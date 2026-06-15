"""
LivePocket 抽票执行器与历史宿主实现（Redis Worker）

职责边界：
- LivePocket 抽票按抽选表单流程执行。
- 运行时先复用 livepocket_purchase.login_livepocket 登录，再访问抽票页面。
- 宿主层会消费 Redis 账号级 job 队列，并提供统一 HTTP 接口。

说明：
- 当前仓库的统一 Python 宿主对外入口已经收敛到 ticket_python_server.py。
- 本文件仍保留宿主实现，原因是现有 LivePocket 逻辑和运行时长期沉淀在这里。
- 新平台接入应尽量把平台能力放到独立模块，再由统一宿主接线。

启动：
    python main.py
"""
import argparse
import hashlib
import socket
import threading
import html
import json
import os
import re
import sys
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, unquote, urljoin, urlparse

import requests
from lottery_platform_router import resolve_event_parser, resolve_executor

from ticket_runtime import get_logger
from livepocket_purchase import extract_ticket_entry_url as submit_order_extract_ticket_entry_url
from livepocket_purchase import login_livepocket as submit_order_login_livepocket
from livepocket_profile import keepalive_livepocket_login, update_livepocket_last_name
from jump_shop import (
    fetch_jump_shop_product_info,
    run_login_batch as run_jump_shop_login_batch,
    run_register_batch as run_jump_shop_register_batch,
)
from livepocket_register import run_register_batch
from livepocket_login import run_login_batch
from livepocket_session import (
    export_session_login_context,
    resolve_authenticated_session,
)
from livepocket_proxy import (
    apply_proxy_to_session,
    get_proxy,
    is_blocked_livepocket_response,
    mark_proxy_failed,
    parse_proxy_string,
    proxy_max_attempts,
    proxy_status,
)

try:
    import redis
except ImportError:
    redis = None


LOGGER = get_logger("lottery.executor")

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/147.0.0.0 Safari/537.36"
)
LIVEPOCKET_BASE_URL = "https://livepocket.jp"
LIVEPOCKET_LOGIN_CHECK_URL = "https://livepocket.jp/my_top"
PURCHASE_CONFIRM_URL = "https://livepocket.jp/purchase/confirm"
PURCHASE_URL = "https://livepocket.jp/purchase"
CACHE_TTL_SECONDS = 600
CACHE_SCHEMA_VERSION = 8
CACHE_DIR = Path(__file__).resolve().parent / ".cache"
CACHE_FILE = CACHE_DIR / "livepocket_lottery_events.json"
EVENT_INFO_CACHE: Dict[str, Dict[str, Any]] = {}
WORKER_RUNTIME: Optional["LotteryRedisWorker"] = None
FLASH_WORKER_RUNTIME: Optional[Any] = None


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


def safe_console_text(value: Any) -> str:
    encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
    return str(value).encode(encoding, errors="backslashreplace").decode(encoding, errors="replace")


def log(message: str) -> None:
    LOGGER.print(safe_console_text(message), flush=True)


def parse_json_maybe(value: Any) -> Dict[str, Any]:
    """兼容 Java 传来的 JSON 字符串或对象。"""
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            data = json.loads(value)
            return data if isinstance(data, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def parse_cookie_header(cookie_header: str) -> Dict[str, str]:
    """把 `_session=...; reauth=...` 转成 requests 可用的 cookie dict。"""
    cookies: Dict[str, str] = {}
    for part in (cookie_header or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name = name.strip()
        if name:
            cookies[name] = value.strip()
    return cookies


def restore_session_from_login_context(login_context: Dict[str, Any], proxy_key: str = "") -> requests.Session:
    """从账号 loginReqData 还原最小可用浏览器请求态。"""
    session = requests.Session()
    proxy = get_proxy(proxy_key)
    apply_proxy_to_session(session, proxy)
    headers = login_context.get("headers") if isinstance(login_context.get("headers"), dict) else {}
    user_agent = login_context.get("userAgent") or headers.get("User-Agent") or DEFAULT_USER_AGENT

    session.headers.update(
        {
            "User-Agent": user_agent,
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language": headers.get("Accept-Language", "ja,en-US;q=0.9,en;q=0.8"),
            "Origin": "https://livepocket.jp",
        }
    )

    cookie_header = login_context.get("cookieHeader") or ""
    for name, value in parse_cookie_header(cookie_header).items():
        session.cookies.set(name, value, domain=".livepocket.jp", path="/")

    cookies = login_context.get("cookies")
    if isinstance(cookies, list):
        for item in cookies:
            if not isinstance(item, dict) or not item.get("name"):
                continue
            session.cookies.set(
                item.get("name"),
                item.get("value", ""),
                domain=item.get("domain") or ".livepocket.jp",
                path=item.get("path") or "/",
            )
    elif isinstance(cookies, dict):
        for name, value in cookies.items():
            session.cookies.set(str(name), str(value), domain=".livepocket.jp", path="/")

    cookies_json = login_context.get("cookiesJson")
    if isinstance(cookies_json, dict):
        for name, value in cookies_json.items():
            session.cookies.set(str(name), str(value), domain=".livepocket.jp", path="/")

    return session


def session_cookie_names(session: requests.Session) -> List[str]:
    names = sorted({cookie.name for cookie in session.cookies})
    return names[:20]


def check_login_state(session: requests.Session) -> Dict[str, Any]:
    """访问 LivePocket 个人页，判断 Java 传来的 cookie 是否仍是登录态。"""
    response = session.get(
        LIVEPOCKET_LOGIN_CHECK_URL,
        headers=request_headers(referer=LIVEPOCKET_BASE_URL + "/"),
        allow_redirects=True,
        timeout=30,
    )
    page_text = response.text or ""
    final_path = urlparse(response.url or "").path
    page_title = extract_page_title(page_text)
    redirected_to_login = "/login" in final_path
    title_is_login = "ログイン" in page_title or "login" in page_title.lower()
    return {
        "loggedIn": response.status_code < 400 and not redirected_to_login and not title_is_login,
        "status": response.status_code,
        "finalUrl": response.url,
        "title": page_title,
        "redirectedToLogin": redirected_to_login,
    }


def login_livepocket_runtime(
    email: str,
    password: str,
    platform_code: str,
    trace_id: str,
    backend_base_url: str = "",
) -> Optional[requests.Session]:
    if not email or not password:
        return None
    log(f"[lottery-entry] LOGIN runtime start trace={trace_id}, email={email}, flow=submit_lottery")
    try:
        session = submit_order_login_livepocket(
            email,
            password,
            debug=False,
            backend_base_url=backend_base_url,
            platform_code=platform_code,
        )
    except Exception as exc:
        log(f"[lottery-entry] LOGIN runtime exception trace={trace_id}, email={email}, error={exc}")
        return None
    if not session:
        log(f"[lottery-entry] LOGIN runtime failed trace={trace_id}, email={email}")
        return None
    log(f"[lottery-entry] LOGIN runtime success trace={trace_id}, email={email}, cookieNames={session_cookie_names(session)}")
    return session


def extract_input_value(page_text: str, input_name: str) -> Optional[str]:
    """从 HTML input 中提取指定 name 的 value。"""
    pattern = (
        r"<input\b(?=[^>]*\bname=[\"']"
        + re.escape(input_name)
        + r"[\"'])(?=[^>]*\bvalue=[\"']([^\"']*)[\"'])[^>]*>"
    )
    match = re.search(pattern, page_text, flags=re.IGNORECASE | re.DOTALL)
    return html.unescape(match.group(1)) if match else None


def extract_hidden_inputs(page_text: str) -> Dict[str, str]:
    """提取页面里已有 hidden input 和已勾选 checkbox。"""
    values: Dict[str, str] = {}
    for match in re.finditer(r"<input\b[^>]*>", page_text, flags=re.IGNORECASE | re.DOTALL):
        tag = match.group(0)
        input_type_match = re.search(r'\btype=["\']([^"\']+)["\']', tag, flags=re.IGNORECASE)
        input_type = input_type_match.group(1).lower() if input_type_match else ""
        is_hidden = input_type == "hidden"
        is_checked_checkbox = input_type == "checkbox" and re.search(r"\bchecked\b", tag, flags=re.IGNORECASE)
        if not is_hidden and not is_checked_checkbox:
            continue
        name_match = re.search(r'\bname=["\']([^"\']+)["\']', tag, flags=re.IGNORECASE)
        if not name_match:
            continue
        value_match = re.search(r'\bvalue=["\']([^"\']*)["\']', tag, flags=re.IGNORECASE)
        values[html.unescape(name_match.group(1))] = html.unescape(value_match.group(1)) if value_match else ""
    return values


def extract_csrf_token(page_text: str) -> Optional[str]:
    match = re.search(
        r'<meta\s+name=["\']csrf-token["\']\s+content=["\']([^"\']+)["\']',
        page_text,
        flags=re.IGNORECASE,
    )
    return html.unescape(match.group(1)) if match else None


def compact_text(value: str, limit: int = 1200) -> str:
    text = re.sub(r"\s+", " ", value or "").strip()
    return text[:limit]


def visible_text(page_text: str) -> str:
    """把 HTML 变成较稳定的可见文本，方便公开页面解析和结果摘要。"""
    text = re.sub(r"<(script|style)\b.*?</\1>", " ", page_text, flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"</(p|div|li|dt|dd|tr|h\d)>", "\n", text, flags=re.IGNORECASE)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    return re.sub(r"\n+", "\n", text).strip()


def parse_japanese_datetime(value: str) -> Optional[str]:
    """把 `2026年5月4日(月) 18:00` 转成 `2026-05-04 18:00:00`。"""
    match = re.search(r"(\d{4})年\s*(\d{1,2})月\s*(\d{1,2})日(?:\([^)]*\))?\s*(\d{1,2}):(\d{2})", value)
    if not match:
        return None
    year, month, day, hour, minute = map(int, match.groups())
    return f"{year:04d}-{month:02d}-{day:02d} {hour:02d}:{minute:02d}:00"


def parse_entry_period(text: str) -> tuple[Optional[str], Optional[str]]:
    """解析抽选受付/販売受付期间。"""
    patterns = [
        r"(?:販売受付期間|予約申込期間|受付期間)\s*([0-9]{4}年.+?[0-9]{1,2}:[0-9]{2})\s*[〜~～-]\s*([0-9]{4}年.+?[0-9]{1,2}:[0-9]{2})",
        r"([0-9]{4}年\s*\d{1,2}月\s*\d{1,2}日(?:\([^)]*\))?\s*\d{1,2}:\d{2})\s*[〜~～-]\s*([0-9]{4}年\s*\d{1,2}月\s*\d{1,2}日(?:\([^)]*\))?\s*\d{1,2}:\d{2})",
    ]
    for pattern in patterns:
        match = re.search(pattern, text, flags=re.DOTALL)
        if match:
            return parse_japanese_datetime(match.group(1)), parse_japanese_datetime(match.group(2))
    return None, None


def extract_tag_blocks_by_class(page_text: str, tag: str, class_fragment: str) -> List[str]:
    """提取指定 class 片段的完整 HTML 块，避免简单正则被内部标签截断。"""
    source = page_text or ""
    blocks: List[str] = []
    seen_starts = set()
    open_pattern = re.compile(rf"<{tag}\b[^>]*>", flags=re.IGNORECASE | re.DOTALL)
    tag_pattern = re.compile(rf"</?{tag}\b[^>]*>", flags=re.IGNORECASE | re.DOTALL)
    for open_match in open_pattern.finditer(source):
        open_tag = open_match.group(0)
        if class_fragment not in open_tag or open_match.start() in seen_starts:
            continue
        depth = 1
        end_pos = open_match.end()
        for tag_match in tag_pattern.finditer(source, open_match.end()):
            token = tag_match.group(0)
            if token.startswith("</"):
                depth -= 1
                if depth == 0:
                    end_pos = tag_match.end()
                    break
            else:
                depth += 1
        seen_starts.add(open_match.start())
        blocks.append(source[open_match.start():end_pos])
    return blocks


def extract_first_class_text(block: str, class_fragment: str, limit: int = 240) -> str:
    pattern = (
        r"<(?P<tag>[a-z0-9]+)\b[^>]*class=(['\"])[^'\"]*"
        + re.escape(class_fragment)
        + r"[^'\"]*\2[^>]*>(?P<body>.*?)</(?P=tag)>"
    )
    match = re.search(pattern, block or "", flags=re.IGNORECASE | re.DOTALL)
    return compact_text(visible_text(match.group("body")), limit) if match else ""


def extract_reception_title(block: str) -> str:
    return extract_first_class_text(block, "event-detail-ticket-head__title", 240)


def extract_sales_type(reception_title: str, block: str) -> str:
    text = f"{reception_title} {compact_text(visible_text(block), 240)}"
    for value in ("抽選", "先着", "通常", "販売"):
        if value in text:
            return value
    return ""


def ticket_session_id(ticket_id: str, ticket_field: str, label: str, reception_title: str, index: int) -> str:
    if ticket_id:
        return f"ticket:{ticket_id}"
    if ticket_field:
        return f"field:{ticket_field}"
    digest = hashlib.sha1(f"{reception_title}|{label}|{index}".encode("utf-8")).hexdigest()[:10]
    return f"card:{index}:{digest}"


def virtual_reception_id(index: int) -> str:
    return f"group:{index}"


def is_virtual_reception_id(reception_id: Any) -> bool:
    return str(reception_id or "").startswith("group:")


def reception_index_from_id(reception_id: Any) -> Optional[int]:
    raw = str(reception_id or "").strip()
    if not raw.startswith("group:"):
        return None
    try:
        return int(raw.split(":", 1)[1])
    except (TypeError, ValueError):
        return None


def parse_max_purchase_quantity_hint(*texts: Any) -> int:
    source = " ".join(compact_text(str(text or ""), 300) for text in texts if text)
    if not source:
        return 0
    patterns = [
        r"(?:お一人様|おひとり様|1人|１人|一人|一名)?\s*(\d+)\s*枚まで",
        r"最大\s*(\d+)\s*枚",
        r"上限\s*(\d+)\s*枚",
    ]
    for pattern in patterns:
        match = re.search(pattern, source)
        if match:
            try:
                quantity = int(match.group(1))
            except ValueError:
                continue
            if quantity > 0:
                return quantity
    return 0


def parse_lottery_sessions_from_cards(page_text: str) -> List[Dict[str, str]]:
    """按 LivePocket 公开页里的票卡解析可提交场次。"""
    sessions: List[Dict[str, str]] = []
    seen = set()
    reception_blocks = extract_tag_blocks_by_class(page_text, "li", "event-detail-ticket__item")
    if not reception_blocks:
        reception_blocks = [page_text or ""]
    page_reception_ids = extract_reception_ids(page_text)
    for reception_index, reception_block in enumerate(reception_blocks):
        reception_title = extract_reception_title(reception_block)
        sales_type = extract_sales_type(reception_title, reception_block)
        reception_id = extract_first_reception_id(reception_block)
        if not reception_id and reception_index < len(page_reception_ids):
            reception_id = page_reception_ids[reception_index]
        if not reception_id:
            reception_id = virtual_reception_id(reception_index)
        card_blocks = extract_tag_blocks_by_class(reception_block, "section", "event-detail-ticket-card")
        if not card_blocks:
            card_blocks = extract_tag_blocks_by_class(reception_block, "div", "event-detail-ticket-card__block")
        for card_block in card_blocks:
            label = extract_first_class_text(card_block, "event-detail-ticket-card__title", 240)
            if not label:
                continue
            ticket_match = re.search(r"\bev_(\d+)_", card_block)
            ticket_id = ticket_match.group(1) if ticket_match else ""
            ticket_field = f"tickets[{ticket_id}]" if ticket_id else ""
            notes = extract_first_class_text(card_block, "event-detail-ticket-card__text", 300)
            status_text = extract_first_class_text(card_block, "event-detail-ticket-card__status", 160)
            max_purchase_quantity = parse_max_purchase_quantity_hint(notes, status_text, label, reception_title)
            session_id = ticket_session_id(ticket_id, ticket_field, label, reception_title, len(sessions))
            key = session_id or f"{reception_index}:{label}"
            if key in seen:
                continue
            seen.add(key)
            sessions.append(
                {
                    "sessionId": session_id,
                    "sessionLabel": label,
                    "ticketId": ticket_id,
                    "ticketField": ticket_field,
                    "receptionId": reception_id,
                    "receptionTitle": reception_title,
                    "salesType": sales_type,
                    "notes": notes or status_text,
                    "maxPurchaseQuantity": max_purchase_quantity,
                }
            )
    return sessions


def parse_lottery_sessions(text: str, page_text: str = "") -> List[Dict[str, str]]:
    """解析活动可提交场次：优先按票卡/票种，兜底识别 `10:30入店`。"""
    card_sessions = parse_lottery_sessions_from_cards(page_text)
    if card_sessions:
        return card_sessions

    sessions: List[Dict[str, str]] = []
    seen = set()
    for match in re.finditer(r"(\d{1,2}:\d{2})\s*入店", text):
        session_id = match.group(1)
        if session_id in seen:
            continue
        seen.add(session_id)
        sessions.append(
            {
                "sessionId": session_id,
                "sessionLabel": f"{session_id}入店",
                "ticketId": "",
                "ticketField": "",
                "receptionTitle": "",
                "salesType": "",
                "notes": "",
            }
        )
    return sessions


def extract_event_title(page_text: str, text: str) -> str:
    og_match = re.search(r'<meta\s+property=["\']og:title["\']\s+content=["\']([^"\']+)["\']', page_text, re.I)
    if og_match:
        return html.unescape(og_match.group(1)).strip()
    h1_match = re.search(r"<h1\b[^>]*>(.*?)</h1>", page_text, flags=re.IGNORECASE | re.DOTALL)
    if h1_match:
        return compact_text(visible_text(h1_match.group(1)), 300)
    title_match = re.search(r"<title\b[^>]*>(.*?)</title>", page_text, flags=re.IGNORECASE | re.DOTALL)
    if title_match:
        return compact_text(html.unescape(title_match.group(1)), 300)
    return compact_text(text.splitlines()[0] if text else "", 300)


def extract_page_title(page_text: str) -> str:
    title_match = re.search(r"<title\b[^>]*>(.*?)</title>", page_text or "", flags=re.IGNORECASE | re.DOTALL)
    return compact_text(html.unescape(title_match.group(1)), 180) if title_match else ""


def browser_executable_path() -> Optional[str]:
    candidates = [
        os.environ.get("LIVEPOCKET_BROWSER_PATH", ""),
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    ]
    return next((path for path in candidates if path and os.path.exists(path)), None)


def browser_cookies_from_session(auth_session: Optional[requests.Session]) -> List[Dict[str, str]]:
    if not auth_session:
        return []
    cookies: List[Dict[str, str]] = []
    for cookie in auth_session.cookies:
        if not cookie.name or cookie.value is None:
            continue
        cookies.append({"name": cookie.name, "value": cookie.value, "url": LIVEPOCKET_BASE_URL})
    return cookies


def sync_session_cookies_from_browser(
    auth_session: Optional[requests.Session],
    browser_cookies: List[Dict[str, Any]],
) -> int:
    if not auth_session:
        return 0
    count = 0
    for cookie in browser_cookies:
        name = str(cookie.get("name") or "").strip()
        if not name:
            continue
        auth_session.cookies.set(
            name,
            str(cookie.get("value") or ""),
            domain=cookie.get("domain") or ".livepocket.jp",
            path=cookie.get("path") or "/",
        )
        count += 1
    return count


def session_proxy_from_requests_session(auth_session: Optional[requests.Session]) -> Any:
    if not auth_session:
        return None
    proxies = getattr(auth_session, "proxies", None) or {}
    proxy_url = str(proxies.get("https") or proxies.get("http") or "").strip()
    if not proxy_url:
        return None
    return parse_proxy_string(proxy_url)


def fetch_page_with_browser(
    page_url: str,
    proxy: Any = None,
    auth_session: Optional[requests.Session] = None,
    expect: str = "event",
    sync_cookies_to_session: bool = False,
) -> Tuple[str, str]:
    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:
        raise RuntimeError(f"Playwright 不可用: {exc}") from exc

    launch_options: Dict[str, Any] = {"headless": True}
    browser_path = browser_executable_path()
    if browser_path:
        launch_options["executable_path"] = browser_path
    if proxy and hasattr(proxy, "playwright_proxy"):
        launch_options["proxy"] = proxy.playwright_proxy()

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(**launch_options)
        try:
            context = browser.new_context(
                user_agent=DEFAULT_USER_AGENT,
                locale="ja-JP",
                extra_http_headers={"Accept-Language": "ja,en-US;q=0.9,en;q=0.8"},
            )
            auth_cookies = browser_cookies_from_session(auth_session)
            if auth_cookies:
                context.add_cookies(auth_cookies)
            page = context.new_page()
            page.goto(page_url, wait_until="domcontentloaded", timeout=60000)
            try:
                if expect == "tickets":
                    page.wait_for_function(
                        """() => {
                          const html = document.documentElement.innerHTML || '';
                          return html.includes('authenticity_token')
                            || !!document.querySelector('input[name="authenticity_token"]');
                        }""",
                        timeout=15000,
                    )
                else:
                    page.wait_for_function(
                        """() => {
                          const html = document.documentElement.innerHTML || '';
                          return /\\/receptions\\/[^\\/"'<>\\s]+\\/tickets/i.test(html)
                            || !!document.querySelector('a[href*="/receptions/"][href*="/tickets"]');
                        }""",
                        timeout=12000,
                    )
            except Exception:
                page.wait_for_timeout(4000)
            page_text = page.content()
            final_url = page.url
            if sync_cookies_to_session and auth_session:
                sync_session_cookies_from_browser(auth_session, context.cookies("https://livepocket.jp"))
            return page_text, final_url
        finally:
            browser.close()


def event_path_from_url(event_url: str) -> str:
    parsed = urlparse(event_url or "")
    match = re.match(r"^(/e/[^/]+)", parsed.path or "")
    return match.group(1) if match else ""


def build_tickets_url(event_url: str, reception_id: str) -> str:
    event_path = event_path_from_url(event_url)
    if not event_path or not reception_id:
        return ""
    return urljoin(LIVEPOCKET_BASE_URL, f"{event_path}/receptions/{reception_id}/tickets")


def extract_first_reception_id(page_text: str) -> str:
    ids = extract_reception_ids(page_text)
    return ids[0] if ids else ""


def extract_reception_ids(page_text: str) -> List[str]:
    seen = set()
    ids: List[str] = []
    patterns = [
        r"/e/[^/\"'<>\s]+/receptions/([^/\"'<>\s]+)(?:/tickets)?",
        r"/receptions/([^/\"'<>\s]+)(?:/tickets)?",
        r"\breception[_-]?id=(['\"]?)([A-Za-z0-9_-]+)\1",
        r"\bdata-(?:[A-Za-z0-9_-]+-)?reception[_-]?id=(['\"])([A-Za-z0-9_-]+)\1",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, page_text or "", flags=re.I):
            reception_id = match.group(2) if match.lastindex and match.lastindex >= 2 else match.group(1)
            if reception_id and reception_id not in seen:
                seen.add(reception_id)
                ids.append(reception_id)
    return ids


def extract_ticket_entry_candidates(page_text: str, event_url: str = "") -> List[Dict[str, str]]:
    candidates: List[Dict[str, str]] = []
    seen = set()

    def add_candidate(raw_url: str, text: str) -> None:
        url = urljoin(LIVEPOCKET_BASE_URL, html.unescape(raw_url))
        if not looks_like_tickets_url(url) or url in seen:
            return
        seen.add(url)
        candidates.append({"url": url, "text": compact_text(text, 120)})

    for match in re.finditer(r"<a\b[^>]*\bhref=(['\"])(.*?)\1[^>]*>", page_text or "", flags=re.I | re.S):
        tag = match.group(0)
        add_candidate(match.group(2), visible_text(tag))

    for match in re.finditer(
        r"(?:https?://livepocket\.jp)?/e/[^/\"'<>\s]+/receptions/[^/\"'<>\s]+/tickets(?:\?[^\"'<>\s]*)?",
        page_text or "",
        flags=re.I,
    ):
        add_candidate(match.group(0), "raw html url")
    for reception_id in extract_reception_ids(page_text):
        derived_url = build_tickets_url(event_url, reception_id)
        if derived_url:
            add_candidate(derived_url, "derived reception url")
    return candidates


def extract_event_ticket_cards(page_text: str) -> List[Dict[str, str]]:
    cards: List[Dict[str, str]] = []
    seen = set()
    for item in parse_lottery_sessions_from_cards(page_text):
        label = item.get("sessionLabel", "")
        ticket_id = item.get("ticketId", "")
        key = f"{item.get('sessionId')}:{label}"
        if key in seen:
            continue
        seen.add(key)
        cards.append(
            {
                "ticketId": ticket_id,
                "ticketField": item.get("ticketField", ""),
                "receptionId": item.get("receptionId", ""),
                "label": label,
                "receptionTitle": item.get("receptionTitle", ""),
                "salesType": item.get("salesType", ""),
                "notes": item.get("notes", ""),
                "maxPurchaseQuantity": item.get("maxPurchaseQuantity", 0),
            }
        )
    return cards


def extract_collection_event_sessions(page_text: str, collection_url: str) -> List[Dict[str, str]]:
    sessions: List[Dict[str, str]] = []
    seen = set()
    pattern = (
        r"<a\b(?=[^>]*\bevent-card-schedule\b)[^>]*\bhref=(['\"])(?P<href>.*?)\1[^>]*>"
        r"(?P<body>.*?)</a>"
    )
    for index, match in enumerate(re.finditer(pattern, page_text or "", flags=re.IGNORECASE | re.DOTALL)):
        event_url = event_detail_url_from_url(urljoin(collection_url, html.unescape(match.group("href"))))
        if not re.match(r"^https://livepocket\.jp/e/[^/?#]+$", event_url):
            continue
        if event_url in seen:
            continue
        seen.add(event_url)
        body = match.group("body")
        title = extract_first_class_text(body, "event-card-schedule__title", 260) or compact_text(visible_text(body), 260)
        status = extract_first_class_text(body, "event-card-schedule__tag", 80)
        place = extract_first_class_text(body, "event-card-schedule__text", 160)
        event_code = urlparse(event_url).path.rsplit("/", 1)[-1]
        sessions.append(
            {
                "sessionId": f"event:{event_code}",
                "sessionLabel": title or event_url,
                "eventUrl": event_url,
                "ticketId": "",
                "ticketField": "",
                "receptionId": "",
                "receptionTitle": status or "LivePocket 活动",
                "salesType": "抽選",
                "notes": " / ".join([part for part in [status, place, event_url] if part]),
            }
        )
    return sessions


def looks_like_tickets_url(url: str) -> bool:
    parsed = urlparse(url or "")
    return bool(re.search(r"/receptions/[^/]+/tickets/?$", parsed.path or ""))


def load_event_cache() -> None:
    global EVENT_INFO_CACHE
    if EVENT_INFO_CACHE or not CACHE_FILE.exists():
        return
    try:
        EVENT_INFO_CACHE = json.loads(CACHE_FILE.read_text(encoding="utf-8"))
    except Exception:
        EVENT_INFO_CACHE = {}


def save_event_cache() -> None:
    try:
        CACHE_DIR.mkdir(parents=True, exist_ok=True)
        CACHE_FILE.write_text(json.dumps(EVENT_INFO_CACHE, ensure_ascii=False, indent=2), encoding="utf-8")
    except Exception:
        pass


def cache_has_usable_ticket_entry(cached: Dict[str, Any]) -> bool:
    ticket_entry_url = str(cached.get("ticketEntryUrl") or "")
    if looks_like_tickets_url(ticket_entry_url):
        return True
    sessions = cached.get("sessions") or []
    if not isinstance(sessions, list):
        return False
    return any(str((item or {}).get("receptionId") or "").strip() for item in sessions if isinstance(item, dict))


def collection_url_from_url(url: str) -> str:
    raw = html.unescape(str(url or "")).strip().strip("'\"")
    raw = raw.replace("　", "").replace("\r", "").replace("\n", "")
    if not raw:
        return ""
    if raw.startswith("//"):
        raw = "https:" + raw
    if not re.match(r"^https?://", raw, flags=re.IGNORECASE):
        raw = "https://" + raw.lstrip("/")
    parsed = urlparse(raw)
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    match = re.match(r"^(/t/[^/?#]+)", parsed.path or "")
    if match and host in {"livepocket.jp", "t.livepocket.jp"}:
        return f"https://livepocket.jp{match.group(1)}"
    return ""


def fetch_lottery_collection_event_info(collection_url: str) -> Dict[str, Any]:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
    }
    log(f"[lottery-event-info] GET event collection: {collection_url}")
    last_error = ""
    response = None
    for attempt in range(1, proxy_max_attempts() + 1):
        proxy_key = f"event-collection:{collection_url}"
        proxy = get_proxy(proxy_key, refresh=attempt > 1)
        session = requests.Session()
        apply_proxy_to_session(session, proxy)
        try:
            response = session.get(collection_url, headers=headers, timeout=30)
        except requests.RequestException as exc:
            last_error = str(exc)
            mark_proxy_failed(proxy, reason=f"活动集合页请求异常: {exc}", key=proxy_key)
            if attempt < proxy_max_attempts():
                log(f"[lottery-event-info] collection proxy retry {attempt}/{proxy_max_attempts()}: {exc}")
                continue
            raise ValueError(f"LivePocket 活动集合页访问异常: {exc}") from exc
        if is_blocked_livepocket_response(response):
            last_error = f"status={response.status_code}, finalUrl={response.url}"
            mark_proxy_failed(proxy, reason=f"活动集合页代理访问失败: {last_error}", key=proxy_key)
            if attempt < proxy_max_attempts():
                log(f"[lottery-event-info] collection proxy blocked, retry {attempt + 1}/{proxy_max_attempts()}: {last_error}")
                continue
        break
    if response is None:
        raise ValueError(last_error or "LivePocket 活动集合页访问失败")

    page_text = response.text or ""
    text = visible_text(page_text)
    sessions = extract_collection_event_sessions(page_text, collection_url)
    if not sessions:
        raise ValueError("活动集合页未找到可选择的 LivePocket 活动")
    return {
        "eventUrl": collection_url,
        "ticketEntryUrl": "",
        "ticketEntryCandidates": [],
        "ticketCards": [],
        "eventTitle": extract_event_title(page_text, text),
        "entryStartTime": "",
        "entryEndTime": "",
        "sessions": sessions,
        "rawSummary": compact_text(text, 1600),
        "cacheHit": False,
    }


def fetch_lottery_event_info(event_url: str, auth_session: Optional[requests.Session] = None) -> Dict[str, Any]:
    """无登录加载 LivePocket 公开活动页，解析抽选时间和场次。"""
    raw_event_url = event_url
    event_url = event_detail_url_from_url(event_url)
    if not event_url or not re.match(r"^https://livepocket\.jp/e/[^/?#]+$", event_url):
        collection_url = collection_url_from_url(raw_event_url)
        if collection_url:
            return fetch_lottery_collection_event_info(collection_url)
        raise ValueError("只支持 https://livepocket.jp/e/... 或 https://livepocket.jp/t/... 活动链接")

    load_event_cache()
    cached = EVENT_INFO_CACHE.get(event_url)
    now = time.time()
    if (
        auth_session is None
        and
        cached
        and int(cached.get("_schemaVersion", 0) or 0) == CACHE_SCHEMA_VERSION
        and now - float(cached.get("_cachedAt", 0)) < CACHE_TTL_SECONDS
        and cache_has_usable_ticket_entry(cached)
    ):
        result = dict(cached)
        result.pop("_cachedAt", None)
        result.pop("_schemaVersion", None)
        result.setdefault("ticketEntryUrl", "")
        result.setdefault("ticketEntryCandidates", [])
        result.setdefault("ticketCards", [])
        result["cacheHit"] = True
        return result

    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
    }
    log(f"[lottery-event-info] GET event detail: {event_url}")
    last_error = ""
    response = None
    blocked = False
    for attempt in range(1, proxy_max_attempts() + 1):
        proxy_key = f"event-info:{event_url}"
        proxy = get_proxy(proxy_key, refresh=attempt > 1)
        session = requests.Session()
        apply_proxy_to_session(session, proxy)
        try:
            response = session.get(event_url, headers=headers, timeout=30)
        except requests.RequestException as exc:
            last_error = str(exc)
            mark_proxy_failed(proxy, reason=f"活动解析请求异常: {exc}", key=proxy_key)
            if attempt < proxy_max_attempts():
                log(f"[lottery-event-info] proxy retry {attempt}/{proxy_max_attempts()}: {exc}")
                continue
            raise ValueError(f"LivePocket 页面访问异常: {exc}") from exc
        blocked = is_blocked_livepocket_response(response)
        if blocked:
            last_error = f"status={response.status_code}, finalUrl={response.url}"
            mark_proxy_failed(proxy, reason=f"活动解析代理访问失败: {last_error}", key=proxy_key)
            if attempt < proxy_max_attempts():
                log(f"[lottery-event-info] proxy blocked, retry {attempt}/{proxy_max_attempts()}: {last_error}")
                continue
        break
    if response is None:
        raise ValueError(f"LivePocket 页面访问失败: {last_error or 'unknown'}")
    if blocked:
        raise ValueError(f"LivePocket 页面被拦截: {last_error or response.url}")
    if response.status_code >= 400:
        raise ValueError(f"LivePocket 页面访问失败: HTTP {response.status_code}")
    page_text = response.text or ""
    text = visible_text(page_text)
    entry_start, entry_end = parse_entry_period(text)
    sessions = parse_lottery_sessions(text, page_text)
    ticket_entry_candidates = extract_ticket_entry_candidates(page_text, event_url)
    ticket_cards = extract_event_ticket_cards(page_text)
    if not ticket_entry_candidates and not any((item or {}).get("receptionId") for item in ticket_cards):
        try:
            browser_page_text, browser_final_url = fetch_page_with_browser(
                event_url,
                proxy,
                auth_session=auth_session,
                expect="event",
                sync_cookies_to_session=False,
            )
            browser_sessions = parse_lottery_sessions(visible_text(browser_page_text), browser_page_text)
            browser_ticket_entry_candidates = extract_ticket_entry_candidates(browser_page_text, event_url)
            browser_ticket_cards = extract_event_ticket_cards(browser_page_text)
            if browser_ticket_entry_candidates or any((item or {}).get("receptionId") for item in browser_ticket_cards):
                page_text = browser_page_text
                text = visible_text(page_text)
                sessions = browser_sessions
                ticket_entry_candidates = browser_ticket_entry_candidates
                ticket_cards = browser_ticket_cards
                log(
                    "[lottery-event-info] BROWSER fallback "
                    f"finalUrl={browser_final_url}, sessions={len(sessions)}, "
                    f"ticketEntryCandidates={len(ticket_entry_candidates)}, ticketCards={len(ticket_cards)}"
                )
        except Exception as exc:
            log(f"[lottery-event-info] BROWSER fallback failed: {exc}")
    log(
        "[lottery-event-info] EVENT response "
        f"status={response.status_code}, finalUrl={response.url}, bytes={len(page_text)}, "
        f"title={extract_page_title(page_text) or '-'}, sessions={len(sessions)}, "
        f"ticketEntryCandidates={len(ticket_entry_candidates)}, ticketCards={len(ticket_cards)}"
    )
    if ticket_entry_candidates:
        log(f"[lottery-event-info] EVENT ticket candidates: {ticket_entry_candidates[:5]}")
    elif ticket_cards:
        log(f"[lottery-event-info] EVENT ticket cards: {ticket_cards[:10]}")
    sessions = enrich_sessions_with_ticket_page_limits(
        sessions=sessions,
        event_url=event_url,
        ticket_entry_candidates=ticket_entry_candidates,
        request_session=session,
        referer_url=response.url or event_url,
        current_page_text=page_text,
    )
    ticket_cards = extract_event_ticket_cards(page_text)
    session_limit_map = {
        str(item.get("sessionId") or ""): int(item.get("maxPurchaseQuantity") or 0)
        for item in sessions
        if isinstance(item, dict)
    }
    for card in ticket_cards:
        session_id = ticket_session_id(
            str(card.get("ticketId") or ""),
            str(card.get("ticketField") or ""),
            str(card.get("label") or ""),
            str(card.get("receptionTitle") or ""),
            0,
        )
        if session_id in session_limit_map:
            card["maxPurchaseQuantity"] = session_limit_map[session_id]
    result = {
        "eventUrl": event_url,
        "ticketEntryUrl": ticket_entry_candidates[0]["url"] if ticket_entry_candidates else "",
        "ticketEntryCandidates": ticket_entry_candidates,
        "ticketCards": ticket_cards,
        "eventTitle": extract_event_title(page_text, text),
        "entryStartTime": entry_start,
        "entryEndTime": entry_end,
        "sessions": sessions,
        "rawSummary": compact_text(text, 1600),
        "cacheHit": False,
    }
    if auth_session is None or cache_has_usable_ticket_entry(result):
        EVENT_INFO_CACHE[event_url] = {**result, "_cachedAt": now, "_schemaVersion": CACHE_SCHEMA_VERSION}
        save_event_cache()
    return result


def parse_attrs(tag: str) -> Dict[str, str]:
    attrs: Dict[str, str] = {}
    for match in re.finditer(r"([\w:-]+)\s*=\s*(['\"])(.*?)\2", tag or "", flags=re.DOTALL):
        attrs[match.group(1).lower()] = html.unescape(match.group(3))
    return attrs


def strip_noisy_attrs(page_text: str) -> str:
    text = re.sub(r"\sstyle=(['\"]).*?\1", "", page_text or "", flags=re.IGNORECASE | re.DOTALL)
    text = re.sub(r"\sdata-snapshot-node=(['\"]).*?\1", "", text, flags=re.IGNORECASE | re.DOTALL)
    return re.sub(r"\s+", " ", text)


def event_detail_url_from_url(url: str) -> str:
    raw = html.unescape(str(url or "")).strip().strip("'\"")
    raw = raw.replace("　", "").replace("\r", "").replace("\n", "")
    if not raw:
        return ""
    if raw.startswith("//"):
        raw = "https:" + raw
    if not re.match(r"^https?://", raw, flags=re.IGNORECASE):
        raw = "https://" + raw.lstrip("/")
    parsed = urlparse(raw)
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    match = re.match(r"^(/e/[^/?#]+)", parsed.path or "")
    if match and host in {"livepocket.jp", "t.livepocket.jp"}:
        return f"https://livepocket.jp{match.group(1)}"
    text_match = re.search(r"(?:https?://)?(?:www\.|t\.)?livepocket\.jp(/e/[^/?#\s]+)", raw, flags=re.IGNORECASE)
    if text_match:
        return f"https://livepocket.jp{text_match.group(1)}"
    return raw


def extract_ticket_entry_url(event_html: str, fallback_url: str, event_url: str = "") -> str:
    submit_order_url = submit_order_extract_ticket_entry_url(event_html, fallback_url)
    if looks_like_tickets_url(submit_order_url):
        return submit_order_url
    candidates = extract_ticket_entry_candidates(event_html, event_url or fallback_url)
    if not candidates:
        return fallback_url
    purchase_words = ("チケットを購入", "購入", "申込", "予約")
    for item in candidates:
        if any(word in item.get("text", "") for word in purchase_words):
            return item["url"]
    return candidates[0]["url"]


def select_seat_url_from_tickets_url(tickets_url: str) -> str:
    parsed = urlparse(tickets_url)
    if parsed.path.endswith("/tickets"):
        path = parsed.path[: -len("/tickets")] + "/select_seat"
        return parsed._replace(path=path, query="", fragment="").geturl()
    return urljoin(tickets_url, "select_seat")


def extract_select_seat_url(tickets_html: str, tickets_url: str) -> str:
    match = re.search(r"<form\b[^>]*\baction=(['\"])(.*?)\1", tickets_html or "", flags=re.I | re.S)
    if match and "select_seat" in match.group(2):
        return urljoin(LIVEPOCKET_BASE_URL, html.unescape(match.group(2)))
    return select_seat_url_from_tickets_url(tickets_url)


def request_headers(
    accept: str = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    referer: str = LIVEPOCKET_BASE_URL + "/",
    turbo: bool = False,
) -> Dict[str, str]:
    headers = {
        "Accept": accept,
        "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
        "Origin": LIVEPOCKET_BASE_URL,
        "Referer": referer,
    }
    if turbo:
        headers.update(
            {
                "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
                "X-Turbo-Request-Id": str(uuid.uuid4()),
                "Sec-Fetch-Dest": "empty",
                "Sec-Fetch-Mode": "cors",
                "Sec-Fetch-Site": "same-origin",
            }
        )
    else:
        headers.update(
            {
                "Sec-Fetch-Dest": "document",
                "Sec-Fetch-Mode": "navigate",
                "Sec-Fetch-Site": "same-origin",
                "Sec-Fetch-User": "?1",
                "Upgrade-Insecure-Requests": "1",
            }
        )
    return headers


def parse_lottery_session_options(page_text: str) -> List[Dict[str, Any]]:
    clean = strip_noisy_attrs(page_text)
    options: List[Dict[str, Any]] = []
    for match in re.finditer(r"<select\b.*?</select>", clean, flags=re.IGNORECASE | re.DOTALL):
        block = match.group(0)
        open_tag_match = re.search(r"<select\b[^>]*>", block, flags=re.IGNORECASE | re.DOTALL)
        if not open_tag_match:
            continue
        attrs = parse_attrs(open_tag_match.group(0))
        field_name = attrs.get("name", "")
        ticket_match = re.search(r"tickets\[(\d+)\]", field_name)
        if not ticket_match:
            continue
        values: List[str] = []
        for option_match in re.finditer(r"<option\b[^>]*>(.*?)</option>", block, flags=re.IGNORECASE | re.DOTALL):
            option_attrs = parse_attrs(option_match.group(0))
            value = option_attrs.get("value", compact_text(visible_text(option_match.group(1)), 20))
            if value not in values:
                values.append(value)
        options.append(
            {
                "index": len(options),
                "fieldName": field_name,
                "ticketId": ticket_match.group(1),
                "label": find_lottery_session_label(clean, match.start()),
                "disabled": bool(re.search(r"\bdisabled\b", open_tag_match.group(0), flags=re.IGNORECASE)),
                "values": values,
            }
        )
    return options


def find_lottery_session_label(clean_html: str, select_start: int) -> str:
    window = clean_html[max(0, select_start - 2500):select_start]
    title_matches = list(
        re.finditer(
            r"<[^>]*class=(['\"])[^'\"]*ticket-card__title[^'\"]*\1[^>]*>(.*?)</[^>]+>",
            window,
            flags=re.IGNORECASE | re.DOTALL,
        )
    )
    if title_matches:
        return compact_text(visible_text(title_matches[-1].group(2)), 160)
    text = visible_text(window)
    time_matches = re.findall(r"\d{1,2}:\d{2}\s*入店(?:\([^)]*\))?", text)
    return time_matches[-1] if time_matches else ""


def normalize_session_value(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "")).strip()


def is_truthy(value: Any) -> bool:
    if isinstance(value, str):
        return value.strip().lower() in {"1", "true", "yes", "y", "on"}
    return bool(value)


def choose_lottery_session(
    options: List[Dict[str, Any]],
    task_options: Dict[str, Any],
    session_id: Any,
    session_label: Any,
) -> tuple[Optional[Dict[str, Any]], str]:
    if not options:
        return None, "页面没有解析到 tickets[...] 时间段 select"

    explicit_field = task_options.get("ticketField") or task_options.get("sessionTicketField")
    explicit_ticket_id = task_options.get("ticketId") or task_options.get("sessionTicketId")
    if explicit_field:
        matched = [item for item in options if item.get("fieldName") == str(explicit_field)]
        if matched:
            return matched[0], "ticketField"
    if explicit_ticket_id:
        matched = [item for item in options if item.get("ticketId") == str(explicit_ticket_id)]
        if matched:
            return matched[0], "ticketId"

    needles = [normalize_session_value(item) for item in [session_label, session_id, task_options.get("sessionLabel"), task_options.get("sessionId")] if item]
    for needle in needles:
        label_matches = [
            item for item in options
            if needle and needle in normalize_session_value(item.get("label"))
        ]
        if label_matches:
            enabled_matches = [item for item in label_matches if not item.get("disabled")]
            return (enabled_matches[0] if enabled_matches else label_matches[0]), "sessionLabel"

    enabled = [item for item in options if not item.get("disabled")]
    if len(enabled) == 1:
        return enabled[0], "singleAvailable"
    if len(options) == 1:
        return options[0], "singleOption"
    return None, "多个可用时间段但无法根据 sessionId/sessionLabel 匹配"


def parse_quantity_option_value(value: Any) -> int:
    match = re.search(r"\d+", str(value or ""))
    if not match:
        return 0
    try:
        return int(match.group(0))
    except ValueError:
        return 0


def max_purchase_quantity_from_option(option: Dict[str, Any]) -> int:
    values = option.get("values") if isinstance(option, dict) else []
    quantities = [parse_quantity_option_value(item) for item in values or []]
    quantities = [item for item in quantities if item > 0]
    return max(quantities) if quantities else 0


def find_matching_session_option(item: Dict[str, Any], option_list: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    for option in option_list:
        if item.get("ticketField") and str(option.get("fieldName") or "") == str(item.get("ticketField") or ""):
            return option
        if item.get("ticketId") and str(option.get("ticketId") or "") == str(item.get("ticketId") or ""):
            return option
    label_key = normalize_session_label_key(item.get("sessionLabel"))
    if label_key:
        for option in option_list:
            if normalize_session_label_key(option.get("label")) == label_key:
                return option
    return None


def resolve_livepocket_entry_quantity(
    purchase_type: str,
    quantity_mode: str,
    selected_session: Dict[str, Any],
    task_options: Dict[str, Any],
    payload: Dict[str, Any],
) -> str:
    normalized_purchase_type = str(purchase_type or "").strip() or "lottery"
    normalized_quantity_mode = str(quantity_mode or "").strip().lower()
    positive_quantities = sorted({
        parse_quantity_option_value(item)
        for item in (selected_session.get("values") or [])
        if parse_quantity_option_value(item) > 0
    })
    if normalized_purchase_type == "flash_sale" and normalized_quantity_mode == "auto_max":
        if not positive_quantities:
            raise ValueError("目标票种当前没有可用数量")
        return str(positive_quantities[-1])

    quantity = str(task_options.get("entryQuantity") or payload.get("purchaseQuantity") or 1)
    if quantity not in [str(item) for item in selected_session.get("values") or []]:
        raise ValueError(f"目标时间段不支持数量 {quantity}")
    return quantity


def normalize_session_label_key(value: Any) -> str:
    return re.sub(r"\s+", "", str(value or "")).strip().lower()


def enrich_sessions_with_ticket_page_limits(
    sessions: List[Dict[str, Any]],
    event_url: str,
    ticket_entry_candidates: List[Dict[str, str]],
    request_session: requests.Session,
    referer_url: str,
    current_page_text: str = "",
) -> List[Dict[str, Any]]:
    if not sessions or request_session is None:
        return sessions

    current_page_options = parse_lottery_session_options(current_page_text or "")
    if current_page_options:
        for item in sessions:
            matched_option = find_matching_session_option(item, current_page_options)
            option_max = max_purchase_quantity_from_option(matched_option or {})
            if option_max > 0:
                item["maxPurchaseQuantity"] = option_max

    candidate_by_reception: Dict[str, str] = {}
    for candidate in ticket_entry_candidates or []:
        candidate_url = str((candidate or {}).get("url") or "")
        reception_id = extract_first_reception_id(candidate_url)
        if reception_id and looks_like_tickets_url(candidate_url):
            candidate_by_reception[reception_id] = candidate_url

    reception_urls: Dict[str, str] = {}
    for item in sessions:
        reception_id = str(item.get("receptionId") or "").strip()
        if not reception_id or reception_id in reception_urls:
            continue
        ticket_entry_url = ""
        if is_virtual_reception_id(reception_id):
            reception_index = reception_index_from_id(reception_id)
            if reception_index is not None and 0 <= reception_index < len(ticket_entry_candidates or []):
                ticket_entry_url = str((ticket_entry_candidates[reception_index] or {}).get("url") or "")
        else:
            ticket_entry_url = candidate_by_reception.get(reception_id) or build_tickets_url(event_url, reception_id)
        if looks_like_tickets_url(ticket_entry_url):
            reception_urls[reception_id] = ticket_entry_url

    ticket_option_cache: Dict[str, List[Dict[str, Any]]] = {}
    for reception_id, ticket_entry_url in reception_urls.items():
        try:
            response = request_session.get(
                ticket_entry_url,
                headers=request_headers(
                    accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
                    referer=referer_url or event_url,
                ),
                allow_redirects=True,
                timeout=30,
            )
        except Exception as exc:
            log(f"[lottery-event-info] ticket page limit fetch failed receptionId={reception_id}: {exc}")
            continue
        if response.status_code >= 400:
            log(
                "[lottery-event-info] ticket page limit fetch failed "
                f"receptionId={reception_id}, status={response.status_code}, url={ticket_entry_url}"
            )
            continue
        ticket_option_cache[reception_id] = parse_lottery_session_options(response.text or "")

    for item in sessions:
        reception_id = str(item.get("receptionId") or "").strip()
        current_max = int(item.get("maxPurchaseQuantity") or 0)
        option_list = ticket_option_cache.get(reception_id) or []
        matched_option = find_matching_session_option(item, option_list)
        option_max = max_purchase_quantity_from_option(matched_option or {})
        if option_max > 0:
            item["maxPurchaseQuantity"] = option_max
        elif current_max <= 0:
            item["maxPurchaseQuantity"] = 0
    return sessions


def parse_confirm_params_from_url(url: str) -> tuple[Optional[str], Optional[str]]:
    query = parse_qs(urlparse(url or "").query)
    return (query.get("id") or [None])[0], (query.get("reserve_id") or [None])[0]


def parse_confirm_url_from_html(page_text: str) -> Optional[str]:
    for pattern in [
        r"['\"]([^'\"]*/purchase/confirm\?[^'\"]+)['\"]",
        r"href=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
        r"action=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
    ]:
        match = re.search(pattern, page_text or "", flags=re.IGNORECASE)
        if match:
            return urljoin(LIVEPOCKET_BASE_URL, html.unescape(match.group(1)))
    return None


def parse_order_id(text_or_url: str) -> Optional[str]:
    if not text_or_url:
        return None
    query = parse_qs(urlparse(text_or_url).query)
    order_id = (query.get("order_id") or [None])[0]
    if order_id:
        return order_id
    match = re.search(r"order_id[=/](\d+)|order_id=(\d+)", text_or_url)
    if match:
        return next(item for item in match.groups() if item)
    match = re.search(r"/purchase/complete/(\d+)", text_or_url)
    return match.group(1) if match else None


def extract_livepocket_errors(page_text: str) -> List[str]:
    errors: List[str] = []
    patterns = [
        r"<p[^>]*class=['\"][^'\"]*form-error-text[^'\"]*['\"][^>]*>(.*?)</p>",
        r"<div[^>]*class=['\"][^'\"]*message--error[^'\"]*['\"][^>]*>(.*?)</div>",
        r"<li[^>]*class=['\"][^'\"]*error[^'\"]*['\"][^>]*>(.*?)</li>",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, page_text or "", flags=re.IGNORECASE | re.DOTALL):
            message = compact_text(visible_text(match.group(1)), 500)
            if message and message not in errors:
                errors.append(message)
    return errors


def extract_lottery_already_applied_message(page_text: str) -> str:
    source = page_text or ""
    alert_pattern = (
        r"<p\b[^>]*class=(['\"])[^'\"]*event-detail-ticket-alert[^'\"]*\1[^>]*>"
        r"(?P<body>.*?)</p>"
    )
    for match in re.finditer(alert_pattern, source, flags=re.IGNORECASE | re.DOTALL):
        message = compact_text(visible_text(match.group("body")), 300)
        if "申込済み" in message:
            return message

    button_pattern = (
        r"<a\b[^>]*class=(['\"])[^'\"]*event-detail-ticket-button[^'\"]*\1"
        r"[^>]*aria-disabled=(['\"])true\2[^>]*>(?P<body>.*?)</a>"
    )
    for match in re.finditer(button_pattern, source, flags=re.IGNORECASE | re.DOTALL):
        label = compact_text(visible_text(match.group("body")), 120)
        if "申込済み" in label:
            return "この受付は申込済みです。"

    text = visible_text(source)
    if "この受付は申込済み" in text:
        return "この受付は申込済みです。"
    return ""


def select_event_ticket_card(
    cards: List[Dict[str, str]],
    ticket_id: Any = None,
    ticket_field: Any = None,
    session_id: Any = None,
) -> Dict[str, str]:
    for item in cards or []:
        if ticket_id and str(item.get("ticketId") or "") == str(ticket_id):
            return item
        if ticket_field and str(item.get("ticketField") or "") == str(ticket_field):
            return item
        if session_id and str(item.get("sessionId") or "") == str(session_id):
            return item
    return (cards or [{}])[0] if cards else {}


def extract_radio_options(page_text: str, input_name: str) -> List[Dict[str, Any]]:
    options: List[Dict[str, Any]] = []
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.IGNORECASE | re.DOTALL):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        if attrs.get("type", "").lower() != "radio" or attrs.get("name") != input_name:
            continue
        label_start = (page_text or "").rfind("<label", 0, match.start())
        label_end = (page_text or "").find("</label>", match.end())
        if label_start >= 0 and label_end >= match.end():
            label_html = (page_text or "")[label_start: label_end + len("</label>")]
        else:
            label_html = (page_text or "")[match.start(): match.end() + 240]
        options.append({
            "name": attrs.get("name", ""),
            "value": attrs.get("value", ""),
            "checked": bool(re.search(r"\bchecked\b", tag, flags=re.IGNORECASE)),
            "label": compact_text(visible_text(label_html), 180),
        })
    return options


def select_has_option(page_text: str, select_name: str, option_value: str) -> bool:
    for match in re.finditer(r"<select\b[^>]*>(?P<body>.*?)</select>", page_text or "", flags=re.IGNORECASE | re.DOTALL):
        attrs = parse_attrs(match.group(0))
        if attrs.get("name") != select_name:
            continue
        for option_match in re.finditer(r"<option\b[^>]*>", match.group("body"), flags=re.IGNORECASE | re.DOTALL):
            option_attrs = parse_attrs(option_match.group(0))
            if option_attrs.get("value") == option_value:
                return True
    return False


def apply_text_questionnaire_answers(form_data: Dict[str, str], confirm_text: str, default_answer: str = "ない") -> int:
    applied = 0
    allowed_input_types = {"", "text", "search", "email", "tel", "url", "number"}
    for match in re.finditer(r"<textarea\b[^>]*>(.*?)</textarea>", confirm_text or "", flags=re.IGNORECASE | re.DOTALL):
        attrs = parse_attrs(match.group(0))
        name = attrs.get("name", "")
        if not name.startswith("questionnaire_answers["):
            continue
        existing_value = html.unescape(match.group(1) or "").strip()
        current_value = str(form_data.get(name) or "").strip()
        if current_value:
            continue
        form_data[name] = existing_value or default_answer
        if not existing_value:
            applied += 1

    for match in re.finditer(r"<input\b[^>]*>", confirm_text or "", flags=re.IGNORECASE | re.DOTALL):
        attrs = parse_attrs(match.group(0))
        name = attrs.get("name", "")
        input_type = attrs.get("type", "").lower()
        if not name.startswith("questionnaire_answers[") or input_type not in allowed_input_types:
            continue
        current_value = str(form_data.get(name) or "").strip()
        if current_value:
            continue
        existing_value = str(attrs.get("value", "")).strip()
        form_data[name] = existing_value or default_answer
        if not existing_value:
            applied += 1
    return applied


def apply_adult_questionnaire_answers(form_data: Dict[str, str], confirm_text: str) -> int:
    grouped: Dict[str, List[Dict[str, Any]]] = {}
    for match in re.finditer(r"<input\b[^>]*>", confirm_text or "", flags=re.IGNORECASE | re.DOTALL):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        name = attrs.get("name", "")
        if attrs.get("type", "").lower() != "radio" or not name.startswith("questionnaire_answers["):
            continue
        label_start = (confirm_text or "").rfind("<label", 0, match.start())
        label_end = (confirm_text or "").find("</label>", match.end())
        if label_start >= 0 and label_end >= match.end():
            label_html = (confirm_text or "")[label_start: label_end + len("</label>")]
        else:
            label_html = (confirm_text or "")[match.start(): match.end() + 240]
        grouped.setdefault(name, []).append({
            "value": attrs.get("value", ""),
            "label": compact_text(visible_text(label_html), 180),
            "checked": bool(re.search(r"\bchecked\b", tag, flags=re.IGNORECASE)),
        })

    applied = 0
    for name, options in grouped.items():
        if form_data.get(name):
            continue
        adult = next(
            (
                item for item in options
                if ("成人済" in item.get("label", "") or "成人" in item.get("label", ""))
                and "未成年" not in item.get("label", "")
            ),
            None,
        )
        if adult and adult.get("value"):
            form_data[name] = str(adult["value"])
            applied += 1
            continue
        checked = next((item for item in options if item.get("checked") and item.get("value")), None)
        if checked:
            form_data[name] = str(checked["value"])
            applied += 1
    return applied


def build_lottery_purchase_form(
    confirm_text: str,
    event_id: str,
    reserve_id: str,
    purchase_type: str = "lottery",
) -> Tuple[Dict[str, str], Dict[str, Any]]:
    form_data = extract_hidden_inputs(confirm_text)
    authenticity_token = extract_input_value(confirm_text, "authenticity_token")
    if not authenticity_token:
        raise ValueError("确认页缺少 authenticity_token")
    form_data["authenticity_token"] = authenticity_token
    form_data["id"] = form_data.get("id") or event_id
    form_data["order_form[reserve_id]"] = form_data.get("order_form[reserve_id]") or reserve_id
    form_data["order_form[event_id]"] = form_data.get("order_form[event_id]") or event_id

    payment_options = extract_radio_options(confirm_text, "order_form[payment_method]")
    if payment_options:
        payment_values = {str(item.get("value") or "") for item in payment_options}
        if "cvs" not in payment_values:
            raise ValueError("付费抽选确认页未提供便利店支付选项，无法自动提交")
        form_data["order_form[payment_method]"] = "cvs"
        if not select_has_option(confirm_text, "order_form[sbps_web_cvs_type]", "002"):
            raise ValueError("付费抽选确认页未提供罗森便利店支付选项")
        form_data["order_form[sbps_web_cvs_type]"] = "002"
        payment_status = "offline_pending" if purchase_type == "flash_sale" else "lottery_waiting_result"
    else:
        form_data["order_form[payment_method]"] = form_data.get("order_form[payment_method]") or "free"
        payment_status = "not_required"

    questionnaire_count = apply_adult_questionnaire_answers(form_data, confirm_text)
    questionnaire_text_count = apply_text_questionnaire_answers(form_data, confirm_text)
    form_data["order_form[follow_notification]"] = form_data.get("order_form[follow_notification]") or "1"
    form_data["order_form[purchase_agreement_content]"] = form_data.get("order_form[purchase_agreement_content]") or "1"
    payment_method = form_data.get("order_form[payment_method]", "free")
    return form_data, {
        "paymentMethod": payment_method,
        "cvsType": form_data.get("order_form[sbps_web_cvs_type]", "") if payment_method == "cvs" else "",
        "paymentStatus": payment_status if payment_method != "free" else "not_required",
        "paymentRequiredOnSelected": payment_method != "free",
        "questionnaireAnswers": questionnaire_count,
        "questionnaireTextAnswers": questionnaire_text_count,
    }

class LivePocketLotteryExecutor:
    """执行单个 LivePocket 抽票请求。"""

    def execute(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        trace_id = str(payload.get("executionId") or payload.get("scheduleId") or uuid.uuid4())[:36]
        task_options = parse_json_maybe(payload.get("taskOptions"))
        login_context = parse_json_maybe(payload.get("loginReqData"))
        account_info = parse_json_maybe(payload.get("accountInfo"))
        configured_url = task_options.get("eventUrl") or task_options.get("lotteryEntryUrl")
        configured_tickets_url = (
            task_options.get("ticketEntryUrl")
            or task_options.get("ticketsUrl")
            or task_options.get("lotteryTicketsUrl")
            or task_options.get("lotteryEntryUrl")
        )
        configured_reception_id = (
            payload.get("receptionId")
            or payload.get("sessionReceptionId")
            or task_options.get("receptionId")
            or task_options.get("sessionReceptionId")
        )
        session_id = payload.get("sessionId") or task_options.get("sessionId")
        session_label = payload.get("sessionLabel") or task_options.get("sessionLabel")
        payload_ticket_field = payload.get("ticketField") or payload.get("sessionTicketField")
        payload_ticket_id = payload.get("ticketId") or payload.get("sessionTicketId")
        if payload_ticket_field and not task_options.get("ticketField"):
            task_options["ticketField"] = payload_ticket_field
        if payload_ticket_id and not task_options.get("ticketId"):
            task_options["ticketId"] = payload_ticket_id
        purchase_type = str(payload.get("purchaseType") or task_options.get("purchaseType") or "lottery").strip() or "lottery"
        quantity_mode = str(task_options.get("quantityMode") or payload.get("quantityMode") or "").strip().lower()
        email = str(payload.get("email") or task_options.get("email") or "")
        password = str(
            payload.get("password")
            or payload.get("platformPassword")
            or account_info.get("platformPassword")
            or account_info.get("password")
            or ""
        )
        platform_code = str(payload.get("platformCode") or task_options.get("platformCode") or "livepocket")
        backend_base_url = str(payload.get("backendBaseUrl") or task_options.get("backendBaseUrl") or "").strip()

        log(
            "[lottery-entry] "
            f"START trace={trace_id}, taskId={payload.get('taskId')}, scheduleId={payload.get('scheduleId')}, "
            f"account={email}, purchaseType={purchase_type}, quantityMode={quantity_mode or '-'}, "
            f"sessionId={session_id}, sessionLabel={session_label}"
        )
        log(
            "[lottery-entry] "
            f"INPUT trace={trace_id}, eventUrl={configured_url}, configuredTicketsUrl={configured_tickets_url}, "
            f"ticketId={task_options.get('ticketId') or '-'}, ticketField={task_options.get('ticketField') or '-'}, "
            f"hasPassword={bool(password)}, hasLoginReqData={bool(login_context)}, hasBackendBaseUrl={bool(backend_base_url)}, "
            f"taskOptionKeys={sorted(task_options.keys())}"
        )

        if not configured_url:
            return self.fail("缺少 taskOptions.eventUrl")

        session, auth_source, login_state, auth_error = resolve_authenticated_session(
            email=email,
            password=password,
            login_context=login_context,
            login_func=lambda: login_livepocket_runtime(email, password, platform_code, trace_id, backend_base_url),
            logger=log,
            trace_id=trace_id,
            flow_label="lottery-entry",
            request_headers=request_headers,
        )
        if not session:
            return self.fail(auth_error or "账号登录态不可用，且现场登录失败")
        log(
            "[lottery-entry] LOGIN context "
            f"trace={trace_id}, authSource={auth_source}, cookieNames={session_cookie_names(session)}, cookieCount={len(session.cookies)}"
        )
        refreshed_login_context = export_session_login_context(session)
        return self.execute_authenticated(
            payload=payload,
            session=session,
            auth_source=auth_source,
            refreshed_login_context=refreshed_login_context,
            trace_id=trace_id,
            task_options=task_options,
        )

    def execute_batch(self, payload: Dict[str, Any]) -> List[Dict[str, Any]]:
        batch_trace_id = str(payload.get("dispatchId") or payload.get("batchTaskId") or uuid.uuid4())[:36]
        login_context = parse_json_maybe(payload.get("loginReqData"))
        account_info = parse_json_maybe(payload.get("accountInfo"))
        email = str(payload.get("email") or "")
        password = str(
            payload.get("password")
            or payload.get("platformPassword")
            or account_info.get("platformPassword")
            or account_info.get("password")
            or ""
        )
        platform_code = str(payload.get("platformCode") or "livepocket")
        backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
        items = payload.get("items") if isinstance(payload.get("items"), list) else []
        if not items:
            return [{
                "executionId": "",
                "scheduleId": "",
                "success": False,
                "status": "failed",
                "message": "批量抽票缺少 items",
            }]

        log(
            "[lottery-batch-entry] "
            f"START trace={batch_trace_id}, batchTaskId={payload.get('batchTaskId')}, "
            f"account={email}, itemCount={len(items)}"
        )
        session, auth_source, _login_state, auth_error = resolve_authenticated_session(
            email=email,
            password=password,
            login_context=login_context,
            login_func=lambda: login_livepocket_runtime(email, password, platform_code, batch_trace_id, backend_base_url),
            logger=log,
            trace_id=batch_trace_id,
            flow_label="lottery-batch-entry",
            request_headers=request_headers,
        )
        if not session:
            message = auth_error or "账号登录态不可用，且现场登录失败"
            return [
                {
                    "executionId": str(item.get("executionId") or ""),
                    "scheduleId": str(item.get("scheduleId") or ""),
                    "success": False,
                    "status": "failed",
                    "message": message,
                    "authSource": "runtime-login",
                }
                for item in items
            ]
        log(
            "[lottery-batch-entry] LOGIN context "
            f"trace={batch_trace_id}, authSource={auth_source}, cookieNames={session_cookie_names(session)}, cookieCount={len(session.cookies)}"
        )

        results: List[Dict[str, Any]] = []
        for index, item in enumerate(items, start=1):
            item_payload = dict(item if isinstance(item, dict) else {})
            item_payload.setdefault("email", email)
            item_payload.setdefault("password", password)
            item_payload.setdefault("platformPassword", password)
            item_payload.setdefault("platformCode", platform_code)
            item_payload.setdefault("backendBaseUrl", backend_base_url)
            item_payload.setdefault("accountInfo", payload.get("accountInfo"))
            item_task_options = parse_json_maybe(item_payload.get("taskOptions"))
            if not item_task_options and item_payload.get("eventUrl"):
                item_task_options = {
                    "eventUrl": item_payload.get("eventUrl"),
                    "eventTitle": item_payload.get("eventTitle"),
                    "receptionId": item_payload.get("receptionId"),
                    "receptionTitle": item_payload.get("receptionTitle"),
                    "salesType": item_payload.get("salesType"),
                    "ticketId": item_payload.get("ticketId"),
                    "ticketField": item_payload.get("ticketField"),
                    "sessionId": item_payload.get("sessionId"),
                    "sessionLabel": item_payload.get("sessionLabel"),
                    "quantityMode": item_payload.get("quantityMode"),
                    "entryQuantity": item_payload.get("purchaseQuantity") or 1,
                }
            item_trace_id = str(item_payload.get("executionId") or f"{batch_trace_id}-{index}")[:36]
            log(
                "[lottery-batch-entry] ITEM "
                f"trace={item_trace_id}, index={index}/{len(items)}, executionId={item_payload.get('executionId')}, "
                f"eventUrl={item_task_options.get('eventUrl') if isinstance(item_task_options, dict) else item_payload.get('eventUrl')}"
            )
            result = self.execute_authenticated(
                payload=item_payload,
                session=session,
                auth_source=auth_source,
                refreshed_login_context=export_session_login_context(session),
                trace_id=item_trace_id,
                task_options=item_task_options,
            )
            results.append(result)
        log(
            "[lottery-batch-entry] DONE "
            f"trace={batch_trace_id}, batchTaskId={payload.get('batchTaskId')}, successCount={sum(1 for item in results if item.get('success'))}, "
            f"failedCount={sum(1 for item in results if not item.get('success'))}"
        )
        return results

    def execute_authenticated(
        self,
        payload: Dict[str, Any],
        session: requests.Session,
        auth_source: str,
        refreshed_login_context: Dict[str, Any],
        trace_id: str,
        task_options: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        task_options = task_options or parse_json_maybe(payload.get("taskOptions"))
        configured_url = task_options.get("eventUrl") or task_options.get("lotteryEntryUrl")
        configured_tickets_url = (
            task_options.get("ticketEntryUrl")
            or task_options.get("ticketsUrl")
            or task_options.get("lotteryTicketsUrl")
            or task_options.get("lotteryEntryUrl")
        )
        configured_reception_id = (
            payload.get("receptionId")
            or payload.get("sessionReceptionId")
            or task_options.get("receptionId")
            or task_options.get("sessionReceptionId")
        )
        session_id = payload.get("sessionId") or task_options.get("sessionId")
        session_label = payload.get("sessionLabel") or task_options.get("sessionLabel")
        payload_ticket_field = payload.get("ticketField") or payload.get("sessionTicketField")
        payload_ticket_id = payload.get("ticketId") or payload.get("sessionTicketId")
        if payload_ticket_field and not task_options.get("ticketField"):
            task_options["ticketField"] = payload_ticket_field
        if payload_ticket_id and not task_options.get("ticketId"):
            task_options["ticketId"] = payload_ticket_id
        purchase_type = str(payload.get("purchaseType") or task_options.get("purchaseType") or "lottery").strip() or "lottery"
        quantity_mode = str(task_options.get("quantityMode") or payload.get("quantityMode") or "").strip().lower()

        if not configured_url:
            return self.fail("缺少 taskOptions.eventUrl")

        event_url = event_detail_url_from_url(str(configured_url))
        if (
            not looks_like_tickets_url(str(configured_tickets_url or ""))
            and configured_reception_id
            and not is_virtual_reception_id(configured_reception_id)
        ):
            derived_tickets_url = build_tickets_url(event_url, str(configured_reception_id))
            if looks_like_tickets_url(derived_tickets_url):
                configured_tickets_url = derived_tickets_url
        log(f"[lottery-entry] GET event detail trace={trace_id}: {event_url}")
        event_resp = session.get(
            event_url,
            headers=request_headers(referer=LIVEPOCKET_BASE_URL + "/"),
            allow_redirects=True,
            timeout=30,
        )
        event_text = event_resp.text or ""
        event_title = extract_page_title(event_text)
        event_ticket_candidates = extract_ticket_entry_candidates(event_text, event_url)
        event_ticket_cards = extract_event_ticket_cards(event_text)
        log(
            "[lottery-entry] EVENT response "
            f"trace={trace_id}, status={event_resp.status_code}, finalUrl={event_resp.url}, "
            f"bytes={len(event_text)}, title={event_title or '-'}, "
            f"ticketEntryCandidates={len(event_ticket_candidates)}, ticketCards={len(event_ticket_cards)}"
        )
        if event_ticket_candidates:
            log(f"[lottery-entry] EVENT ticket candidates trace={trace_id}: {event_ticket_candidates[:5]}")
        elif event_ticket_cards:
            log(f"[lottery-entry] EVENT ticket cards trace={trace_id}: {event_ticket_cards[:10]}")
        if event_resp.status_code >= 400:
            return self.fail(
                "活动详情页访问失败",
                http_status=event_resp.status_code,
                raw_result=compact_text(visible_text(event_text)),
                request_url=event_url,
            )

        already_applied_message = extract_lottery_already_applied_message(event_text)
        if already_applied_message:
            selected_event_card = select_event_ticket_card(
                event_ticket_cards,
                ticket_id=payload_ticket_id,
                ticket_field=payload_ticket_field,
                session_id=session_id,
            )
            selected_session = {
                "fieldName": selected_event_card.get("ticketField", ""),
                "ticketId": selected_event_card.get("ticketId", ""),
                "label": selected_event_card.get("label", ""),
                "receptionTitle": selected_event_card.get("receptionTitle", ""),
                "salesType": selected_event_card.get("salesType", ""),
            }
            message = f"LivePocket 受付已申込済み: {already_applied_message}"
            log(
                "[lottery-entry] DONE "
                f"trace={trace_id}, status=already_applied, message={message}, "
                f"ticketField={selected_session.get('fieldName') or '-'}, ticketId={selected_session.get('ticketId') or '-'}"
            )
            return {
                "success": True,
                "status": "submitted",
                "message": message,
                "httpStatus": event_resp.status_code,
                "requestUrl": event_url,
                "submitUrl": "",
                "sessionId": session_id,
                "sessionLabel": session_label,
                "selectedSession": selected_session,
                "selectedReason": "alreadyApplied",
                "ticketField": selected_session.get("fieldName"),
                "ticketId": selected_session.get("ticketId"),
                "resultUrl": event_resp.url or event_url,
                "rawResult": compact_text(visible_text(event_text), 1600),
                "authSource": auth_source,
                "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False),
                "alreadyApplied": True,
                "ticketCards": event_ticket_cards,
            }

        configured_tickets_url_text = str(configured_tickets_url or "")
        tickets_url = configured_tickets_url_text if looks_like_tickets_url(configured_tickets_url_text) else ""
        fallback_tickets_url = tickets_url
        if looks_like_tickets_url(tickets_url):
            log(
                "[lottery-entry] EVENT ticket entry "
                f"trace={trace_id}, source=configured-reception, ticketEntryUrl={tickets_url}, "
                f"receptionId={configured_reception_id or '-'}"
            )
        else:
            matched_candidate = ""
            if configured_reception_id and event_ticket_candidates:
                if is_virtual_reception_id(configured_reception_id):
                    reception_index = reception_index_from_id(configured_reception_id)
                    if reception_index is not None and 0 <= reception_index < len(event_ticket_candidates):
                        matched_candidate = str(event_ticket_candidates[reception_index].get("url") or "")
                        if looks_like_tickets_url(matched_candidate):
                            tickets_url = matched_candidate
                            log(
                                "[lottery-entry] EVENT ticket entry "
                                f"trace={trace_id}, source=event-ticket-candidates-index, "
                                f"ticketEntryUrl={tickets_url}, receptionId={configured_reception_id}"
                            )
                else:
                    for candidate in event_ticket_candidates:
                        candidate_url = str(candidate.get("url") or "")
                        if f"/receptions/{configured_reception_id}/tickets" in candidate_url:
                            matched_candidate = candidate_url
                            break
                    if looks_like_tickets_url(matched_candidate):
                        tickets_url = matched_candidate
                        log(
                            "[lottery-entry] EVENT ticket entry "
                            f"trace={trace_id}, source=event-ticket-candidates-id, "
                            f"ticketEntryUrl={tickets_url}, receptionId={configured_reception_id}"
                        )
            if not looks_like_tickets_url(tickets_url):
                selected_event_card = select_event_ticket_card(
                    event_ticket_cards,
                    ticket_id=payload_ticket_id,
                    ticket_field=payload_ticket_field,
                    session_id=session_id,
                )
                selected_card_reception_id = str(selected_event_card.get("receptionId") or "").strip()
                if selected_card_reception_id and event_ticket_candidates:
                    if is_virtual_reception_id(selected_card_reception_id):
                        reception_index = reception_index_from_id(selected_card_reception_id)
                        if reception_index is not None and 0 <= reception_index < len(event_ticket_candidates):
                            matched_candidate = str(event_ticket_candidates[reception_index].get("url") or "")
                            if looks_like_tickets_url(matched_candidate):
                                tickets_url = matched_candidate
                                log(
                                    "[lottery-entry] EVENT ticket entry "
                                    f"trace={trace_id}, source=selected-card-reception-index, "
                                    f"ticketEntryUrl={tickets_url}, receptionId={selected_card_reception_id}"
                                )
                    else:
                        for candidate in event_ticket_candidates:
                            candidate_url = str(candidate.get("url") or "")
                            if f"/receptions/{selected_card_reception_id}/tickets" in candidate_url:
                                matched_candidate = candidate_url
                                break
                        if looks_like_tickets_url(matched_candidate):
                            tickets_url = matched_candidate
                            log(
                                "[lottery-entry] EVENT ticket entry "
                                f"trace={trace_id}, source=selected-card-reception-id, "
                                f"ticketEntryUrl={tickets_url}, receptionId={selected_card_reception_id}"
                            )
            if not looks_like_tickets_url(tickets_url):
                tickets_url = extract_ticket_entry_url(event_text, fallback_tickets_url, event_url)
        public_event_info: Dict[str, Any] | None = None
        if not looks_like_tickets_url(tickets_url):
            try:
                public_event_info = fetch_lottery_event_info(event_url, auth_session=session)
                public_tickets_url = str(public_event_info.get("ticketEntryUrl") or "")
                public_sessions = public_event_info.get("sessions") or []
                matched_session = None
                for item in public_sessions:
                    if not isinstance(item, dict):
                        continue
                    if session_id and str(item.get("sessionId") or "") == str(session_id):
                        matched_session = item
                        break
                    if payload_ticket_id and str(item.get("ticketId") or "") == str(payload_ticket_id):
                        matched_session = item
                        break
                    if payload_ticket_field and str(item.get("ticketField") or "") == str(payload_ticket_field):
                        matched_session = item
                        break
                public_reception_id = ""
                if isinstance(matched_session, dict):
                    public_reception_id = str(matched_session.get("receptionId") or "").strip()
                if not looks_like_tickets_url(public_tickets_url) and public_reception_id:
                    if is_virtual_reception_id(public_reception_id):
                        reception_index = reception_index_from_id(public_reception_id)
                        public_candidates = public_event_info.get("ticketEntryCandidates") or []
                        if (
                            isinstance(public_candidates, list)
                            and reception_index is not None
                            and 0 <= reception_index < len(public_candidates)
                        ):
                            public_tickets_url = str((public_candidates[reception_index] or {}).get("url") or "")
                    else:
                        public_tickets_url = build_tickets_url(event_url, public_reception_id)
                if looks_like_tickets_url(public_tickets_url):
                    tickets_url = public_tickets_url
                    log(
                        "[lottery-entry] EVENT fallback "
                        f"trace={trace_id}, source=public-event-info, ticketEntryUrl={tickets_url}, "
                        f"matchedReceptionId={public_reception_id or '-'}"
                    )
            except Exception as exc:
                log(f"[lottery-entry] EVENT fallback trace={trace_id}, source=public-event-info failed: {exc}")
        if not looks_like_tickets_url(tickets_url):
            message = "未解析到 LivePocket tickets 页链接，无法进入抽票表单"
            log(
                "[lottery-entry] STOP "
                f"trace={trace_id}, reason={message}, configuredTicketsUrl={configured_tickets_url_text or '-'}, "
                f"eventFinalUrl={event_resp.url}"
            )
            return self.fail(
                message,
                http_status=event_resp.status_code,
                raw_result=compact_text(visible_text(event_text)),
                request_url=event_url,
                extra={
                    "eventUrl": event_url,
                    "eventFinalUrl": event_resp.url,
                    "eventTitle": event_title,
                    "configuredTicketsUrl": configured_tickets_url_text,
                    "publicEventInfo": public_event_info,
                    "ticketEntryCandidates": event_ticket_candidates,
                    "ticketCards": event_ticket_cards,
                },
            )
        log(f"[lottery-entry] GET tickets page trace={trace_id}: {tickets_url}")
        tickets_resp = session.get(
            tickets_url,
            headers=request_headers(
                accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
                referer=event_resp.url or event_url,
            ),
            allow_redirects=False,
            timeout=30,
        )
        tickets_text = tickets_resp.text or ""
        log(
            "[lottery-entry] TICKETS response "
            f"trace={trace_id}, status={tickets_resp.status_code}, finalUrl={tickets_resp.url}, "
            f"location={tickets_resp.headers.get('Location', '-')}, bytes={len(tickets_text)}, "
            f"title={extract_page_title(tickets_text) or '-'}"
        )
        tickets_browser_snapshot_adopted = False
        tickets_blocked = is_blocked_livepocket_response(tickets_resp, require_token=True)
        if tickets_blocked:
            browser_proxy = session_proxy_from_requests_session(session)
            log(
                "[lottery-entry] TICKETS blocked "
                f"trace={trace_id}, status={tickets_resp.status_code}, finalUrl={tickets_resp.url}, "
                f"title={extract_page_title(tickets_text) or '-'}, browserRecover=True"
            )
            try:
                browser_tickets_text, browser_tickets_final_url = fetch_page_with_browser(
                    tickets_url,
                    proxy=browser_proxy,
                    auth_session=session,
                    expect="tickets",
                    sync_cookies_to_session=True,
                )
                browser_has_token = bool(extract_input_value(browser_tickets_text, "authenticity_token"))
                log(
                    "[lottery-entry] TICKETS browser recover "
                    f"trace={trace_id}, finalUrl={browser_tickets_final_url}, "
                    f"bytes={len(browser_tickets_text)}, title={extract_page_title(browser_tickets_text) or '-'}, "
                    f"hasToken={browser_has_token}, cookieNames={session_cookie_names(session)}"
                )
                tickets_resp = session.get(
                    tickets_url,
                    headers=request_headers(
                        accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
                        referer=event_resp.url or event_url,
                    ),
                    allow_redirects=False,
                    timeout=30,
                )
                tickets_text = tickets_resp.text or ""
                log(
                    "[lottery-entry] TICKETS retry response "
                    f"trace={trace_id}, status={tickets_resp.status_code}, finalUrl={tickets_resp.url}, "
                    f"location={tickets_resp.headers.get('Location', '-')}, bytes={len(tickets_text)}, "
                    f"title={extract_page_title(tickets_text) or '-'}"
                )
                if is_blocked_livepocket_response(tickets_resp, require_token=True) and browser_has_token:
                    tickets_text = browser_tickets_text
                    tickets_browser_snapshot_adopted = True
                    log(
                        "[lottery-entry] TICKETS browser snapshot adopted "
                        f"trace={trace_id}, reason=requests-retry-still-blocked"
                    )
            except Exception as exc:
                return self.fail(
                    f"抽票购票页人机校验自动处理失败: {exc}",
                    http_status=tickets_resp.status_code,
                    raw_result=compact_text(visible_text(tickets_text)),
                    request_url=tickets_url,
                )
        tickets_redirect = urljoin(LIVEPOCKET_BASE_URL, tickets_resp.headers.get("Location", "")) if tickets_resp.headers.get("Location") else ""
        if tickets_resp.status_code in (301, 302, 303, 307, 308):
            return self.fail(
                f"抽票购票页被重定向，未返回 tickets 表单: {tickets_redirect}",
                http_status=tickets_resp.status_code,
                raw_result=compact_text(visible_text(tickets_text)),
                request_url=tickets_url,
                submit_url=tickets_redirect,
            )
        if tickets_resp.status_code >= 400 and not tickets_browser_snapshot_adopted:
            return self.fail(
                "抽票购票页访问失败",
                http_status=tickets_resp.status_code,
                raw_result=compact_text(visible_text(tickets_text)),
                request_url=tickets_url,
            )

        authenticity_token = extract_input_value(tickets_text, "authenticity_token")
        session_options = parse_lottery_session_options(tickets_text)
        log(
            "[lottery-entry] TICKETS parsed "
            f"trace={trace_id}, hasToken={bool(authenticity_token)}, sessionCount={len(session_options)}, "
            f"sessions={session_options[:10]}"
        )
        if not authenticity_token:
            return self.fail(
                "抽票购票页 token 缺失，可能未进入真实 tickets 表单",
                http_status=tickets_resp.status_code,
                raw_result=compact_text(visible_text(tickets_text)),
                request_url=tickets_url,
            )

        selected_session, selected_reason = choose_lottery_session(session_options, task_options, session_id, session_label)
        if not selected_session:
            return self.fail(
                selected_reason,
                http_status=tickets_resp.status_code,
                raw_result=compact_text(visible_text(tickets_text)),
                request_url=tickets_url,
                extra={"availableSessions": session_options},
            )
        if selected_session.get("disabled") and not is_truthy(task_options.get("includeDisabledSession")):
            return self.fail(
                f"目标时间段不可选: {selected_session.get('label') or selected_session.get('fieldName')}",
                http_status=tickets_resp.status_code,
                raw_result=compact_text(visible_text(tickets_text)),
                request_url=tickets_url,
                extra={"selectedSession": selected_session, "availableSessions": session_options},
            )
        try:
            quantity = resolve_livepocket_entry_quantity(
                purchase_type=purchase_type,
                quantity_mode=quantity_mode,
                selected_session=selected_session,
                task_options=task_options,
                payload=payload,
            )
        except ValueError as exc:
            return self.fail(
                str(exc),
                http_status=tickets_resp.status_code,
                request_url=tickets_url,
                extra={"selectedSession": selected_session, "availableSessions": session_options},
            )

        select_url = extract_select_seat_url(tickets_text, tickets_url)
        select_form = {
            "authenticity_token": authenticity_token,
            str(selected_session["fieldName"]): quantity,
        }
        log(
            "[lottery-entry] POST select_seat: "
            f"trace={trace_id}, url={select_url}, field={selected_session.get('fieldName')}, "
            f"ticketId={selected_session.get('ticketId')}, label={selected_session.get('label')}, quantity={quantity}"
        )
        select_resp = session.post(
            select_url,
            data=select_form,
            headers={
                **request_headers(
                    accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    referer=tickets_url,
                ),
                "Content-Type": "application/x-www-form-urlencoded",
            },
            allow_redirects=False,
            timeout=30,
        )
        select_text = select_resp.text or ""
        select_location = select_resp.headers.get("Location", "")
        log(
            "[lottery-entry] SELECT response "
            f"trace={trace_id}, status={select_resp.status_code}, location={select_location or '-'}, "
            f"bytes={len(select_text)}, title={extract_page_title(select_text) or '-'}"
        )
        confirm_url = urljoin(LIVEPOCKET_BASE_URL, select_location) if "purchase/confirm" in select_location else parse_confirm_url_from_html(select_text)
        event_id, reserve_id = parse_confirm_params_from_url(confirm_url or "")
        if not event_id or not reserve_id:
            errors = extract_livepocket_errors(select_text)
            return self.fail(
                "select_seat 未返回确认页参数" + (f": {'; '.join(errors)}" if errors else ""),
                http_status=select_resp.status_code,
                raw_result=compact_text(visible_text(select_text)),
                request_url=tickets_url,
                submit_url=select_url,
                extra={"selectedSession": selected_session, "availableSessions": session_options},
            )

        log(f"[lottery-entry] GET confirm page trace={trace_id}: {confirm_url}")
        confirm_resp = session.get(
            confirm_url,
            headers=request_headers(referer=tickets_url),
            allow_redirects=True,
            timeout=30,
        )
        confirm_text = confirm_resp.text or ""
        log(
            "[lottery-entry] CONFIRM response "
            f"trace={trace_id}, status={confirm_resp.status_code}, finalUrl={confirm_resp.url}, "
            f"bytes={len(confirm_text)}, title={extract_page_title(confirm_text) or '-'}, "
            f"reserveId={reserve_id}, eventId={event_id}"
        )
        if confirm_resp.status_code >= 400:
            return self.fail(
                "抽票确认页访问失败",
                http_status=confirm_resp.status_code,
                raw_result=compact_text(visible_text(confirm_text)),
                request_url=confirm_url,
                extra={"selectedSession": selected_session, "reserveId": reserve_id},
            )

        try:
            purchase_form, payment_context = build_lottery_purchase_form(confirm_text, event_id, reserve_id, purchase_type)
        except ValueError as exc:
            return self.fail(
                str(exc),
                http_status=confirm_resp.status_code,
                raw_result=compact_text(visible_text(confirm_text)),
                request_url=confirm_url,
                extra={"selectedSession": selected_session, "reserveId": reserve_id},
            )

        csrf_token = extract_csrf_token(confirm_text) or purchase_form.get("authenticity_token")
        purchase_headers = request_headers(
            accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
            referer=confirm_url,
            turbo=True,
        )
        if csrf_token:
            purchase_headers["X-CSRF-Token"] = csrf_token

        log(
            "[lottery-entry] POST purchase: "
            f"trace={trace_id}, url={PURCHASE_URL}, payment={purchase_form.get('order_form[payment_method]', '')}, "
            f"cvsType={purchase_form.get('order_form[sbps_web_cvs_type]', '-')}, "
            f"formKeys={sorted(purchase_form.keys())}"
        )
        purchase_resp = session.post(
            PURCHASE_URL,
            data=purchase_form,
            headers=purchase_headers,
            allow_redirects=False,
            timeout=30,
        )
        purchase_text = purchase_resp.text or ""
        purchase_location = purchase_resp.headers.get("Location", "")
        log(
            "[lottery-entry] PURCHASE response "
            f"trace={trace_id}, status={purchase_resp.status_code}, location={purchase_location or '-'}, "
            f"bytes={len(purchase_text)}, title={extract_page_title(purchase_text) or '-'}"
        )
        result_url = urljoin(LIVEPOCKET_BASE_URL, purchase_location) if purchase_location else purchase_resp.url
        order_id = parse_order_id(result_url) or parse_order_id(purchase_text)
        result_excerpt = compact_text(visible_text(purchase_text))
        errors = extract_livepocket_errors(purchase_text)
        if purchase_resp.status_code >= 400 or (errors and purchase_resp.status_code not in (302, 303)):
            return self.fail(
                "抽票提交失败" + (f": {'; '.join(errors)}" if errors else ""),
                http_status=purchase_resp.status_code,
                raw_result=result_excerpt,
                request_url=confirm_url,
                submit_url=PURCHASE_URL,
                extra={
                    "selectedSession": selected_session,
                    "ticketField": selected_session.get("fieldName"),
                    "ticketId": selected_session.get("ticketId"),
                    "confirmUrl": confirm_url,
                    "reserveId": reserve_id,
                    "availableSessions": session_options,
                },
            )

        log(
            "[lottery-entry] DONE "
            f"trace={trace_id}, status=submitted, resultUrl={result_url}, orderId={order_id or '-'}, "
            f"reserveId={reserve_id}, ticketField={selected_session.get('fieldName')}"
        )
        success_message = "抽票提交完成"
        if purchase_type == "flash_sale":
            success_message = "普通抢票提交完成"
            if payment_context.get("paymentStatus") == "offline_pending":
                success_message = "普通抢票提交完成，等待 Lawson 便利店支付"
        return {
            "success": True,
            "status": "submitted",
            "message": success_message,
            "httpStatus": purchase_resp.status_code,
            "requestUrl": tickets_url,
            "submitUrl": PURCHASE_URL,
            "sessionId": session_id,
            "sessionLabel": session_label,
            "selectedSession": selected_session,
            "selectedReason": selected_reason,
            "ticketField": selected_session.get("fieldName"),
            "ticketId": selected_session.get("ticketId"),
            "confirmUrl": confirm_url,
            "reserveId": reserve_id,
            "orderId": order_id,
            "resultUrl": result_url,
            "rawResult": result_excerpt,
            "paymentMethod": payment_context.get("paymentMethod"),
            "cvsType": payment_context.get("cvsType"),
            "paymentStatus": payment_context.get("paymentStatus"),
            "paymentProvider": "lawson" if payment_context.get("paymentMethod") == "cvs" else "",
            "paymentMode": "cod_store" if payment_context.get("paymentMethod") == "cvs" else "",
            "paymentRequiredOnSelected": payment_context.get("paymentRequiredOnSelected"),
            "questionnaireAnswers": payment_context.get("questionnaireAnswers"),
            "authSource": auth_source,
            "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False),
        }

    @staticmethod
    def fail(
        message: str,
        http_status: Optional[int] = None,
        raw_result: str = "",
        request_url: str = "",
        submit_url: str = "",
        extra: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        result = {
            "success": False,
            "status": "failed",
            "message": message,
            "httpStatus": http_status,
            "requestUrl": request_url,
            "submitUrl": submit_url,
            "rawResult": raw_result,
        }
        if extra:
            result.update(extra)
        return result


class LotteryRedisWorkerConfig:
    def __init__(self) -> None:
        self.redis_url = env_text("LOTTERY_REDIS_URL")
        self.redis_host = env_text("LOTTERY_REDIS_HOST", "127.0.0.1")
        self.redis_port = env_int("LOTTERY_REDIS_PORT", 6379)
        self.redis_password = os.environ.get("LOTTERY_REDIS_PASSWORD") or None
        self.redis_db = env_int("LOTTERY_REDIS_DB", 0)
        self.ready_stream_key = env_text("LOTTERY_READY_STREAM_KEY", "ticket:lottery:stream:ready")
        self.result_stream_key = env_text("LOTTERY_RESULT_STREAM_KEY", "ticket:lottery:stream:result")
        self.delayed_zset_key = env_text("LOTTERY_DELAYED_ZSET_KEY", "ticket:lottery:zset:delayed")
        self.job_key_prefix = env_text("LOTTERY_JOB_KEY_PREFIX", "ticket:lottery:job:")
        self.account_lock_key_prefix = env_text("LOTTERY_ACCOUNT_LOCK_KEY_PREFIX", "ticket:lottery:lock:account:")
        self.event_parse_ready_stream_key = env_text("LOTTERY_EVENT_PARSE_READY_STREAM_KEY", "ticket:lottery:event-parse:stream:ready")
        self.event_parse_result_stream_key = env_text("LOTTERY_EVENT_PARSE_RESULT_STREAM_KEY", "ticket:lottery:event-parse:stream:result")
        self.event_parse_job_key_prefix = env_text("LOTTERY_EVENT_PARSE_JOB_KEY_PREFIX", "ticket:lottery:event-parse:job:")
        self.event_parse_consumer_group = env_text("LOTTERY_EVENT_PARSE_CONSUMER_GROUP", "ticket-lottery-event-parser")
        self.consumer_group = env_text("LOTTERY_CONSUMER_GROUP", "ticket-lottery-executor")
        default_consumer = f"ticket-lottery-{socket.gethostname()}-{os.getpid()}"
        self.consumer_name = env_text("LOTTERY_CONSUMER_NAME", default_consumer)
        self.workers = max(env_int("LOTTERY_WORKERS", 5), 1)
        self.event_parse_workers = max(env_int("LOTTERY_EVENT_PARSE_WORKERS", 1), 1)
        self.block_ms = max(env_int("LOTTERY_BLOCK_MS", 5000), 1000)
        self.poll_interval_ms = max(env_int("LOTTERY_POLL_INTERVAL_MS", 500), 100)
        self.pending_reclaim_idle_ms = max(env_int("LOTTERY_PENDING_RECLAIM_IDLE_MS", 60000), 5000)
        self.account_lock_ttl_seconds = max(env_int("LOTTERY_ACCOUNT_LOCK_TTL_SECONDS", 300), 30)
        self.requeue_delay_seconds = max(env_int("LOTTERY_REQUEUE_DELAY_SECONDS", 30), 1)


class LotteryRedisWorker:
    def __init__(self, config: Optional[LotteryRedisWorkerConfig] = None) -> None:
        if redis is None:
            raise RuntimeError("缺少 redis 依赖，请先安装 auto-py/requirements.txt")
        self.config = config or LotteryRedisWorkerConfig()
        self.client = self.create_client()
        self.executor = LivePocketLotteryExecutor()
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
        self.ensure_stream_group(self.config.ready_stream_key, self.config.consumer_group)
        self.ensure_stream_group(self.config.event_parse_ready_stream_key, self.config.event_parse_consumer_group)

    def ensure_stream_group(self, stream_key: str, consumer_group: str) -> None:
        try:
            self.client.xgroup_create(stream_key, consumer_group, id="0", mkstream=True)
        except Exception as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def start(self) -> None:
        self.client.ping()
        self.ensure_group()
        log(
            "[lottery-worker] START "
            f"workers={self.config.workers}, ready={self.config.ready_stream_key}, "
            f"delayed={self.config.delayed_zset_key}, result={self.config.result_stream_key}, "
            f"group={self.config.consumer_group}, consumer={self.config.consumer_name}, "
            f"eventParseWorkers={self.config.event_parse_workers}, eventParseReady={self.config.event_parse_ready_stream_key}"
        )
        log(f"[lottery-worker] PROXY {proxy_status()}")
        promoter = threading.Thread(target=self.promote_loop, name="lottery-delayed-promoter", daemon=True)
        promoter.start()
        self.threads.append(promoter)
        for index in range(self.config.workers):
            thread = threading.Thread(target=self.worker_loop, args=(index,), name=f"lottery-worker-{index}", daemon=True)
            thread.start()
            self.threads.append(thread)
        for index in range(self.config.event_parse_workers):
            thread = threading.Thread(target=self.event_parse_worker_loop, args=(index,), name=f"lottery-event-parser-{index}", daemon=True)
            thread.start()
            self.threads.append(thread)

    def serve_forever(self) -> None:
        self.start()
        try:
            while not self.stop_event.is_set():
                time.sleep(1)
        except KeyboardInterrupt:
            self.stop()

    def stop(self) -> None:
        self.stop_event.set()

    def promote_loop(self) -> None:
        while not self.stop_event.is_set():
            try:
                self.promote_due_jobs()
            except Exception as exc:
                log(f"[lottery-worker] promote delayed failed: {exc}")
            time.sleep(self.config.poll_interval_ms / 1000)

    def promote_due_jobs(self) -> None:
        now_ms = int(time.time() * 1000)
        items = self.client.zrangebyscore(self.config.delayed_zset_key, 0, now_ms, start=0, num=100)
        for execution_id in items:
            removed = self.client.zrem(self.config.delayed_zset_key, execution_id)
            if removed:
                self.add_ready(execution_id)

    def add_ready(self, execution_id: Any) -> None:
        self.client.xadd(
            self.config.ready_stream_key,
            {"executionId": str(execution_id), "enqueuedAt": str(int(time.time() * 1000))},
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
                log(f"[lottery-worker] worker={index} exception: {exc}")
                time.sleep(1)

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
            log(f"[lottery-worker] pending reclaim skipped: {exc}")
        return False

    def event_parse_worker_loop(self, index: int) -> None:
        consumer_name = f"{self.config.consumer_name}-event-parse-{index}"
        while not self.stop_event.is_set():
            try:
                if self.reclaim_one_event_parse_pending(consumer_name):
                    continue
                messages = self.client.xreadgroup(
                    self.config.event_parse_consumer_group,
                    consumer_name,
                    {self.config.event_parse_ready_stream_key: ">"},
                    count=1,
                    block=self.config.block_ms,
                )
                if not messages:
                    continue
                for _, stream_messages in messages:
                    for message_id, fields in stream_messages:
                        self.handle_event_parse_message(message_id, fields)
            except Exception as exc:
                log(f"[lottery-event-parser] worker={index} exception: {exc}")
                time.sleep(1)

    def reclaim_one_event_parse_pending(self, consumer_name: str) -> bool:
        try:
            result = self.client.xautoclaim(
                self.config.event_parse_ready_stream_key,
                self.config.event_parse_consumer_group,
                consumer_name,
                min_idle_time=self.config.pending_reclaim_idle_ms,
                start_id="0-0",
                count=1,
            )
            messages = result[1] if isinstance(result, (list, tuple)) and len(result) > 1 else []
            for message_id, fields in messages:
                self.handle_event_parse_message(message_id, fields)
                return True
        except Exception as exc:
            log(f"[lottery-event-parser] pending reclaim skipped: {exc}")
        return False

    def handle_event_parse_message(self, message_id: str, fields: Dict[str, Any]) -> None:
        request_id = str(fields.get("requestId") or "").strip()
        record_id = str(fields.get("recordId") or "").strip()
        if not request_id:
            self.ack_event_parse(message_id)
            return
        job_key = self.config.event_parse_job_key_prefix + request_id
        payload_text = self.client.get(job_key)
        if not payload_text:
            self.write_event_parse_result(
                {
                    "recordId": record_id,
                    "requestId": request_id,
                    "success": False,
                    "status": "failed",
                    "message": "活动解析任务载荷不存在",
                    "finishedAt": utc_now_text(),
                }
            )
            self.ack_event_parse(message_id)
            return
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            self.write_event_parse_result(
                {
                    "recordId": record_id,
                    "requestId": request_id,
                    "success": False,
                    "status": "failed",
                    "message": "活动解析任务载荷不是合法 JSON",
                    "finishedAt": utc_now_text(),
                }
            )
            self.ack_event_parse(message_id)
            return
        record_id = str(payload.get("recordId") or record_id)
        event_url = str(payload.get("eventUrl") or "")
        platform_id = str(payload.get("platformId") or "")
        platform_code = str(payload.get("platformCode") or "livepocket").strip() or "livepocket"
        started_at = utc_now_text()
        self.write_event_parse_result(
            {
                "recordId": record_id,
                "requestId": request_id,
                "platformId": platform_id,
                "platformCode": platform_code,
                "eventUrl": event_url,
                "success": False,
                "status": "running",
                "message": "Python 正在解析活动",
                "startedAt": started_at,
            }
        )
        try:
            parser = resolve_event_parser(platform_code)
            event_info = parser(event_url)
            event_info["platformId"] = platform_id
            event_info["platformCode"] = platform_code
            self.write_event_parse_result(
                {
                    "recordId": record_id,
                    "requestId": request_id,
                    "platformId": platform_id,
                    "platformCode": platform_code,
                    "eventUrl": event_info.get("eventUrl") or event_url,
                    "success": True,
                    "status": "completed",
                    "message": "活动解析完成",
                    "eventInfo": event_info,
                    "startedAt": started_at,
                    "finishedAt": utc_now_text(),
                }
            )
            self.client.delete(job_key)
            log(
                "[lottery-event-parser] DONE "
                f"recordId={record_id}, requestId={request_id}, sessions={len(event_info.get('sessions') or [])}, "
                f"ticketEntryUrl={event_info.get('ticketEntryUrl') or '-'}"
            )
        except Exception as exc:
            self.write_event_parse_result(
                {
                    "recordId": record_id,
                    "requestId": request_id,
                    "platformId": platform_id,
                    "platformCode": platform_code,
                    "eventUrl": event_url,
                    "success": False,
                    "status": "failed",
                    "message": f"活动解析失败：{exc}",
                    "startedAt": started_at,
                    "finishedAt": utc_now_text(),
                }
            )
        finally:
            self.ack_event_parse(message_id)

    def handle_message(self, message_id: str, fields: Dict[str, Any]) -> None:
        execution_id = str(fields.get("executionId") or "").strip()
        if not execution_id:
            self.ack(message_id)
            return
        job_key = self.config.job_key_prefix + execution_id
        payload_text = self.client.get(job_key)
        if not payload_text:
            self.write_result({"executionId": execution_id, "status": "failed", "success": False, "message": "抽票任务载荷不存在"})
            self.ack(message_id)
            return
        try:
            payload = json.loads(payload_text)
        except json.JSONDecodeError:
            self.write_result({"executionId": execution_id, "status": "failed", "success": False, "message": "抽票任务载荷不是合法 JSON"})
            self.ack(message_id)
            return

        account_id = str(payload.get("accountId") or "")
        lock_token = str(uuid.uuid4())
        lock_key = self.config.account_lock_key_prefix + account_id if account_id else ""
        if lock_key and not self.client.set(lock_key, lock_token, nx=True, ex=self.config.account_lock_ttl_seconds):
            self.client.zadd(
                self.config.delayed_zset_key,
                {execution_id: int((time.time() + self.config.requeue_delay_seconds) * 1000)},
            )
            self.ack(message_id)
            log(f"[lottery-worker] account locked, requeued executionId={execution_id}, accountId={account_id}")
            return
        mode = str(payload.get("mode") or "").strip()
        if mode == "lottery_batch":
            self.handle_batch_message(message_id, execution_id, payload, lock_key, lock_token, account_id)
            return

        platform_code = str(payload.get("platformCode") or "livepocket").strip() or "livepocket"
        purchase_type = str(payload.get("purchaseType") or "lottery").strip() or "lottery"
        started_at = utc_now_text()
        self.write_result(
            {
                "executionId": execution_id,
                "scheduleId": payload.get("scheduleId", ""),
                "accountId": account_id,
                "status": "running",
                "success": False,
                "message": "Python 普通抢票执行中" if purchase_type == "flash_sale" else "Python 抽票执行中",
                "startedAt": started_at,
            }
        )
        try:
            executor = resolve_executor(platform_code)
            result = executor.execute(payload)
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
                "[lottery-worker] DONE "
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
                    "message": f"执行异常：{exc}",
                    "startedAt": started_at,
                    "finishedAt": utc_now_text(),
                }
            )
        finally:
            if lock_key:
                self.release_lock(lock_key, lock_token)
            self.ack(message_id)

    def handle_batch_message(
        self,
        message_id: str,
        dispatch_id: str,
        payload: Dict[str, Any],
        lock_key: str,
        lock_token: str,
        account_id: str,
    ) -> None:
        items = payload.get("items") if isinstance(payload.get("items"), list) else []
        started_at = utc_now_text()
        for item in items:
            execution_id = str((item or {}).get("executionId") or "")
            schedule_id = str((item or {}).get("scheduleId") or "")
            if not execution_id:
                continue
            self.write_result(
                {
                    "executionId": execution_id,
                    "scheduleId": schedule_id,
                    "accountId": account_id,
                    "status": "running",
                    "success": False,
                    "message": "Python 批量抽票执行中",
                    "startedAt": started_at,
                }
            )
        try:
            results = self.executor.execute_batch(payload)
            finished_at = utc_now_text()
            result_map: Dict[str, Dict[str, Any]] = {}
            for result in results:
                result_map[str(result.get("executionId") or "")] = result
            for item in items:
                execution_id = str((item or {}).get("executionId") or "")
                if not execution_id:
                    continue
                schedule_id = str((item or {}).get("scheduleId") or "")
                result = dict(result_map.get(execution_id) or {})
                if not result:
                    result = {
                        "success": False,
                        "status": "failed",
                        "message": "批量抽票未返回该执行结果",
                    }
                result.update(
                    {
                        "executionId": execution_id,
                        "scheduleId": schedule_id,
                        "accountId": account_id,
                        "startedAt": started_at,
                        "finishedAt": finished_at,
                    }
                )
                self.write_result(result)
            self.client.delete(self.config.job_key_prefix + dispatch_id)
            log(
                "[lottery-batch-worker] DONE "
                f"dispatchId={dispatch_id}, accountId={account_id}, itemCount={len(items)}, "
                f"successCount={sum(1 for item in results if item.get('success'))}"
            )
        except Exception as exc:
            finished_at = utc_now_text()
            for item in items:
                execution_id = str((item or {}).get("executionId") or "")
                schedule_id = str((item or {}).get("scheduleId") or "")
                if not execution_id:
                    continue
                self.write_result(
                    {
                        "executionId": execution_id,
                        "scheduleId": schedule_id,
                        "accountId": account_id,
                        "status": "failed",
                        "success": False,
                        "message": f"执行异常：{exc}",
                        "startedAt": started_at,
                        "finishedAt": finished_at,
                    }
                )
        finally:
            if lock_key:
                self.release_lock(lock_key, lock_token)
            self.ack(message_id)

    def write_result(self, payload: Dict[str, Any]) -> None:
        self.write_stream_event(self.config.result_stream_key, payload)

    def write_event_parse_result(self, payload: Dict[str, Any]) -> None:
        self.write_stream_event(self.config.event_parse_result_stream_key, payload)

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
            log(f"[lottery-worker] release account lock failed key={lock_key}: {exc}")

    def ack(self, message_id: str) -> None:
        self.client.xack(self.config.ready_stream_key, self.config.consumer_group, message_id)

    def ack_event_parse(self, message_id: str) -> None:
        self.client.xack(self.config.event_parse_ready_stream_key, self.config.event_parse_consumer_group, message_id)

    def health(self) -> Dict[str, Any]:
        pong = self.client.ping()
        return {
            "ok": bool(pong),
            "mode": "worker",
            "startedAt": self.started_at,
            "workers": self.config.workers,
            "readyStream": self.config.ready_stream_key,
            "resultStream": self.config.result_stream_key,
            "eventParseReadyStream": self.config.event_parse_ready_stream_key,
            "eventParseResultStream": self.config.event_parse_result_stream_key,
            "delayedZset": self.config.delayed_zset_key,
            "readyLength": self.client.xlen(self.config.ready_stream_key),
            "eventParseReadyLength": self.client.xlen(self.config.event_parse_ready_stream_key),
            "delayedCount": self.client.zcard(self.config.delayed_zset_key),
        }


class LotteryRequestHandler(BaseHTTPRequestHandler):
    executor = LivePocketLotteryExecutor()

    def do_POST(self):
        request_path = urlparse(self.path).path
        if request_path == "/livepocket/lottery-entry":
            self.handle_lottery_entry()
            return
        if request_path == "/livepocket/lottery-batch-entry":
            self.handle_lottery_batch_entry()
            return
        if request_path == "/livepocket/profile-last-name":
            self.handle_profile_last_name()
            return
        if request_path == "/livepocket/login-keepalive":
            self.handle_login_keepalive()
            return
        if request_path == "/livepocket/register-batch":
            self.handle_register_batch()
            return
        if request_path == "/livepocket/login-batch":
            self.handle_login_batch()
            return
        if request_path == "/jump-shop/register-batch":
            self.handle_jump_shop_register_batch()
            return
        if request_path == "/jump-shop/login-batch":
            self.handle_jump_shop_login_batch()
            return
        self.send_json({"success": False, "message": "not found"}, status=404)

    def do_GET(self):
        request_path = urlparse(self.path).path
        if request_path == "/health":
            try:
                worker_health = WORKER_RUNTIME.health() if WORKER_RUNTIME else {"ok": True, "mode": "http"}
                worker_health["flashSaleWorker"] = FLASH_WORKER_RUNTIME.health() if FLASH_WORKER_RUNTIME else {"ok": False, "mode": "disabled"}
                worker_health["livepocketProxy"] = proxy_status()
                self.send_json(worker_health)
            except Exception as exc:
                self.send_json({"ok": False, "message": str(exc)}, status=500)
            return
        if request_path == "/livepocket/lottery-event-info":
            self.handle_lottery_event_info()
            return
        if request_path == "/jump-shop/product-info":
            self.handle_jump_shop_product_info()
            return
        self.send_json({"success": False, "message": "not found"}, status=404)

    def handle_lottery_entry(self):
        try:
            payload = self.read_json_body()
            result = self.executor.execute(payload)
            log(
                "[livepocket.lottery] POST /livepocket/lottery-entry result "
                f"executionId={payload.get('executionId')}, success={result.get('success')}, "
                f"status={result.get('status')}, message={result.get('message')}, "
                f"requestUrl={result.get('requestUrl')}, submitUrl={result.get('submitUrl')}"
            )
            self.send_json(result)
        except Exception as exc:
            log(f"[livepocket.lottery] POST /livepocket/lottery-entry exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_lottery_batch_entry(self):
        try:
            payload = self.read_json_body()
            results = self.executor.execute_batch(payload)
            success_count = sum(1 for item in results if item.get("success"))
            log(
                "[livepocket.lottery] POST /livepocket/lottery-batch-entry result "
                f"batchTaskId={payload.get('batchTaskId')}, accountId={payload.get('accountId')}, "
                f"itemCount={len(results)}, successCount={success_count}"
            )
            self.send_json(
                {
                    "success": success_count > 0,
                    "status": "completed" if results else "failed",
                    "message": f"批量抽票执行完成：{success_count}/{len(results)}",
                    "results": results,
                }
            )
        except Exception as exc:
            log(f"[livepocket.lottery] POST /livepocket/lottery-batch-entry exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_register_batch(self):
        try:
            payload = self.read_json_body()
            batch_id = payload.get("batchId")
            platform_code = str(payload.get("platformCode") or "livepocket").strip()
            count = int(payload.get("count") or 0)
            backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
            if not batch_id:
                self.send_json({"success": False, "status": "failed", "message": "缺少 batchId"}, status=400)
                return
            if count <= 0:
                self.send_json({"success": False, "status": "failed", "message": "注册数量必须大于0"}, status=400)
                return
            thread = threading.Thread(
                target=run_register_batch,
                args=(batch_id, platform_code, count, backend_base_url),
                name=f"livepocket-register-batch-{batch_id}",
                daemon=True,
            )
            thread.start()
            self.send_json({"success": True, "status": "accepted", "message": "注册批次已提交", "batchId": batch_id})
        except Exception as exc:
            log(f"[livepocket.register] POST /livepocket/register-batch exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_login_batch(self):
        try:
            payload = self.read_json_body()
            batch_id = payload.get("batchId")
            platform_code = str(payload.get("platformCode") or "livepocket").strip()
            accounts = payload.get("accounts") if isinstance(payload.get("accounts"), list) else []
            backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
            if not batch_id:
                self.send_json({"success": False, "status": "failed", "message": "缺少 batchId"}, status=400)
                return
            thread = threading.Thread(
                target=run_login_batch,
                args=(batch_id, platform_code, accounts, backend_base_url),
                name=f"livepocket-login-batch-{batch_id}",
                daemon=True,
            )
            thread.start()
            self.send_json({"success": True, "status": "accepted", "message": "登录批次已提交", "batchId": batch_id})
        except Exception as exc:
            log(f"[livepocket.login] POST /livepocket/login-batch exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_jump_shop_register_batch(self):
        try:
            payload = self.read_json_body()
            batch_id = payload.get("batchId")
            platform_code = str(payload.get("platformCode") or "jump-shop").strip()
            count = int(payload.get("count") or 0)
            backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
            if not batch_id:
                self.send_json({"success": False, "status": "failed", "message": "缺少 batchId"}, status=400)
                return
            if count <= 0:
                self.send_json({"success": False, "status": "failed", "message": "注册数量必须大于0"}, status=400)
                return
            thread = threading.Thread(
                target=run_jump_shop_register_batch,
                args=(batch_id, platform_code, count, backend_base_url),
                name=f"jump-shop-register-batch-{batch_id}",
                daemon=True,
            )
            thread.start()
            self.send_json({"success": True, "status": "accepted", "message": "Jump Shop 注册批次已提交", "batchId": batch_id})
        except Exception as exc:
            log(f"[jump-shop.register] POST /jump-shop/register-batch exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_jump_shop_login_batch(self):
        try:
            payload = self.read_json_body()
            batch_id = payload.get("batchId")
            platform_code = str(payload.get("platformCode") or "jump-shop").strip()
            accounts = payload.get("accounts") if isinstance(payload.get("accounts"), list) else []
            backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
            if not batch_id:
                self.send_json({"success": False, "status": "failed", "message": "缺少 batchId"}, status=400)
                return
            thread = threading.Thread(
                target=run_jump_shop_login_batch,
                args=(batch_id, platform_code, accounts, backend_base_url),
                name=f"jump-shop-login-batch-{batch_id}",
                daemon=True,
            )
            thread.start()
            self.send_json({"success": True, "status": "accepted", "message": "Jump Shop 登录批次已提交", "batchId": batch_id})
        except Exception as exc:
            log(f"[jump-shop.login] POST /jump-shop/login-batch exception: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_profile_last_name(self):
        payload: Dict[str, Any] = {}
        try:
            payload = self.read_json_body()
            email = str(payload.get("email") or "").strip()
            password = str(payload.get("password") or payload.get("platformPassword") or "")
            last_name = str(payload.get("lastName") or payload.get("last_name") or "").strip()
            platform_code = str(payload.get("platformCode") or "livepocket").strip() or "livepocket"
            backend_base_url = str(payload.get("backendBaseUrl") or "").strip()
            login_context = payload.get("loginReqData")
            if not email:
                self.send_json({"success": False, "status": "failed", "message": "缺少邮箱"}, status=400)
                return
            if not last_name:
                self.send_json({"success": False, "status": "failed", "message": "姓不能为空"}, status=400)
                return
            result = update_livepocket_last_name(
                email,
                password,
                last_name,
                debug=bool(payload.get("debug")),
                backend_base_url=backend_base_url,
                platform_code=platform_code,
                login_context=login_context,
            )
            ok = bool(result.get("success"))
            log(
                "[livepocket.profile] POST /livepocket/profile-last-name result "
                f"email={email}, backendBaseUrl={backend_base_url or '-'}, platformCode={platform_code}, "
                f"authSource={result.get('authSource') or '-'}, success={result.get('success')}, message={result.get('message')}"
            )
            self.send_json(result, status=200 if ok else 500)
        except Exception as exc:
            log(f"[livepocket.profile] POST /livepocket/profile-last-name exception email={payload.get('email')}: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_login_keepalive(self):
        payload: Dict[str, Any] = {}
        try:
            payload = self.read_json_body()
            email = str(payload.get("email") or "").strip()
            login_context = payload.get("loginReqData")
            if not email:
                self.send_json({"success": False, "status": "failed", "message": "缺少邮箱"}, status=400)
                return
            result = keepalive_livepocket_login(email, login_context=login_context)
            ok = bool(result.get("success"))
            log(
                "[livepocket.keepalive] POST /livepocket/login-keepalive result "
                f"email={email}, loggedIn={result.get('loggedIn')}, success={result.get('success')}, message={result.get('message')}"
            )
            self.send_json(result, status=200 if ok else 500)
        except Exception as exc:
            log(f"[livepocket.keepalive] POST /livepocket/login-keepalive exception email={payload.get('email')}: {exc}")
            self.send_json({"success": False, "status": "failed", "message": f"执行异常：{exc}"}, status=500)

    def handle_lottery_event_info(self):
        try:
            query = parse_qs(urlparse(self.path).query)
            event_url = unquote((query.get("url") or [""])[0])
            result = fetch_lottery_event_info(event_url)
            log(
                "[livepocket.event-info] GET /livepocket/lottery-event-info result "
                f"url={event_url}, sessions={len(result.get('sessions') or [])}, "
                f"ticketEntryUrl={result.get('ticketEntryUrl') or '-'}, "
                f"ticketEntryCandidates={len(result.get('ticketEntryCandidates') or [])}, "
                f"ticketCards={len(result.get('ticketCards') or [])}, cacheHit={result.get('cacheHit')}"
            )
            self.send_json({"success": True, "data": result})
        except Exception as exc:
            log(f"[livepocket.event-info] GET /livepocket/lottery-event-info exception: {exc}")
            self.send_json({"success": False, "message": str(exc)}, status=500)

    def handle_jump_shop_product_info(self):
        try:
            query = parse_qs(urlparse(self.path).query)
            product_url = unquote((query.get("url") or [""])[0])
            result = fetch_jump_shop_product_info(product_url)
            log(
                "[jump-shop.product-info] GET /jump-shop/product-info result "
                f"url={product_url}, available={result.get('available')}, "
                f"variantId={result.get('variantId')}, quantity={result.get('quantity')}"
            )
            self.send_json({"success": True, "data": result})
        except Exception as exc:
            log(f"[jump-shop.product-info] GET /jump-shop/product-info exception: {exc}")
            self.send_json({"success": False, "message": str(exc)}, status=500)

    def read_json_body(self) -> Dict[str, Any]:
        content_length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(content_length).decode("utf-8") if content_length > 0 else ""
        return json.loads(body) if body else {}

    def log_message(self, format: str, *args: Any) -> None:
        log(f"[ticket.http] {self.address_string()} - {format % args}")

    def send_json(self, payload: Dict[str, Any], status: int = 200):
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def start_http_server(host: str, port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), LotteryRequestHandler)
    log(f"Ticket HTTP service listening on {host}:{port}")
    log("Endpoint: POST /livepocket/lottery-entry")
    log("Endpoint: POST /livepocket/lottery-batch-entry")
    log("Endpoint: POST /livepocket/profile-last-name")
    log("Endpoint: POST /livepocket/login-keepalive")
    log("Endpoint: POST /livepocket/register-batch")
    log("Endpoint: POST /livepocket/login-batch")
    log("Endpoint: POST /jump-shop/register-batch")
    log("Endpoint: POST /jump-shop/login-batch")
    log("Endpoint: GET /livepocket/lottery-event-info")
    log("Endpoint: GET /jump-shop/product-info")
    log("Endpoint: GET /health")
    return server


def main():
    global WORKER_RUNTIME, FLASH_WORKER_RUNTIME
    parser = argparse.ArgumentParser(description="LivePocket Python executor")
    parser.add_argument("--host", default=env_text("LOTTERY_HTTP_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=env_int("LOTTERY_HTTP_PORT", 8098))
    parser.add_argument("--workers", type=int, default=None, help="override LOTTERY_WORKERS")
    parser.add_argument("--event-parse-workers", type=int, default=None, help="override LOTTERY_EVENT_PARSE_WORKERS")
    parser.add_argument("--flash-sale-workers", type=int, default=None, help="override FLASH_SALE_WORKERS")
    args = parser.parse_args()

    config = LotteryRedisWorkerConfig()
    if args.workers is not None:
        config.workers = max(args.workers, 1)
    if args.event_parse_workers is not None:
        config.event_parse_workers = max(args.event_parse_workers, 1)
    WORKER_RUNTIME = LotteryRedisWorker(config)
    from livepocket_flash_worker import FlashSaleRedisWorker, FlashSaleRedisWorkerConfig

    flash_config = FlashSaleRedisWorkerConfig()
    if args.flash_sale_workers is not None:
        flash_config.workers = max(args.flash_sale_workers, 1)
    FLASH_WORKER_RUNTIME = FlashSaleRedisWorker(flash_config)

    server = start_http_server(args.host, args.port)
    http_thread = threading.Thread(target=server.serve_forever, name="lottery-http", daemon=True)
    http_thread.start()

    FLASH_WORKER_RUNTIME.start()
    WORKER_RUNTIME.serve_forever()
