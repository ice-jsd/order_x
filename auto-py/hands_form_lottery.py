import html
import re
from typing import Any, Dict, Optional
from urllib.parse import urlsplit, urlunsplit

import requests

from ticket_runtime import get_logger
from livepocket_proxy import apply_proxy_to_session, get_proxy, mark_proxy_failed, proxy_max_attempts


LOGGER = get_logger("hands-form.parser")

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/147.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
}


def _log(message: str) -> None:
    LOGGER.info(message)


def normalize_hands_event_url(event_url: str) -> str:
    parts = urlsplit(str(event_url or "").strip())
    if not parts.scheme or not parts.netloc:
        raise ValueError("Hands 活动链接无效")
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


def _clean_text(value: str) -> str:
    text = html.unescape(re.sub(r"<[^>]+>", " ", value or ""))
    return re.sub(r"\s+", " ", text).strip()


def _match_group(pattern: str, text: str) -> str:
    match = re.search(pattern, text, flags=re.I | re.S)
    return _clean_text(match.group(1)) if match else ""


def _extract_table_value(text: str, header_text: str) -> str:
    patterns = [
        rf"<th[^>]*>\s*{re.escape(header_text)}\s*</th>\s*<td[^>]*>(.*?)</td>",
        rf"<dt[^>]*>\s*{re.escape(header_text)}\s*</dt>\s*<dd[^>]*>(.*?)</dd>",
    ]
    for pattern in patterns:
        value = _match_group(pattern, text)
        if value:
            return value
    return ""


def _extract_event_title(text: str) -> str:
    for pattern in (
        r"<h2[^>]*>(.*?)</h2>",
        r"<h1[^>]*>(.*?)</h1>",
        r"<title[^>]*>(.*?)</title>",
    ):
        value = _match_group(pattern, text)
        if value:
            return value
    return "Hands 抽票活动"


def _extract_store_name(text: str) -> str:
    for pattern in (
        r"<h1[^>]*>.*?<[^>]+>(.*?)</[^>]+>\s*</h1>",
        r"<h3[^>]*>(.*?)</h3>",
    ):
        value = _match_group(pattern, text)
        if value:
            return value
    return ""


def _segment_id_from_url(event_url: str) -> str:
    match = re.search(r"/segment/(\d+)", event_url)
    return match.group(1) if match else "unknown"


def fetch_hands_event_info(event_url: str, session: Optional[requests.Session] = None) -> Dict[str, Any]:
    normalized_url = normalize_hands_event_url(event_url)
    client = session or requests.Session()
    client.headers.update(DEFAULT_HEADERS)

    proxy_key = f"hands-event-info:{normalized_url}"
    response: Optional[requests.Response] = None
    last_error: Optional[Exception] = None
    max_attempts = proxy_max_attempts()

    for attempt in range(1, max_attempts + 1):
        proxy = get_proxy(proxy_key, refresh=attempt > 1, platform_code="hands-form")
        apply_proxy_to_session(client, proxy)
        try:
            response = client.get(normalized_url, timeout=30)
            response.raise_for_status()
            break
        except Exception as exc:
            last_error = exc
            if proxy:
                mark_proxy_failed(proxy, reason=f"Hands 活动解析请求异常: {exc}", key=proxy_key, platform_code="hands-form")
            if attempt >= max_attempts:
                raise
            _log(f"[hands-form] 活动解析代理重试 {attempt}/{max_attempts}: {exc}")

    if response is None:
        raise RuntimeError(f"Hands 活动解析失败: {last_error}")

    page_text = response.text or ""
    event_title = _extract_event_title(page_text)
    store_name = _extract_store_name(page_text)
    winner_visit_time = _extract_table_value(page_text, "当選者来店日時")
    sales_location = _extract_table_value(page_text, "販売場所")
    segment_id = _segment_id_from_url(normalized_url)
    session_id = f"segment:{segment_id}"

    if not winner_visit_time:
        winner_visit_time = "待页面确认"

    raw_summary_parts = []
    if winner_visit_time:
        raw_summary_parts.append(f"当選者来店日時: {winner_visit_time}")
    if sales_location:
        raw_summary_parts.append(f"販売場所: {sales_location}")
    raw_summary = " / ".join(raw_summary_parts)

    reception_title = sales_location or store_name or "Hands 事前Web抽選"

    return {
        "eventUrl": normalized_url,
        "ticketEntryUrl": normalized_url,
        "eventTitle": event_title,
        "entryStartTime": "",
        "entryEndTime": "",
        "rawSummary": raw_summary,
        "sessions": [
            {
                "sessionId": session_id,
                "sessionLabel": winner_visit_time,
                "eventUrl": normalized_url,
                "receptionId": session_id,
                "ticketId": "",
                "ticketField": "",
                "receptionTitle": reception_title,
                "salesType": "抽選",
                "notes": "hands-form synthetic session",
            }
        ],
    }
