import json
import os
import time
from typing import Any, Dict, Optional

try:
    import redis
except ImportError:
    redis = None


def env_text(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def utc_now_text() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


class RedisEventPublisher:
    def __init__(self, redis_client: Optional[Any] = None) -> None:
        if redis_client is not None:
            self.client = redis_client
            return
        if redis is None:
            raise RuntimeError("缺少 redis 依赖，请先安装 auto-py/requirements.txt")
        redis_url = env_text("LOTTERY_REDIS_URL") or env_text("PYTHON_EXECUTOR_REDIS_URL")
        if redis_url:
            self.client = redis.Redis.from_url(redis_url, decode_responses=True)
            return
        self.client = redis.Redis(
            host=env_text("LOTTERY_REDIS_HOST", env_text("PYTHON_EXECUTOR_REDIS_HOST", "127.0.0.1")),
            port=int(env_text("LOTTERY_REDIS_PORT", env_text("PYTHON_EXECUTOR_REDIS_PORT", "6379"))),
            password=os.environ.get("LOTTERY_REDIS_PASSWORD") or os.environ.get("PYTHON_EXECUTOR_REDIS_PASSWORD") or None,
            db=int(env_text("LOTTERY_REDIS_DB", env_text("PYTHON_EXECUTOR_REDIS_DB", "0"))),
            decode_responses=True,
        )

    def publish(self, stream_key: str, payload: Dict[str, Any]) -> None:
        event: Dict[str, str] = {}
        for key, value in payload.items():
            if value is None:
                continue
            if isinstance(value, (dict, list, tuple)):
                event[key] = json.dumps(value, ensure_ascii=False)
            else:
                event[key] = str(value)
        event.setdefault("emittedAt", utc_now_text())
        self.client.xadd(stream_key, event)


REGISTER_RESULT_STREAM = "ticket:livepocket:register:stream:result"
LOGIN_RESULT_STREAM = "ticket:livepocket:login:stream:result"
