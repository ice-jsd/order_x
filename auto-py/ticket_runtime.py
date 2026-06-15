import os
import re
import sys
import unicodedata
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterator


def load_local_env() -> None:
    env_path = Path(__file__).resolve().parent / ".env.local"
    if not env_path.exists():
        return
    for raw_line in env_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[len("export "):].strip()
        if "=" not in line:
            continue
        name, value = line.split("=", 1)
        name = name.strip()
        if not name or name in os.environ:
            continue
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        os.environ[name] = value


load_local_env()


def env_text(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


def bool_value(value: Any) -> bool:
    if isinstance(value, bool):
        return value
    return str(value or "").strip().lower() in {"1", "true", "yes", "y", "on"}


SENSITIVE_KEY_PATTERN = re.compile(
    r"(?i)(authenticity_token|csrf_token|csrf-token|g-recaptcha-response|recaptcha|"
    r"cookieHeader|cookie|authorization|password|任务ID|\btoken\b)"
)
LONG_SECRET_PATTERN = re.compile(r"(?<![A-Za-z0-9])([A-Za-z0-9_\-]{24,})(?![A-Za-z0-9])")
KEY_VALUE_SECRET_PATTERN = re.compile(
    r"(?i)(\"?(?:authenticity_token|csrf_token|csrf-token|g-recaptcha-response|recaptcha|"
    r"cookieHeader|cookie|authorization|password|任务ID|token)\"?\s*[:=]\s*)"
    r"(\"?)([^\",;\s}]+)(\"?)"
)
COOKIE_PAIR_PATTERN = re.compile(r"(?i)\b([A-Za-z0-9_.-]*(?:session|token|cookie|waf|auth)[A-Za-z0-9_.-]*)=([^;\s]+)")
PUBLIC_CODE_KEYS = {"verifycode", "auth_code", "login_auth_code"}
_LOG_CONTEXT: ContextVar[Dict[str, Any]] = ContextVar("ticket_log_context", default={})
LOG_CONTEXT_ORDER = (
    "flow",
    "stage",
    "batchId",
    "index",
    "total",
    "platformCode",
    "accountId",
    "email",
    "trace",
    "executionId",
)


def mask_email(value: str) -> str:
    return value


def mask_code(value: str) -> str:
    text = value
    text = KEY_VALUE_SECRET_PATTERN.sub(lambda m: f"{m.group(1)}{m.group(2)}<masked>{m.group(4)}", text)
    text = COOKIE_PAIR_PATTERN.sub(
        lambda m: m.group(0) if m.group(1).lower() in PUBLIC_CODE_KEYS else f"{m.group(1)}=<masked>",
        text
    )
    if SENSITIVE_KEY_PATTERN.search(text):
        text = re.sub(r"(?<!\d)(\d{4,8})(?!\d)", "<masked-code>", text)
        text = LONG_SECRET_PATTERN.sub(lambda m: f"{m.group(1)[:8]}...{m.group(1)[-4:]}", text)
    return text


def mask_sensitive(value: Any) -> str:
    text = str(value)
    text = mask_email(text)
    text = mask_code(text)
    return sanitize_console_text(text)


@contextmanager
def log_context(**kwargs: Any) -> Iterator[None]:
    current = dict(_LOG_CONTEXT.get() or {})
    for key, value in kwargs.items():
        if value is None or value == "":
            current.pop(key, None)
        else:
            current[key] = value
    token = _LOG_CONTEXT.set(current)
    try:
        yield
    finally:
        _LOG_CONTEXT.reset(token)


def format_log_context() -> str:
    current = dict(_LOG_CONTEXT.get() or {})
    if not current:
        return ""
    ordered_keys = [key for key in LOG_CONTEXT_ORDER if key in current]
    ordered_keys.extend(sorted(key for key in current if key not in LOG_CONTEXT_ORDER))
    parts = []
    for key in ordered_keys:
        value = current.get(key)
        if value is None or value == "":
            continue
        parts.append(f"{key}={mask_sensitive(value)}")
    return " ".join(parts)


def sanitize_console_text(text: str) -> str:
    safe = []
    for char in text.replace("\r", "\\r"):
        if char in ("\n", "\t"):
            safe.append(char)
            continue
        category = unicodedata.category(char)
        if category in {"Cc", "Cf", "Cs"}:
            safe.append(f"\\u{ord(char):04x}")
        else:
            safe.append(char)
    return "".join(safe)


class StructuredLogger:
    def __init__(self, component: str) -> None:
        self.component = component

    def print(self, *values: Any, sep: str = " ", end: str = "\n", file: Any = None, flush: bool = False) -> None:
        if not values:
            self.info("", end=end, file=file, flush=flush)
            return
        message = sep.join(mask_sensitive(value) for value in values)
        level, normalized = normalize_legacy_message(message)
        self.write(level, normalized, end=end, file=file, flush=flush)

    def debug(self, message: Any, *args: Any, **kwargs: Any) -> None:
        self.write("DEBUG", mask_sensitive(self.format_message(message, args)), **kwargs)

    def info(self, message: Any, *args: Any, **kwargs: Any) -> None:
        self.write("INFO", mask_sensitive(self.format_message(message, args)), **kwargs)

    def success(self, message: Any, *args: Any, **kwargs: Any) -> None:
        self.write("SUCCESS", mask_sensitive(self.format_message(message, args)), **kwargs)

    def warning(self, message: Any, *args: Any, **kwargs: Any) -> None:
        self.write("WARN", mask_sensitive(self.format_message(message, args)), **kwargs)

    def error(self, message: Any, *args: Any, **kwargs: Any) -> None:
        self.write("ERROR", mask_sensitive(self.format_message(message, args)), **kwargs)

    def format_message(self, message: Any, args: tuple[Any, ...]) -> str:
        text = str(message)
        if not args:
            return text
        try:
            if len(args) == 1 and isinstance(args[0], dict):
                return text % args[0]
            return text % args
        except Exception:
            return " ".join([text, *(str(arg) for arg in args)])

    def write(self, level: str, message: str, end: str = "\n", file: Any = None, flush: bool = True) -> None:
        timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        context = format_log_context()
        body = f"{context} | {message}" if context else message
        output = f"{timestamp} | {level:<7} | {self.component} | {body}"
        target = file if file is not None else sys.stdout
        builtins_print(output, end=end, file=target, flush=flush)


def normalize_legacy_message(message: str) -> tuple[str, str]:
    text = message.strip()
    if not text:
        return "INFO", ""
    if text.startswith("[X]"):
        return "ERROR", text[3:].strip()
    if text.startswith("[OK]"):
        return "SUCCESS", text[4:].strip()
    if text.startswith("[DEBUG]"):
        return "DEBUG", text[7:].strip()
    if text.startswith("[!]"):
        return "WARN", text[3:].strip()
    if text.startswith("[ERROR]"):
        return "ERROR", text[7:].strip()
    if text.startswith("[WARN]"):
        return "WARN", text[6:].strip()
    if text.startswith("[INFO]"):
        return "INFO", text[6:].strip()
    return "INFO", text


def get_logger(component: str) -> StructuredLogger:
    return StructuredLogger(component)


builtins_print = print
