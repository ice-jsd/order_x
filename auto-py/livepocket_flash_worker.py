"""
LivePocket 普通抢票 Redis Worker。

独立消费 flash_sale 队列，结果仍写入既有 lottery result stream，方便 Java 复用结果回写。
"""
import json
import os
import socket
import threading
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

from livepocket_flash_sale import execute_flash_sale, prewarm_flash_sale
from ticket_runtime import get_logger

try:
    import redis
except ImportError:
    redis = None


LOGGER = get_logger("livepocket.flash_worker")


def env_text(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        return int(raw)
    except ValueError:
        return default


def utc_now_text() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def log(message: str) -> None:
    LOGGER.print(message, flush=True)


class FlashSaleRedisWorkerConfig:
    def __init__(self) -> None:
        self.redis_url = env_text("LOTTERY_REDIS_URL")
        self.redis_host = env_text("LOTTERY_REDIS_HOST", "127.0.0.1")
        self.redis_port = env_int("LOTTERY_REDIS_PORT", 6379)
        self.redis_password = os.environ.get("LOTTERY_REDIS_PASSWORD") or None
        self.redis_db = env_int("LOTTERY_REDIS_DB", 0)
        self.ready_stream_key = env_text("FLASH_SALE_READY_STREAM_KEY", "ticket:flash-sale:stream:ready")
        self.delayed_zset_key = env_text("FLASH_SALE_DELAYED_ZSET_KEY", "ticket:flash-sale:zset:delayed")
        self.job_key_prefix = env_text("FLASH_SALE_JOB_KEY_PREFIX", "ticket:flash-sale:job:")
        self.result_stream_key = env_text("LOTTERY_RESULT_STREAM_KEY", "ticket:lottery:stream:result")
        self.account_lock_key_prefix = env_text("FLASH_SALE_ACCOUNT_LOCK_KEY_PREFIX", "ticket:lottery:lock:account:")
        self.consumer_group = env_text("FLASH_SALE_CONSUMER_GROUP", "ticket-flash-sale-executor")
        default_consumer = f"ticket-flash-sale-{socket.gethostname()}-{os.getpid()}"
        self.consumer_name = env_text("FLASH_SALE_CONSUMER_NAME", default_consumer)
        self.workers = max(env_int("FLASH_SALE_WORKERS", 100), 1)
        self.block_ms = max(env_int("FLASH_SALE_BLOCK_MS", 1000), 200)
        self.poll_interval_ms = max(env_int("FLASH_SALE_POLL_INTERVAL_MS", 100), 20)
        self.pending_reclaim_idle_ms = max(env_int("FLASH_SALE_PENDING_RECLAIM_IDLE_MS", 60000), 5000)
        self.account_lock_ttl_seconds = max(env_int("FLASH_SALE_ACCOUNT_LOCK_TTL_SECONDS", 300), 30)
        self.requeue_delay_seconds = max(env_int("FLASH_SALE_REQUEUE_DELAY_SECONDS", 1), 1)
        self.job_ttl_seconds = max(env_int("FLASH_SALE_JOB_TTL_SECONDS", 7 * 24 * 3600), 60)


class FlashSaleRedisWorker:
    def __init__(self, config: Optional[FlashSaleRedisWorkerConfig] = None) -> None:
        if redis is None:
            raise RuntimeError("缺少 redis 依赖，请先安装 auto-py/requirements.txt")
        self.config = config or FlashSaleRedisWorkerConfig()
        self.client = self.create_client()
        self.stop_event = threading.Event()
        self.threads: List[threading.Thread] = []
        self.started_at = utc_now_text()

    def create_client(self):
        if self.config.redis_url:
            return redis.Redis.from_url(self.config.redis_url, decode_responses=True)
        return redis.Redis(
            host=self.config.redis_host,
            port=self.config.redis_port,
            password=self.config.redis_password,
            db=self.config.redis_db,
            decode_responses=True,
        )

    def ensure_group(self) -> None:
        try:
            self.client.xgroup_create(self.config.ready_stream_key, self.config.consumer_group, id="0", mkstream=True)
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def start(self) -> None:
        self.client.ping()
        self.ensure_group()
        log(
            "[flash-sale-worker] START "
            f"workers={self.config.workers}, ready={self.config.ready_stream_key}, "
            f"delayed={self.config.delayed_zset_key}, result={self.config.result_stream_key}, "
            f"group={self.config.consumer_group}, consumer={self.config.consumer_name}"
        )
        promoter = threading.Thread(target=self.promote_loop, name="flash-sale-delayed-promoter", daemon=True)
        promoter.start()
        self.threads.append(promoter)
        for index in range(self.config.workers):
            thread = threading.Thread(target=self.worker_loop, args=(index,), name=f"flash-sale-worker-{index}", daemon=True)
            thread.start()
            self.threads.append(thread)

    def stop(self) -> None:
        self.stop_event.set()

    def promote_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                self.promote_due_jobs()
            except Exception as exc:
                log(f"[flash-sale-worker] promote delayed failed: {exc}")
            time.sleep(self.config.poll_interval_ms / 1000)

    def promote_due_jobs(self) -> None:
        now_ms = int(time.time() * 1000)
        items = self.client.zrangebyscore(self.config.delayed_zset_key, 0, now_ms, start=0, num=500)
        for member in items:
            removed = self.client.zrem(self.config.delayed_zset_key, member)
            if removed:
                execution_id, phase = self.parse_member(member)
                self.add_ready(execution_id, phase)

    def add_ready(self, execution_id: Any, phase: str = "run") -> None:
        self.client.xadd(
            self.config.ready_stream_key,
            {"executionId": str(execution_id), "phase": phase, "enqueuedAt": str(int(time.time() * 1000))},
        )

    def worker_loop(self, index: int) -> None:
        consumer_name = f"{self.config.consumer_name}-{index}"
        while not self.stop_event.is_set():
            try:
                if self.reclaim_one_pending(consumer_name):
                    continue
                messages = self.client.xreadgroup(
                    self.config.consumer_group,
                    consumer_name,
                    {self.config.ready_stream_key: ">"},
                    count=1,
                    block=self.config.block_ms,
                )
                if not messages:
                    continue
                for _, stream_messages in messages:
                    for message_id, fields in stream_messages:
                        self.handle_message(message_id, fields)
            except Exception as exc:
                log(f"[flash-sale-worker] worker={index} exception: {exc}")
                time.sleep(0.2)

    def reclaim_one_pending(self, consumer_name: str) -> bool:
        try:
            result = self.client.xautoclaim(
                self.config.ready_stream_key,
                self.config.consumer_group,
                consumer_name,
                min_idle_time=self.config.pending_reclaim_idle_ms,
                start_id="0-0",
                count=1,
            )
            messages = result[1] if isinstance(result, (list, tuple)) and len(result) > 1 else []
            for message_id, fields in messages:
                self.handle_message(message_id, fields)
                return True
        except Exception as exc:
            log(f"[flash-sale-worker] pending reclaim skipped: {exc}")
        return False

    def handle_message(self, message_id: str, fields: Dict[str, Any]) -> None:
        execution_id = str(fields.get("executionId") or "").strip()
        phase = str(fields.get("phase") or "run").strip() or "run"
        if not execution_id:
            self.ack(message_id)
            return
        job_key = self.job_key(execution_id)
        payload_text = self.client.get(job_key)
        if not payload_text:
            if phase == "run":
                self.write_result({"executionId": execution_id, "status": "failed", "success": False, "message": "普通抢票任务载荷不存在"})
            self.ack(message_id)
            return
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            if phase == "run":
                self.write_result({"executionId": execution_id, "status": "failed", "success": False, "message": "普通抢票任务载荷不是合法 JSON"})
            self.ack(message_id)
            return

        account_id = str(payload.get("accountId") or "")
        lock_token = str(uuid.uuid4())
        lock_key = self.config.account_lock_key_prefix + account_id if account_id else ""
        if lock_key and not self.client.set(lock_key, lock_token, nx=True, ex=self.config.account_lock_ttl_seconds):
            self.requeue(execution_id, phase, self.config.requeue_delay_seconds)
            self.ack(message_id)
            log(f"[flash-sale-worker] account locked, requeued executionId={execution_id}, accountId={account_id}, phase={phase}")
            return

        try:
            if phase == "warmup":
                self.handle_warmup(execution_id, job_key, payload)
            else:
                self.handle_run(execution_id, job_key, payload)
        finally:
            if lock_key:
                self.release_lock(lock_key, lock_token)
            self.ack(message_id)

    def handle_warmup(self, execution_id: str, job_key: str, payload: Dict[str, Any]) -> None:
        started_at = utc_now_text()
        result = prewarm_flash_sale(payload)
        payload["flashWarmupAt"] = utc_now_text()
        payload["flashWarmupStatus"] = result.get("status")
        payload["flashWarmupMessage"] = result.get("message")
        if result.get("loginReqData"):
            payload["loginReqData"] = result.get("loginReqData")
        if result.get("authSource"):
            payload["flashWarmupAuthSource"] = result.get("authSource")
        self.client.set(job_key, json.dumps(payload, ensure_ascii=False), ex=self.config.job_ttl_seconds)
        self.schedule_run(execution_id, payload)
        log(
            "[flash-sale-worker] WARMUP "
            f"executionId={execution_id}, success={result.get('success')}, status={result.get('status')}, "
            f"message={result.get('message')}, startedAt={started_at}"
        )

    def handle_run(self, execution_id: str, job_key: str, payload: Dict[str, Any]) -> None:
        account_id = str(payload.get("accountId") or "")
        started_at = utc_now_text()
        self.write_result(
            {
                "executionId": execution_id,
                "scheduleId": payload.get("scheduleId", ""),
                "accountId": account_id,
                "status": "running",
                "success": False,
                "message": "Python 普通抢票执行中",
                "startedAt": started_at,
            }
        )
        try:
            result = execute_flash_sale(payload)
            result.update(
                {
                    "executionId": execution_id,
                    "scheduleId": payload.get("scheduleId", ""),
                    "accountId": account_id,
                    "startedAt": started_at,
                    "finishedAt": utc_now_text(),
                }
            )
            self.write_result(result)
            if result.get("success"):
                self.client.delete(job_key)
            log(
                "[flash-sale-worker] DONE "
                f"executionId={execution_id}, status={result.get('status')}, success={result.get('success')}, "
                f"message={result.get('message')}"
            )
        except Exception as exc:
            self.write_result(
                {
                    "executionId": execution_id,
                    "scheduleId": payload.get("scheduleId", ""),
                    "accountId": account_id,
                    "status": "failed",
                    "success": False,
                    "message": f"普通抢票执行异常：{exc}",
                    "startedAt": started_at,
                    "finishedAt": utc_now_text(),
                }
            )

    def schedule_run(self, execution_id: str, payload: Dict[str, Any]) -> None:
        dispatch_at = self.long_value(payload.get("scheduledTime"))
        if dispatch_at > int(time.time() * 1000):
            self.client.zadd(self.config.delayed_zset_key, {self.member(execution_id, "run"): dispatch_at})
        else:
            self.add_ready(execution_id, "run")

    def requeue(self, execution_id: str, phase: str, delay_seconds: int) -> None:
        self.client.zadd(
            self.config.delayed_zset_key,
            {self.member(execution_id, phase): int((time.time() + delay_seconds) * 1000)},
        )

    def write_result(self, payload: Dict[str, Any]) -> None:
        self.write_stream_event(self.config.result_stream_key, payload)

    def write_stream_event(self, stream_key: str, payload: Dict[str, Any]) -> None:
        event: Dict[str, str] = {}
        for key, value in payload.items():
            if value is None:
                event[key] = ""
            elif isinstance(value, (dict, list)):
                event[key] = json.dumps(value, ensure_ascii=False)
            else:
                event[key] = str(value)
        event.setdefault("emittedAt", utc_now_text())
        self.client.xadd(stream_key, event)

    def release_lock(self, lock_key: str, lock_token: str) -> None:
        script = """
if redis.call("GET", KEYS[1]) == ARGV[1] then
    return redis.call("DEL", KEYS[1])
end
return 0
"""
        try:
            self.client.eval(script, 1, lock_key, lock_token)
        except Exception as exc:
            log(f"[flash-sale-worker] release account lock failed key={lock_key}: {exc}")

    def ack(self, message_id: str) -> None:
        self.client.xack(self.config.ready_stream_key, self.config.consumer_group, message_id)

    def health(self) -> Dict[str, Any]:
        pong = self.client.ping()
        return {
            "ok": bool(pong),
            "mode": "flash-sale-worker",
            "startedAt": self.started_at,
            "workers": self.config.workers,
            "readyStream": self.config.ready_stream_key,
            "resultStream": self.config.result_stream_key,
            "delayedZset": self.config.delayed_zset_key,
            "readyLength": self.client.xlen(self.config.ready_stream_key),
            "delayedCount": self.client.zcard(self.config.delayed_zset_key),
        }

    def job_key(self, execution_id: Any) -> str:
        return self.config.job_key_prefix + str(execution_id)

    @staticmethod
    def member(execution_id: Any, phase: str) -> str:
        return f"{execution_id}:{phase or 'run'}"

    @staticmethod
    def parse_member(member: Any) -> Tuple[str, str]:
        text = str(member or "")
        if ":" in text:
            execution_id, phase = text.rsplit(":", 1)
            if execution_id and phase in {"warmup", "run"}:
                return execution_id, phase
        return text, "run"

    @staticmethod
    def long_value(value: Any) -> int:
        try:
            return int(str(value or "0"))
        except ValueError:
            return 0
