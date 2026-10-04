import json
import os
import random
import re
import secrets
import threading
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from urllib.parse import quote, unquote, urlparse

import requests

from ticket_runtime import bool_value, env_text, get_logger


LOGGER = get_logger("livepocket.proxy")
_LOGGER_CACHE: Dict[str, Any] = {"livepocket.proxy": LOGGER}


class LivePocketProxyError(RuntimeError):
    """Raised when a LivePocket request looks blocked by IP/proxy quality."""


@dataclass(frozen=True)
class LivePocketProxy:
    raw: str
    host: str
    port: str
    username: str = ""
    password: str = ""
    provider: str = "ipweb"
    region: str = "JP"

    def requests_url(self) -> str:
        if self.username or self.password:
            username = quote(self.username, safe="")
            password = quote(self.password, safe="")
            return f"http://{username}:{password}@{self.host}:{self.port}"
        return f"http://{self.host}:{self.port}"

    def requests_proxies(self) -> Dict[str, str]:
        proxy_url = self.requests_url()
        return {"http": proxy_url, "https": proxy_url}

    def playwright_proxy(self) -> Dict[str, str]:
        config = {"server": f"http://{self.host}:{self.port}"}
        if self.username:
            config["username"] = self.username
        if self.password:
            config["password"] = self.password
        return config

    def masked(self) -> str:
        return f"{self.provider}/{self.region} {self.host}:{self.port}"


def proxy_logger_name(platform_code: str = "livepocket") -> str:
    code = str(platform_code or "").strip()
    return f"{code}.proxy" if code else "ticket.proxy"


def proxy_logger(platform_code: str = "livepocket"):
    name = proxy_logger_name(platform_code)
    logger = _LOGGER_CACHE.get(name)
    if logger is None:
        logger = get_logger(name)
        _LOGGER_CACHE[name] = logger
    return logger


def env_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None or value.strip() == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def proxy_runtime_name() -> str:
    explicit = str(env_text("LIVEPOCKET_PROXY_RUNTIME", "") or "").strip().lower()
    if explicit in {"local", "server"}:
        return explicit
    return "local" if os.name == "nt" else "server"


def proxy_scope() -> str:
    scope = str(env_text("LIVEPOCKET_PROXY_SCOPE", "all") or "").strip().lower()
    return scope or "all"


def proxy_scope_allows_runtime(scope: str, runtime: str) -> bool:
    normalized_scope = str(scope or "").strip().lower() or "all"
    normalized_runtime = str(runtime or "").strip().lower() or "local"
    if normalized_scope in {"disabled", "off", "none"}:
        return False
    if normalized_scope == "server-only":
        return normalized_runtime == "server"
    if normalized_scope == "local-only":
        return normalized_runtime == "local"
    return True


def proxy_enabled() -> bool:
    configured = bool_value(env_text("LIVEPOCKET_PROXY_ENABLED", "false"))
    if not configured:
        return False
    return proxy_scope_allows_runtime(proxy_scope(), proxy_runtime_name())


def proxy_configured() -> bool:
    return bool_value(env_text("LIVEPOCKET_PROXY_ENABLED", "false"))


def parse_proxy_string(raw: str, provider: str = "ipweb", region: str = "JP") -> Optional[LivePocketProxy]:
    text = str(raw or "").strip()
    if not text:
        return None

    if text.startswith(("http://", "https://")):
        parsed = urlparse(text)
        if not parsed.hostname or not parsed.port:
            return None
        return LivePocketProxy(
            raw=text,
            host=parsed.hostname,
            port=str(parsed.port),
            username=unquote(parsed.username or ""),
            password=unquote(parsed.password or ""),
            provider=provider,
            region=region,
        )

    parts = text.split(":")
    if len(parts) == 4:
        host, port, username, password = parts
        return LivePocketProxy(text, host, port, username, password, provider, region)
    if len(parts) == 2:
        host, port = parts
        return LivePocketProxy(text, host, port, provider=provider, region=region)
    return None


def _extract_ipweb_proxy_items(payload: Any) -> List[str]:
    if isinstance(payload, list):
        data = payload
    elif isinstance(payload, dict):
        data = payload.get("data") or payload.get("list") or payload.get("result") or []
    else:
        data = []

    items: List[str] = []
    for item in data:
        if isinstance(item, str):
            if item.strip():
                items.append(item.strip())
            continue
        if not isinstance(item, dict):
            continue
        username = item.get("username") or item.get("user") or item.get("account") or item.get("proxyUser")
        password = item.get("password") or item.get("pass") or item.get("pwd") or item.get("proxyPassword")
        host = item.get("host") or item.get("ip") or item.get("server")
        port = item.get("port")
        if host and port and username and password:
            items.append(f"{host}:{port}:{username}:{password}")
        elif username and password:
            items.append(f"{username}:{password}")
    return items


class LivePocketProxyPool:
    def __init__(self) -> None:
        self.provider = (env_text("LIVEPOCKET_PROXY_PROVIDER", "ipweb") or "ipweb").lower()
        self.region = env_text("LIVEPOCKET_PROXY_REGION", "JP") or "JP"
        default_gateway = "gate.decodo.com:7000" if self.provider == "decodo" else "gate1.ipweb.cc:7778"
        self.gateway = env_text("LIVEPOCKET_PROXY_GATEWAY", default_gateway) or default_gateway
        self.proxy_username = env_text("LIVEPOCKET_PROXY_USERNAME", "")
        self.proxy_password = env_text("LIVEPOCKET_PROXY_PASSWORD", "")
        self.target_approved = bool_value(env_text("LIVEPOCKET_PROXY_TARGET_APPROVED", "false"))
        self.session_minutes = min(max(env_int("LIVEPOCKET_PROXY_SESSION_MINUTES", 60), 1), 1440)
        self.api_url = env_text("LIVEPOCKET_PROXY_API_URL", "http://api.ipweb.cc:8004/api/agent/account2")
        self.api_token = env_text("LIVEPOCKET_PROXY_API_TOKEN", "")
        self.batch_size = max(env_int("LIVEPOCKET_PROXY_BATCH_SIZE", 10), 1)
        self.min_pool_size = max(env_int("LIVEPOCKET_PROXY_POOL_MIN", 5), 0)
        self.times = max(env_int("LIVEPOCKET_PROXY_TIMES", 10), 1)
        self.max_attempts = max(env_int("LIVEPOCKET_PROXY_MAX_ATTEMPTS", 3), 1)
        self._available: List[LivePocketProxy] = []
        self._sticky: Dict[str, LivePocketProxy] = {}
        self._failed_raw: set[str] = set()
        self._lock = threading.RLock()

    def is_enabled(self) -> bool:
        return proxy_enabled()

    def get(self, key: str = "", refresh: bool = False, platform_code: str = "livepocket") -> Optional[LivePocketProxy]:
        if not self.is_enabled():
            return None
        logger = proxy_logger(platform_code)
        sticky_key = str(key or "").strip()
        with self._lock:
            if sticky_key and not refresh and sticky_key in self._sticky:
                return self._sticky[sticky_key]
            self._refill_if_needed_locked(platform_code)
            proxy = self._pop_locked(platform_code)
            if proxy and sticky_key:
                self._sticky[sticky_key] = proxy
            if proxy:
                logger.info(f"分配代理: {proxy.masked()}, key={sticky_key or '-'}, pool={len(self._available)}")
            return proxy

    def mark_failed(
        self,
        proxy: Optional[LivePocketProxy],
        reason: str = "",
        key: str = "",
        platform_code: str = "livepocket",
    ) -> None:
        if not proxy:
            return
        logger = proxy_logger(platform_code)
        with self._lock:
            self._failed_raw.add(proxy.raw)
            if key and self._sticky.get(str(key)) == proxy:
                self._sticky.pop(str(key), None)
            for sticky_key, sticky_proxy in list(self._sticky.items()):
                if sticky_proxy == proxy:
                    self._sticky.pop(sticky_key, None)
            self._available = [item for item in self._available if item != proxy]
        logger.warning(f"代理标记失败: {proxy.masked()}, reason={reason or '-'}")

    def status(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "configured": proxy_configured(),
                "enabled": self.is_enabled(),
                "scope": proxy_scope(),
                "runtime": proxy_runtime_name(),
                "provider": self.provider,
                "region": self.region,
                "gateway": self.gateway,
                "targetApproved": self.target_approved,
                "available": len(self._available),
                "sticky": len(self._sticky),
                "failed": len(self._failed_raw),
            }

    def _pop_locked(self, platform_code: str = "livepocket") -> Optional[LivePocketProxy]:
        self._refill_if_needed_locked(platform_code)
        if not self._available:
            return None
        index = random.randrange(len(self._available))
        return self._available.pop(index)

    def _refill_if_needed_locked(self, platform_code: str = "livepocket") -> None:
        if len(self._available) >= self.min_pool_size:
            return
        for proxy in self._fetch_proxies(self.batch_size, platform_code):
            if proxy.raw not in self._failed_raw and proxy not in self._available:
                self._available.append(proxy)

    def _fetch_proxies(self, count: int, platform_code: str = "livepocket") -> List[LivePocketProxy]:
        logger = proxy_logger(platform_code)
        if self.provider == "decodo" and not self.target_approved:
            logger.warning("Decodo Ticketing 目标尚未确认解锁，拒绝启用代理")
            return []
        static_proxy = env_text("LIVEPOCKET_PROXY_STATIC", "")
        if static_proxy:
            proxy = parse_proxy_string(static_proxy, self.provider, self.region)
            return [proxy] if proxy else []

        if self.provider == "decodo":
            return self._build_decodo_proxies(count, logger)
        if self.provider != "ipweb":
            logger.warning(f"不支持的代理供应商: {self.provider}")
            return []
        if not self.api_token:
            logger.warning("代理已开启，但缺少 LIVEPOCKET_PROXY_API_TOKEN")
            return []

        headers = {"Token": self.api_token}
        params = {"country": self.region, "times": self.times, "limit": count}
        try:
            response = requests.get(self.api_url, headers=headers, params=params, timeout=20)
            if response.status_code != 200:
                logger.warning(f"获取代理失败: HTTP {response.status_code}")
                return []
            try:
                payload = response.json()
            except json.JSONDecodeError:
                payload = json.loads(response.content.decode("utf-8", errors="ignore"))
        except Exception as exc:
            logger.warning(f"获取代理异常: {exc}")
            return []

        gateway_proxy = parse_proxy_string(self.gateway, self.provider, self.region)
        if not gateway_proxy:
            logger.warning(f"代理网关格式错误: {self.gateway}")
            return []

        proxies: List[LivePocketProxy] = []
        for item in _extract_ipweb_proxy_items(payload):
            if item.count(":") == 1:
                username, password = item.split(":", 1)
                proxies.append(
                    LivePocketProxy(
                        raw=f"{gateway_proxy.host}:{gateway_proxy.port}:{username}:{password}",
                        host=gateway_proxy.host,
                        port=gateway_proxy.port,
                        username=username,
                        password=password,
                        provider=self.provider,
                        region=self.region,
                    )
                )
                continue
            proxy = parse_proxy_string(item, self.provider, self.region)
            if proxy:
                proxies.append(proxy)
                continue
            if ":" not in item:
                continue
            username, password = item.split(":", 1)
            proxies.append(
                LivePocketProxy(
                    raw=f"{gateway_proxy.host}:{gateway_proxy.port}:{username}:{password}",
                    host=gateway_proxy.host,
                    port=gateway_proxy.port,
                    username=username,
                    password=password,
                    provider=self.provider,
                    region=self.region,
                )
            )
        logger.info(f"获取代理完成: count={len(proxies)}, provider={self.provider}, region={self.region}")
        return proxies

    def _build_decodo_proxies(self, count: int, logger: Any) -> List[LivePocketProxy]:
        if not self.target_approved:
            logger.warning("Decodo Ticketing 目标尚未确认解锁，拒绝启用代理")
            return []
        if not self.proxy_username or not self.proxy_password:
            logger.warning("Decodo 代理已开启，但缺少 LIVEPOCKET_PROXY_USERNAME 或 LIVEPOCKET_PROXY_PASSWORD")
            return []

        gateway_proxy = parse_proxy_string(self.gateway, self.provider, self.region)
        if not gateway_proxy:
            logger.warning(f"代理网关格式错误: {self.gateway}")
            return []

        base_username = self.proxy_username
        if not base_username.startswith("user-"):
            base_username = f"user-{base_username}"
        country = self.region.strip().lower()
        proxies: List[LivePocketProxy] = []
        for _ in range(max(count, 1)):
            session_id = secrets.token_hex(8)
            username = base_username
            if country:
                username = f"{username}-country-{country}"
            username = f"{username}-session-{session_id}-sessionduration-{self.session_minutes}"
            raw = f"{gateway_proxy.host}:{gateway_proxy.port}:{username}:{self.proxy_password}"
            proxies.append(
                LivePocketProxy(
                    raw=raw,
                    host=gateway_proxy.host,
                    port=gateway_proxy.port,
                    username=username,
                    password=self.proxy_password,
                    provider=self.provider,
                    region=self.region,
                )
            )
        logger.info(f"生成 Decodo 粘性会话: count={len(proxies)}, region={self.region}")
        return proxies


_POOL = LivePocketProxyPool()


def get_proxy(key: str = "", refresh: bool = False, platform_code: str = "livepocket") -> Optional[LivePocketProxy]:
    return _POOL.get(key=key, refresh=refresh, platform_code=platform_code)


def mark_proxy_failed(
    proxy: Optional[LivePocketProxy],
    reason: str = "",
    key: str = "",
    platform_code: str = "livepocket",
) -> None:
    _POOL.mark_failed(proxy, reason=reason, key=key, platform_code=platform_code)


def proxy_status() -> Dict[str, Any]:
    return _POOL.status()


def proxy_max_attempts() -> int:
    return _POOL.max_attempts if _POOL.is_enabled() else 1


def apply_proxy_to_session(session: requests.Session, proxy: Optional[LivePocketProxy]) -> None:
    if proxy:
        session.proxies.update(proxy.requests_proxies())


def is_blocked_livepocket_response(response: Optional[requests.Response], require_token: bool = False) -> bool:
    if response is None:
        return False
    text = response.text or ""
    final_url = response.url or ""
    title_match = re.search(r"<title[^>]*>(.*?)</title>", text, flags=re.I | re.S)
    title = title_match.group(1).strip() if title_match else ""
    waf_action = response.headers.get("x-amzn-waf-action", "")
    if response.status_code == 202 or waf_action.lower() == "challenge":
        return True
    if "AwsWafIntegration" in text or ("challenge.js" in text and "awswaf.com" in text):
        return True
    if response.status_code in (403, 407):
        return True
    if "/sorry/403" in final_url or "/sorry/403" in text:
        return True
    if "不正なリクエスト" in title or "不正なリクエスト" in text[:1000]:
        return True
    if require_token and "authenticity_token" not in text:
        return True
    return False
