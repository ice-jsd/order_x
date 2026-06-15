import time
from typing import Any, Dict, Optional

import requests

from ticket_runtime import env_text, get_logger


LOGGER = get_logger("backend.api")
print = LOGGER.print

DEFAULT_BACKEND_BASE_URL = env_text("TICKET_BACKEND_BASE_URL")
DEFAULT_PLATFORM_CODE = env_text("TICKET_PLATFORM_CODE", "livepocket")


def env_int(name: str, default: int) -> int:
    value = env_text(name, "")
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


DEFAULT_BACKEND_TIMEOUT_SECONDS = max(env_int("TICKET_BACKEND_TIMEOUT_SECONDS", 30), 1)
DEFAULT_BACKEND_REGISTER_TIMEOUT_SECONDS = max(
    env_int("TICKET_BACKEND_REGISTER_TIMEOUT_SECONDS", 45),
    DEFAULT_BACKEND_TIMEOUT_SECONDS,
)


def normalize_backend_base_url(base_url: Optional[str] = None) -> str:
    base = str(base_url or DEFAULT_BACKEND_BASE_URL or "").strip().rstrip("/")
    if not base:
        raise RuntimeError("缺少 Java 外部账号接口地址，请由 Java 请求传入 backendBaseUrl，或直接调试 Python 时配置 TICKET_BACKEND_BASE_URL")
    return base


def normalize_platform_code(platform_code: Optional[str] = None) -> str:
    return str(platform_code or DEFAULT_PLATFORM_CODE or "livepocket").strip() or "livepocket"


class TicketBackendApi:
    def __init__(
        self,
        base_url: Optional[str] = None,
        platform_code: Optional[str] = None,
        timeout_seconds: Optional[int] = None,
        register_timeout_seconds: Optional[int] = None,
    ) -> None:
        self.base_url = normalize_backend_base_url(base_url)
        self.platform_code = normalize_platform_code(platform_code)
        self.timeout_seconds = max(int(timeout_seconds or DEFAULT_BACKEND_TIMEOUT_SECONDS), 1)
        self.register_timeout_seconds = max(
            int(register_timeout_seconds or DEFAULT_BACKEND_REGISTER_TIMEOUT_SECONDS),
            self.timeout_seconds,
        )

    def next_register(self) -> Optional[Dict[str, Any]]:
        print(
            "[Backend] 开始获取注册信息: "
            f"platform={self.platform_code}, url={self.base_url}/next-register, timeout={self.register_timeout_seconds}s"
        )
        try:
            resp = requests.post(
                f"{self.base_url}/next-register",
                params={"platformCode": self.platform_code},
                timeout=self.register_timeout_seconds,
            )
        except requests.Timeout as exc:
            print(
                "[Backend] 获取注册信息超时: "
                f"platform={self.platform_code}, timeout={self.register_timeout_seconds}s, error={exc}"
            )
            raise RuntimeError(
                f"获取注册信息超时（等待 {self.register_timeout_seconds}s），"
                "后端可能仍在等待短信平台响应"
            ) from exc
        except requests.RequestException as exc:
            print(f"[Backend] 获取注册信息请求异常: platform={self.platform_code}, error={exc}")
            raise
        try:
            resp.raise_for_status()
            body = resp.json()
        except Exception as exc:
            print(
                "[Backend] 获取注册信息响应解析异常: "
                f"platform={self.platform_code}, status={getattr(resp, 'status_code', '-')}, error={exc}"
            )
            raise
        if body.get("code") == 200:
            data = body.get("data")
            print(
                "[Backend] 注册信息获取成功: "
                f"platform={self.platform_code}, accountId={(data or {}).get('accountId')}, email={(data or {}).get('email')}"
            )
            return data
        message = body.get("msg") or "获取注册信息失败"
        print(
            "[Backend] 获取注册信息失败: "
            f"platform={self.platform_code}, code={body.get('code')}, message={message}"
        )
        raise RuntimeError(message)

    def email_activation_link(self, email: str, attempts: int = 10, interval_seconds: int = 3) -> Optional[str]:
        for _ in range(attempts):
            time.sleep(interval_seconds)
            body = self._get_data("/email-activation-link", email)
            activation_url = (body or {}).get("activationUrl")
            if activation_url:
                return activation_url
        return None

    def email_verify_code(
        self,
        email: str,
        attempts: int = 10,
        interval_seconds: int = 3,
        retry_rounds: int = 1,
    ) -> Optional[str]:
        last_error = ""
        for round_index in range(1, retry_rounds + 1):
            for attempt in range(1, attempts + 1):
                time.sleep(interval_seconds)
                try:
                    body = self._get_data("/email-verify-code", email)
                    verify_code = (body or {}).get("verifyCode")
                    if verify_code:
                        print(f"[Backend] 邮件验证码获取成功: {verify_code}")
                        return verify_code
                    print(f"[Backend] 邮件验证码未就绪: round={round_index}/{retry_rounds}, attempt={attempt}/{attempts}")
                except Exception as exc:
                    last_error = str(exc)
                    print(
                        "[Backend] 邮件验证码获取异常，继续重试: "
                        f"round={round_index}/{retry_rounds}, attempt={attempt}/{attempts}, error={exc}"
                    )
            if round_index < retry_rounds:
                print(f"[Backend] 邮件验证码本轮获取失败，准备重试: {round_index}/{retry_rounds}")
        if last_error:
            print(f"[Backend] 邮件验证码获取失败: {last_error}")
        return None

    def request_manual_login_email_code(
        self,
        batch_id: Any,
        account_id: Any,
        email: str,
        request_id: str,
        timeout_seconds: int,
    ) -> None:
        payload = {
            "platformCode": self.platform_code,
            "batchId": batch_id,
            "accountId": account_id,
            "email": email,
            "requestId": request_id,
            "timeoutSeconds": timeout_seconds,
        }
        resp = requests.post(
            f"{self.base_url}/login-code-request",
            json=payload,
            timeout=self.timeout_seconds,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 200:
            raise RuntimeError(body.get("msg") or "创建人工验证码请求失败")
        print(f"[Backend] 人工验证码请求已创建: requestId={request_id}, email={email}")

    def manual_login_email_code(
        self,
        request_id: str,
        attempts: int,
        interval_seconds: int,
    ) -> Optional[str]:
        for attempt in range(1, max(attempts, 1) + 1):
            time.sleep(max(interval_seconds, 1))
            resp = requests.get(
                f"{self.base_url}/login-code",
                params={"requestId": request_id},
                timeout=self.timeout_seconds,
            )
            resp.raise_for_status()
            body = resp.json()
            if body.get("code") != 200:
                print(f"[Backend] 人工验证码轮询失败: requestId={request_id}, message={body.get('msg')}")
                continue
            data = body.get("data") or {}
            verify_code = data.get("verifyCode")
            if verify_code:
                print(f"[Backend] 人工验证码已提交: requestId={request_id}")
                return str(verify_code).strip()
            print(f"[Backend] 等待人工验证码: requestId={request_id}, attempt={attempt}/{attempts}")
        return None

    def report_manual_login_email_code_invalid(
        self,
        batch_id: Any,
        account_id: Any,
        email: str,
        request_id: str,
        message: str,
    ) -> None:
        payload = {
            "platformCode": self.platform_code,
            "batchId": batch_id,
            "accountId": account_id,
            "email": email,
            "requestId": request_id,
            "message": message,
        }
        try:
            resp = requests.post(
                f"{self.base_url}/login-code-invalid",
                json=payload,
                timeout=self.timeout_seconds,
            )
            resp.raise_for_status()
            body = resp.json()
            if body.get("code") != 200:
                print(f"[Backend] 人工验证码错误回调失败: requestId={request_id}, message={body.get('msg')}")
        except Exception as exc:
            print(f"[Backend] 人工验证码错误回调异常: requestId={request_id}, error={exc}")

    def phone_activation_code(self, email: str, attempts: int = 20, interval_seconds: int = 5) -> Optional[str]:
        last_error = ""
        for attempt in range(1, attempts + 1):
            time.sleep(interval_seconds)
            try:
                body = self._get_data("/phone-activation-code", email)
                verify_code = (body or {}).get("verifyCode")
                if verify_code:
                    print(f"[Backend] 短信验证码获取成功: {verify_code}")
                    return verify_code
                print(f"[Backend] 短信验证码未就绪: attempt={attempt}/{attempts}")
            except Exception as exc:
                last_error = str(exc)
                print(f"[Backend] 短信验证码获取异常，继续重试: attempt={attempt}/{attempts}, error={exc}")
        if last_error:
            print(f"[Backend] 短信验证码获取失败: {last_error}")
        return None

    def _get_data(self, path: str, email: str) -> Optional[Dict[str, Any]]:
        resp = requests.get(
            f"{self.base_url}{path}",
            params={"platformCode": self.platform_code, "email": email},
            timeout=self.timeout_seconds,
        )
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") == 200:
            return body.get("data")
        return None
