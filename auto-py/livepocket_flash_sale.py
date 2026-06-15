"""
LivePocket 普通抢票快速路径。

只处理先着/普通抢票：优先直接从 tickets 页开始；缺少 tickets 链接时才从活动页发现第一个可购入口。
"""
import html
import json
import re
import sys
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urljoin, urlparse

import requests

from livepocket_purchase import login_livepocket as submit_order_login_livepocket
from livepocket_session import (
    export_session_login_context,
    parse_json_maybe,
    resolve_authenticated_session,
    restore_session_from_login_context,
)
from ticket_runtime import get_logger


LOGGER = get_logger("livepocket.flash_sale")

LIVEPOCKET_BASE_URL = "https://livepocket.jp"
PURCHASE_CONFIRM_URL = "https://livepocket.jp/purchase/confirm"
PURCHASE_URL = "https://livepocket.jp/purchase"


def safe_console_text(value: Any) -> str:
    encoding = getattr(sys.stdout, "encoding", None) or "utf-8"
    return str(value).encode(encoding, errors="backslashreplace").decode(encoding, errors="replace")


def log(message: str) -> None:
    LOGGER.print(safe_console_text(message), flush=True)


def compact_text(value: str, limit: int = 1200) -> str:
    text = re.sub(r"\s+", " ", value or "").strip()
    return text if len(text) <= limit else text[:limit] + "..."


def visible_text(page_text: str) -> str:
    text = re.sub(r"<script\b.*?</script>", " ", page_text or "", flags=re.I | re.S)
    text = re.sub(r"<style\b.*?</style>", " ", text, flags=re.I | re.S)
    return html.unescape(re.sub(r"<[^>]+>", " ", text))


def response_preview(page_text: str, limit: int = 700) -> str:
    return compact_text(visible_text(page_text), limit) or compact_text(page_text, limit)


def parse_attrs(tag: str) -> Dict[str, str]:
    attrs: Dict[str, str] = {}
    for match in re.finditer(r"([\w:-]+)\s*=\s*(['\"])(.*?)\2", tag or "", flags=re.S):
        attrs[match.group(1).lower()] = html.unescape(match.group(3))
    return attrs


def extract_input_value(page_text: str, input_name: str) -> Optional[str]:
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.I | re.S):
        attrs = parse_attrs(match.group(0))
        if attrs.get("name") == input_name:
            return attrs.get("value", "")
    return None


def extract_hidden_inputs(page_text: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.I | re.S):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        name = attrs.get("name")
        if not name:
            continue
        input_type = attrs.get("type", "").lower()
        checked_checkbox = input_type == "checkbox" and re.search(r"\bchecked\b", tag, flags=re.I)
        if input_type in ("hidden", "submit") or name in ("authenticity_token", "_method") or checked_checkbox:
            values[name] = attrs.get("value", "")
    return values


def extract_csrf_token(page_text: str) -> Optional[str]:
    match = re.search(
        r"<meta\b(?=[^>]*\bname=['\"]csrf-token['\"])(?=[^>]*\bcontent=['\"]([^'\"]+)['\"])",
        page_text or "",
        flags=re.I | re.S,
    )
    return html.unescape(match.group(1)) if match else None


def extract_page_title(page_text: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", page_text or "", flags=re.I | re.S)
    return compact_text(html.unescape(match.group(1)), 160) if match else ""


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


def normalize_tickets_url(value: Any) -> str:
    raw = html.unescape(str(value or "")).strip().strip("'\"")
    raw = raw.replace("　", "").replace("\r", "").replace("\n", "")
    if not raw:
        return ""
    if raw.startswith("//"):
        raw = "https:" + raw
    if raw.startswith("/"):
        raw = urljoin(LIVEPOCKET_BASE_URL, raw)
    if not re.match(r"^https?://", raw, flags=re.I):
        raw = "https://" + raw.lstrip("/")
    parsed = urlparse(raw)
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    if host == "t.livepocket.jp":
        parsed = parsed._replace(netloc="livepocket.jp")
    return parsed.geturl()


def looks_like_tickets_url(value: Any) -> bool:
    parsed = urlparse(str(value or ""))
    return parsed.netloc.lower().endswith("livepocket.jp") and re.search(r"/receptions/\d+/tickets/?$", parsed.path or "") is not None


def normalize_event_url(value: Any) -> str:
    raw = normalize_tickets_url(value)
    parsed = urlparse(raw)
    if not parsed.netloc.lower().endswith("livepocket.jp"):
        return raw
    match = re.match(r"^(/e/[^/?#]+)", parsed.path or "")
    if not match:
        return raw
    return parsed._replace(path=match.group(1), query="", fragment="").geturl()


def looks_like_event_url(value: Any) -> bool:
    parsed = urlparse(str(value or ""))
    return parsed.netloc.lower().endswith("livepocket.jp") and re.match(r"^/e/[^/?#]+/?$", parsed.path or "") is not None


def select_seat_url_from_tickets_url(tickets_url: str) -> str:
    parsed = urlparse(tickets_url)
    if parsed.path.endswith("/tickets"):
        return parsed._replace(path=parsed.path[: -len("/tickets")] + "/select_seat", query="", fragment="").geturl()
    return urljoin(tickets_url, "select_seat")


def extract_select_seat_url(tickets_html: str, tickets_url: str) -> str:
    match = re.search(r"<form\b[^>]*\baction=(['\"])(.*?)\1", tickets_html or "", flags=re.I | re.S)
    if match and "select_seat" in match.group(2):
        return urljoin(LIVEPOCKET_BASE_URL, html.unescape(match.group(2)))
    return select_seat_url_from_tickets_url(tickets_url)


def parse_confirm_params_from_url(url: str) -> Tuple[Optional[str], Optional[str]]:
    query = parse_qs(urlparse(url or "").query)
    return (query.get("id") or [None])[0], (query.get("reserve_id") or [None])[0]


def parse_confirm_url_from_html(page_text: str) -> Optional[str]:
    for pattern in [
        r"['\"]([^'\"]*/purchase/confirm\?[^'\"]+)['\"]",
        r"href=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
        r"action=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
    ]:
        match = re.search(pattern, page_text or "", flags=re.I)
        if match:
            return urljoin(LIVEPOCKET_BASE_URL, html.unescape(match.group(1)))
    return None


def extract_ticket_entry_url_from_event_page(event_html: str, event_url: str) -> str:
    candidates: List[Tuple[str, str]] = []
    unavailable_words = ("受付終了", "販売終了", "予定枚数終了", "売切", "売り切れ", "sold out")
    purchase_words = ("チケットを購入", "購入", "申込", "予約")
    seen_urls: set[str] = set()
    for match in re.finditer(r"<a\b[^>]*\bhref=(['\"])(.*?)\1[^>]*>", event_html or "", flags=re.I | re.S):
        tag = match.group(0)
        href = html.unescape(match.group(2))
        if "/receptions/" not in href or "/tickets" not in href:
            continue
        text = compact_text(visible_text(tag), 260)
        if any(word.lower() in text.lower() for word in unavailable_words):
            continue
        candidate_url = normalize_tickets_url(urljoin(event_url or LIVEPOCKET_BASE_URL, href))
        if looks_like_tickets_url(candidate_url) and candidate_url not in seen_urls:
            seen_urls.add(candidate_url)
            candidates.append((candidate_url, text))
    for match in re.finditer(r"['\"]([^'\"]*/receptions/\d+/tickets/?(?:\?[^'\"]*)?)['\"]", event_html or "", flags=re.I):
        candidate_url = normalize_tickets_url(urljoin(event_url or LIVEPOCKET_BASE_URL, html.unescape(match.group(1))))
        if looks_like_tickets_url(candidate_url) and candidate_url not in seen_urls:
            seen_urls.add(candidate_url)
            candidates.append((candidate_url, ""))
    if not candidates:
        return ""
    for url, text in candidates:
        if any(word in text for word in purchase_words):
            return url
    return candidates[0][0]


def resolve_platform_password(payload: Dict[str, Any], task_options: Optional[Dict[str, Any]] = None) -> str:
    options = task_options or parse_json_maybe(payload.get("taskOptions"))
    account_info = parse_json_maybe(payload.get("accountInfo"))
    return str(
        payload.get("password")
        or payload.get("platformPassword")
        or account_info.get("platformPassword")
        or account_info.get("password")
        or options.get("password")
        or options.get("platformPassword")
        or ""
    )


def is_account_reauth_url(value: str) -> bool:
    return urlparse(value or "").path.rstrip("/") == "/account/reauth"


def extract_form_action(page_text: str, fallback_url: str, action_hint: str = "reauth") -> str:
    first_action = ""
    for match in re.finditer(r"<form\b[^>]*>", page_text or "", flags=re.I | re.S):
        attrs = parse_attrs(match.group(0))
        action = html.unescape(attrs.get("action", "")).strip()
        if not action:
            continue
        action_url = urljoin(LIVEPOCKET_BASE_URL, action)
        if not first_action:
            first_action = action_url
        if action_hint in action_url:
            return action_url
    return first_action or fallback_url


def extract_password_field_names(page_text: str) -> List[str]:
    names: List[str] = []
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.I | re.S):
        attrs = parse_attrs(match.group(0))
        if attrs.get("type", "").lower() == "password" and attrs.get("name"):
            names.append(attrs["name"])
    return names


def fill_reauth_identity_fields(form_data: Dict[str, str], page_text: str, email: str, password: str) -> None:
    for name in extract_password_field_names(page_text):
        form_data[name] = password
    if not email:
        return
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.I | re.S):
        attrs = parse_attrs(match.group(0))
        name = attrs.get("name", "")
        input_type = attrs.get("type", "").lower()
        if name and ("email" in name.lower() or input_type == "email") and not form_data.get(name):
            form_data[name] = email


def cookie_names_from_session(session: requests.Session) -> List[str]:
    return sorted({cookie.name for cookie in session.cookies})


def cookie_names_from_response(response: requests.Response) -> List[str]:
    return sorted({cookie.name for cookie in response.cookies})


def normalize_response_cookies(session: requests.Session, response: requests.Response) -> None:
    response_cookies = list(response.cookies)
    if not response_cookies:
        return
    for cookie in response_cookies:
        for existing in list(session.cookies):
            if existing.name == cookie.name and "livepocket.jp" in (existing.domain or ""):
                try:
                    session.cookies.clear(existing.domain, existing.path, existing.name)
                except (KeyError, ValueError):
                    pass
        session.cookies.set(
            cookie.name,
            cookie.value,
            domain=cookie.domain or ".livepocket.jp",
            path=cookie.path or "/",
        )


def complete_account_reauth(
    session: requests.Session,
    reauth_url: str,
    password: str,
    referer: str,
    trace_id: str,
    email: str = "",
) -> Tuple[bool, str, Optional[requests.Response]]:
    if not password:
        return False, "LivePocket 要求 account/reauth，但账号缺少平台密码，无法自动再认证", None
    log(f"[flash-sale] REAUTH GET trace={trace_id}: {reauth_url}")
    get_resp = session.get(
        reauth_url,
        headers=request_headers(referer=referer),
        allow_redirects=False,
        timeout=30,
    )
    get_text = get_resp.text or ""
    get_location = urljoin(LIVEPOCKET_BASE_URL, get_resp.headers.get("Location", "")) if get_resp.headers.get("Location") else ""
    log(
        "[flash-sale] REAUTH page "
        f"trace={trace_id}, status={get_resp.status_code}, location={get_location or '-'}, "
        f"bytes={len(get_text)}, title={extract_page_title(get_text) or '-'}"
    )
    if get_resp.status_code in (301, 302, 303, 307, 308):
        return False, f"LivePocket account/reauth 页面被重定向: {get_location or get_resp.headers.get('Location', '-')}", get_resp
    if get_resp.status_code >= 400:
        return False, f"LivePocket account/reauth 页面访问失败: HTTP {get_resp.status_code}", get_resp

    form_data = extract_hidden_inputs(get_text)
    if not extract_password_field_names(get_text):
        return False, "LivePocket account/reauth 页面未找到密码输入框", get_resp
    fill_reauth_identity_fields(form_data, get_text, email, password)
    post_url = extract_form_action(get_text, reauth_url)
    log(f"[flash-sale] REAUTH POST trace={trace_id}: {post_url}")
    post_resp = session.post(
        post_url,
        data=form_data,
        headers={
            **request_headers(referer=reauth_url),
            "Content-Type": "application/x-www-form-urlencoded",
        },
        allow_redirects=False,
        timeout=30,
    )
    normalize_response_cookies(session, post_resp)
    post_text = post_resp.text or ""
    post_location = urljoin(LIVEPOCKET_BASE_URL, post_resp.headers.get("Location", "")) if post_resp.headers.get("Location") else ""
    log(
        "[flash-sale] REAUTH response "
        f"trace={trace_id}, status={post_resp.status_code}, location={post_location or '-'}, "
        f"bytes={len(post_text)}, title={extract_page_title(post_text) or '-'}, "
        f"setCookies={cookie_names_from_response(post_resp) or '-'}, sessionCookies={cookie_names_from_session(session)[:12]}"
    )
    if post_resp.status_code in (301, 302, 303, 307, 308):
        if not post_location:
            return True, "reauth redirected", post_resp
        follow_resp = session.get(
            post_location,
            headers=request_headers(referer=reauth_url),
            allow_redirects=False,
            timeout=30,
        )
        follow_text = follow_resp.text or ""
        follow_location = urljoin(LIVEPOCKET_BASE_URL, follow_resp.headers.get("Location", "")) if follow_resp.headers.get("Location") else ""
        log(
            "[flash-sale] REAUTH follow "
            f"trace={trace_id}, status={follow_resp.status_code}, location={follow_location or '-'}, "
            f"bytes={len(follow_text)}, title={extract_page_title(follow_text) or '-'}"
        )
        if follow_resp.status_code in (301, 302, 303, 307, 308) and is_account_reauth_url(follow_location):
            return False, "LivePocket account/reauth 后仍被要求再认证", follow_resp
        return True, post_location, follow_resp
    errors = extract_livepocket_errors(post_text)
    if errors:
        return False, "LivePocket account/reauth 失败: " + "; ".join(errors), post_resp
    return post_resp.status_code < 400, "reauth submitted", post_resp


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
        for match in re.finditer(pattern, page_text or "", flags=re.I | re.S):
            message = compact_text(visible_text(match.group(1)), 500)
            if message and message not in errors:
                errors.append(message)
    return errors


def parse_quantity_value(value: Any) -> int:
    try:
        return int(str(value or "").strip())
    except ValueError:
        return 0


def parse_ticket_options(page_text: str) -> List[Dict[str, Any]]:
    options: List[Dict[str, Any]] = []
    seen = set()
    for match in re.finditer(r"<select\b.*?</select>", page_text or "", flags=re.I | re.S):
        block = match.group(0)
        open_tag_match = re.search(r"<select\b[^>]*>", block, flags=re.I | re.S)
        if not open_tag_match:
            continue
        attrs = parse_attrs(open_tag_match.group(0))
        field_name = attrs.get("name", "")
        ticket_match = re.search(r"tickets\[(\d+)\]", field_name)
        if not ticket_match:
            continue
        values: List[str] = []
        for option_match in re.finditer(r"<option\b[^>]*>(.*?)</option>", block, flags=re.I | re.S):
            option_tag = option_match.group(0)
            if re.search(r"\bdisabled\b", option_tag, flags=re.I):
                continue
            option_attrs = parse_attrs(option_tag)
            value = option_attrs.get("value", compact_text(visible_text(option_match.group(1)), 20))
            if value != "":
                values.append(value)
        options.append(
            {
                "fieldName": field_name,
                "ticketId": ticket_match.group(1),
                "values": values,
                "label": compact_text(visible_text(page_text[max(0, match.start() - 800) : match.end() + 400]), 220),
                "disabled": bool(re.search(r"\bdisabled\b", open_tag_match.group(0), flags=re.I)),
            }
        )
        seen.add(field_name)

    for match in re.finditer(r"<input\b[^>]*\bname=(['\"])(tickets\[(\d+)\])\1[^>]*>", page_text or "", flags=re.I | re.S):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        field_name = attrs.get("name") or match.group(2)
        if field_name in seen or re.search(r"\bdisabled\b", tag, flags=re.I):
            continue
        options.append(
            {
                "fieldName": field_name,
                "ticketId": match.group(3),
                "values": [attrs.get("value", "1") or "1"],
                "label": "",
                "disabled": False,
            }
        )
    return options


def choose_ticket_option(options: List[Dict[str, Any]], task_options: Dict[str, Any], payload: Dict[str, Any]) -> Tuple[Optional[Dict[str, Any]], str]:
    ticket_field = str(
        payload.get("ticketField")
        or payload.get("sessionTicketField")
        or task_options.get("ticketField")
        or task_options.get("sessionTicketField")
        or ""
    )
    ticket_id = str(
        payload.get("ticketId")
        or payload.get("sessionTicketId")
        or task_options.get("ticketId")
        or task_options.get("sessionTicketId")
        or ""
    )
    if ticket_field:
        for option in options:
            if str(option.get("fieldName") or "") == ticket_field:
                return option, "ticketField"
    if ticket_id:
        for option in options:
            if str(option.get("ticketId") or "") == ticket_id:
                return option, "ticketId"
    enabled = [item for item in options if not item.get("disabled")]
    if enabled:
        return enabled[0], "firstEnabled"
    return None, "未找到可用票种"


def resolve_quantity(selected: Dict[str, Any], task_options: Dict[str, Any], payload: Dict[str, Any]) -> str:
    quantity_mode = str(task_options.get("quantityMode") or payload.get("quantityMode") or "").strip().lower()
    values = [str(item) for item in selected.get("values") or []]
    positive = sorted({parse_quantity_value(item) for item in values if parse_quantity_value(item) > 0})
    if quantity_mode == "auto_max":
        if not positive:
            raise ValueError("目标票种当前没有可用数量")
        return str(positive[-1])
    quantity = str(task_options.get("entryQuantity") or task_options.get("purchaseQuantity") or payload.get("purchaseQuantity") or 1)
    if values and quantity not in values:
        raise ValueError(f"目标票种不支持数量 {quantity}")
    return quantity


def extract_radio_options(page_text: str, input_name: str) -> List[Dict[str, Any]]:
    options: List[Dict[str, Any]] = []
    for match in re.finditer(r"<input\b[^>]*>", page_text or "", flags=re.I | re.S):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        if attrs.get("type", "").lower() != "radio" or attrs.get("name") != input_name:
            continue
        label_start = (page_text or "").rfind("<label", 0, match.start())
        label_end = (page_text or "").find("</label>", match.end())
        label_html = (page_text or "")[label_start : label_end + len("</label>")] if label_start >= 0 and label_end >= match.end() else tag
        options.append(
            {
                "value": attrs.get("value", ""),
                "checked": bool(re.search(r"\bchecked\b", tag, flags=re.I)),
                "label": compact_text(visible_text(label_html), 180),
            }
        )
    return options


def select_has_option(page_text: str, select_name: str, option_value: str) -> bool:
    for match in re.finditer(r"<select\b[^>]*>(?P<body>.*?)</select>", page_text or "", flags=re.I | re.S):
        attrs = parse_attrs(match.group(0))
        if attrs.get("name") != select_name:
            continue
        for option_match in re.finditer(r"<option\b[^>]*>", match.group("body"), flags=re.I | re.S):
            if parse_attrs(option_match.group(0)).get("value") == option_value:
                return True
    return False


def apply_text_questionnaire_answers(form_data: Dict[str, str], confirm_text: str, default_answer: str = "ない") -> int:
    applied = 0
    allowed_input_types = {"", "text", "search", "email", "tel", "url", "number"}
    for match in re.finditer(r"<textarea\b[^>]*>(.*?)</textarea>", confirm_text or "", flags=re.I | re.S):
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

    for match in re.finditer(r"<input\b[^>]*>", confirm_text or "", flags=re.I | re.S):
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
    for match in re.finditer(r"<input\b[^>]*>", confirm_text or "", flags=re.I | re.S):
        tag = match.group(0)
        attrs = parse_attrs(tag)
        name = attrs.get("name", "")
        if attrs.get("type", "").lower() != "radio" or not name.startswith("questionnaire_answers["):
            continue
        label_start = (confirm_text or "").rfind("<label", 0, match.start())
        label_end = (confirm_text or "").find("</label>", match.end())
        label_html = (confirm_text or "")[label_start : label_end + len("</label>")] if label_start >= 0 and label_end >= match.end() else tag
        grouped.setdefault(name, []).append(
            {
                "value": attrs.get("value", ""),
                "label": compact_text(visible_text(label_html), 180),
                "checked": bool(re.search(r"\bchecked\b", tag, flags=re.I)),
            }
        )

    applied = 0
    for name, options in grouped.items():
        if form_data.get(name):
            continue
        adult = next(
            (
                item
                for item in options
                if ("成人済" in item.get("label", "") or "成人" in item.get("label", ""))
                and "未成年" not in item.get("label", "")
            ),
            None,
        )
        checked = next((item for item in options if item.get("checked") and item.get("value")), None)
        chosen = adult if adult and adult.get("value") else checked
        if chosen and chosen.get("value"):
            form_data[name] = str(chosen["value"])
            applied += 1
    return applied


def build_purchase_form(confirm_text: str, event_id: str, reserve_id: str) -> Tuple[Dict[str, str], Dict[str, Any]]:
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
            raise ValueError("确认页未提供便利店支付选项，无法自动提交")
        form_data["order_form[payment_method]"] = "cvs"
        if not select_has_option(confirm_text, "order_form[sbps_web_cvs_type]", "002"):
            raise ValueError("确认页未提供 Lawson 便利店支付选项")
        form_data["order_form[sbps_web_cvs_type]"] = "002"
        payment_status = "offline_pending"
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


def login_livepocket_runtime(email: str, password: str, platform_code: str, trace_id: str, backend_base_url: str = "") -> Optional[requests.Session]:
    if not email or not password:
        return None
    log(f"[flash-sale] LOGIN runtime start trace={trace_id}, email={email}")
    try:
        session = submit_order_login_livepocket(
            email,
            password,
            debug=False,
            backend_base_url=backend_base_url,
            platform_code=platform_code,
        )
    except Exception as exc:
        log(f"[flash-sale] LOGIN runtime exception trace={trace_id}, email={email}, error={exc}")
        return None
    if session:
        log(f"[flash-sale] LOGIN runtime success trace={trace_id}, email={email}")
    return session


def prepare_authenticated_session(
    payload: Dict[str, Any],
    trace_id: str,
    verify_login_state: bool = True,
) -> Tuple[Optional[requests.Session], str, Dict[str, Any], str]:
    task_options = parse_json_maybe(payload.get("taskOptions"))
    login_context = parse_json_maybe(payload.get("loginReqData"))
    email = str(payload.get("email") or task_options.get("email") or "")
    password = resolve_platform_password(payload, task_options)
    platform_code = str(payload.get("platformCode") or task_options.get("platformCode") or "livepocket")
    backend_base_url = str(payload.get("backendBaseUrl") or task_options.get("backendBaseUrl") or "").strip()
    if not verify_login_state:
        if login_context:
            try:
                session = restore_session_from_login_context(login_context, proxy_key=email)
                log(f"[flash-sale] LOGIN context restored trace={trace_id}, authSource=context, email={email}, check=skipped")
                return session, "context", {"loggedIn": None, "skippedCheck": True}, ""
            except Exception as exc:
                log(f"[flash-sale] LOGIN context restore failed trace={trace_id}, authSource=context, email={email}, error={exc}")
        if not password:
            return None, "none", {}, "账号缺少密码，且登录上下文不可用"
        session = login_livepocket_runtime(email, password, platform_code, trace_id, backend_base_url)
        if not session:
            return None, "runtime-login", {}, "现场登录失败"
        return session, "runtime-login", {"loggedIn": None, "skippedCheck": True}, ""
    return resolve_authenticated_session(
        email=email,
        password=password,
        login_context=login_context,
        login_func=lambda: login_livepocket_runtime(email, password, platform_code, trace_id, backend_base_url),
        logger=log,
        trace_id=trace_id,
        flow_label="flash-sale",
        request_headers=request_headers,
    )


def prewarm_flash_sale(payload: Dict[str, Any]) -> Dict[str, Any]:
    trace_id = str(payload.get("executionId") or uuid.uuid4())[:36]
    session, auth_source, login_state, auth_error = prepare_authenticated_session(payload, trace_id)
    if not session:
        return {
            "success": False,
            "status": "warmup_failed",
            "message": auth_error or "登录预热失败",
            "authSource": auth_source,
            "loginState": login_state,
        }
    reauth_state = prewarm_ticket_reauth(payload, session, trace_id)
    return {
        "success": True,
        "status": "warmed",
        "message": "登录预热完成",
        "authSource": auth_source,
        "loginReqData": json.dumps(export_session_login_context(session), ensure_ascii=False),
        "loginState": login_state,
        "reauthState": reauth_state,
    }


def fail_result(
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


def resolve_configured_tickets_url(payload: Dict[str, Any], task_options: Dict[str, Any]) -> str:
    configured_tickets_url = (
        payload.get("ticketEntryUrl")
        or task_options.get("ticketEntryUrl")
        or task_options.get("ticketsUrl")
        or task_options.get("lotteryTicketsUrl")
    )
    return normalize_tickets_url(configured_tickets_url)


def resolve_configured_event_url(payload: Dict[str, Any], task_options: Dict[str, Any]) -> str:
    for value in (
        task_options.get("eventUrl"),
        task_options.get("lotteryEntryUrl"),
        task_options.get("lotteryEventUrl"),
        payload.get("eventUrl"),
        payload.get("lotteryEventUrl"),
    ):
        event_url = normalize_event_url(value)
        if looks_like_event_url(event_url):
            return event_url
    return ""


def discover_tickets_url_from_event_page(session: requests.Session, event_url: str, trace_id: str) -> Tuple[str, str, str]:
    if not looks_like_event_url(event_url):
        return "", event_url, "普通抢票缺少有效 tickets 链接"
    log(f"[flash-sale] GET event page trace={trace_id}: {event_url}")
    event_resp = session.get(
        event_url,
        headers=request_headers(referer=LIVEPOCKET_BASE_URL + "/"),
        allow_redirects=True,
        timeout=30,
    )
    event_text = event_resp.text or ""
    final_event_url = normalize_event_url(event_resp.url or event_url)
    log(
        "[flash-sale] EVENT response "
        f"trace={trace_id}, status={event_resp.status_code}, finalUrl={final_event_url}, "
        f"bytes={len(event_text)}, title={extract_page_title(event_text) or '-'}"
    )
    if event_resp.status_code >= 400:
        return "", final_event_url, f"普通抢票活动页访问失败: HTTP {event_resp.status_code}"
    tickets_url = normalize_tickets_url(extract_ticket_entry_url_from_event_page(event_text, final_event_url))
    if not looks_like_tickets_url(tickets_url):
        return "", final_event_url, "未从 LivePocket 活动页解析到 tickets 链接"
    log(f"[flash-sale] EVENT ticketEntryUrl trace={trace_id}, source=event-page, ticketEntryUrl={tickets_url}")
    return tickets_url, final_event_url, ""


def resolve_flash_sale_tickets_url(
    session: requests.Session,
    payload: Dict[str, Any],
    task_options: Dict[str, Any],
    trace_id: str,
) -> Tuple[str, str, str, str]:
    tickets_url = resolve_configured_tickets_url(payload, task_options)
    if looks_like_tickets_url(tickets_url):
        return tickets_url, resolve_configured_event_url(payload, task_options), "direct", ""
    event_url = resolve_configured_event_url(payload, task_options)
    tickets_url, final_event_url, error = discover_tickets_url_from_event_page(session, event_url, trace_id)
    if not tickets_url:
        return "", final_event_url or event_url, "event-page", error
    return tickets_url, final_event_url, "event-page", ""


def prewarm_ticket_reauth(payload: Dict[str, Any], session: requests.Session, trace_id: str) -> Dict[str, Any]:
    task_options = parse_json_maybe(payload.get("taskOptions"))
    tickets_url, event_url, source, error = resolve_flash_sale_tickets_url(session, payload, task_options, trace_id)
    if not looks_like_tickets_url(tickets_url):
        return {"reauthHandled": False, "reauthMessage": error or "普通抢票缺少有效 tickets 链接"}
    task_options["ticketEntryUrl"] = tickets_url
    task_options["lotteryEntryUrl"] = tickets_url
    if event_url:
        task_options["eventUrl"] = event_url
    payload["ticketEntryUrl"] = tickets_url
    payload["taskOptions"] = json.dumps(task_options, ensure_ascii=False)
    referer = event_url or str(task_options.get("eventUrl") or LIVEPOCKET_BASE_URL + "/")
    log(f"[flash-sale] PREWARM tickets reauth probe trace={trace_id}: {tickets_url}")
    response = session.get(
        tickets_url,
        headers=request_headers(
            accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            referer=referer,
        ),
        allow_redirects=False,
        timeout=30,
    )
    location = response.headers.get("Location", "")
    redirect_url = urljoin(LIVEPOCKET_BASE_URL, location) if location else ""
    log(
        "[flash-sale] PREWARM tickets probe "
        f"trace={trace_id}, status={response.status_code}, location={redirect_url or '-'}, bytes={len(response.text or '')}"
    )
    if response.status_code in (301, 302, 303, 307, 308) and is_account_reauth_url(redirect_url):
        password = resolve_platform_password(payload, task_options)
        ok, message, _ = complete_account_reauth(
            session=session,
            reauth_url=redirect_url,
            password=password,
            referer=tickets_url,
            trace_id=trace_id,
            email=str(payload.get("email") or task_options.get("email") or ""),
        )
        return {"reauthHandled": ok, "reauthMessage": message, "ticketEntryUrl": tickets_url, "source": source}
    return {"reauthHandled": False, "reauthMessage": redirect_url or f"HTTP {response.status_code}", "ticketEntryUrl": tickets_url, "source": source}


def execute_flash_sale(payload: Dict[str, Any]) -> Dict[str, Any]:
    trace_id = str(payload.get("executionId") or uuid.uuid4())[:36]
    task_options = parse_json_maybe(payload.get("taskOptions"))
    configured_tickets_url = resolve_configured_tickets_url(payload, task_options)
    configured_event_url = resolve_configured_event_url(payload, task_options)
    if not looks_like_tickets_url(configured_tickets_url) and not looks_like_event_url(configured_event_url):
        return fail_result("普通抢票缺少有效 tickets 链接或 LivePocket 活动页链接", request_url=configured_tickets_url or configured_event_url)

    session, auth_source, login_state, auth_error = prepare_authenticated_session(payload, trace_id, verify_login_state=False)
    if not session:
        return fail_result(auth_error or "账号登录态不可用，且现场登录失败", extra={"authSource": auth_source, "loginState": login_state})
    refreshed_login_context = export_session_login_context(session)

    tickets_url, event_url, ticket_source, ticket_error = resolve_flash_sale_tickets_url(session, payload, task_options, trace_id)
    if not looks_like_tickets_url(tickets_url):
        return fail_result(
            ticket_error or "未从 LivePocket 活动页解析到 tickets 链接",
            request_url=configured_tickets_url or configured_event_url,
            extra={"authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )
    log(f"[flash-sale] TICKET source trace={trace_id}, source={ticket_source}, ticketEntryUrl={tickets_url}")
    referer = event_url or str(task_options.get("eventUrl") or LIVEPOCKET_BASE_URL + "/")
    log(f"[flash-sale] GET tickets trace={trace_id}: {tickets_url}")
    tickets_resp = session.get(
        tickets_url,
        headers=request_headers(
            accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            referer=referer,
        ),
        allow_redirects=False,
        timeout=30,
    )
    tickets_text = tickets_resp.text or ""
    tickets_location = tickets_resp.headers.get("Location", "")
    tickets_redirect = urljoin(LIVEPOCKET_BASE_URL, tickets_location) if tickets_location else ""
    log(
        "[flash-sale] TICKETS response "
        f"trace={trace_id}, status={tickets_resp.status_code}, location={tickets_redirect or '-'}, "
        f"bytes={len(tickets_text)}, title={extract_page_title(tickets_text) or '-'}"
    )
    if tickets_resp.status_code in (301, 302, 303, 307, 308) and is_account_reauth_url(tickets_redirect):
        password = resolve_platform_password(payload, task_options)
        ok, reauth_message, reauth_follow_resp = complete_account_reauth(
            session=session,
            reauth_url=tickets_redirect,
            password=password,
            referer=tickets_url,
            trace_id=trace_id,
            email=str(payload.get("email") or task_options.get("email") or ""),
        )
        refreshed_login_context = export_session_login_context(session)
        if not ok:
            return fail_result(
                reauth_message,
                http_status=tickets_resp.status_code,
                raw_result=response_preview(tickets_text),
                request_url=tickets_url,
                submit_url=tickets_redirect,
                extra={"authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
            )
        if reauth_follow_resp is not None:
            tickets_resp = reauth_follow_resp
            tickets_text = tickets_resp.text or ""
            tickets_location = tickets_resp.headers.get("Location", "")
            tickets_redirect = urljoin(LIVEPOCKET_BASE_URL, tickets_location) if tickets_location else ""
            log(
                "[flash-sale] TICKETS response from reauth follow "
                f"trace={trace_id}, status={tickets_resp.status_code}, location={tickets_redirect or '-'}, "
                f"bytes={len(tickets_text)}, title={extract_page_title(tickets_text) or '-'}"
            )
        else:
            log(f"[flash-sale] RETRY tickets after reauth trace={trace_id}: {tickets_url}")
            tickets_resp = session.get(
                tickets_url,
                headers=request_headers(
                    accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    referer=referer,
                ),
                allow_redirects=False,
                timeout=30,
            )
            tickets_text = tickets_resp.text or ""
            tickets_location = tickets_resp.headers.get("Location", "")
            tickets_redirect = urljoin(LIVEPOCKET_BASE_URL, tickets_location) if tickets_location else ""
            log(
                "[flash-sale] TICKETS retry response "
                f"trace={trace_id}, status={tickets_resp.status_code}, location={tickets_redirect or '-'}, "
                f"bytes={len(tickets_text)}, title={extract_page_title(tickets_text) or '-'}"
            )
    if tickets_resp.status_code in (301, 302, 303, 307, 308):
        return fail_result(
            f"普通抢票 tickets 页被重定向，未返回表单: {tickets_redirect}",
            http_status=tickets_resp.status_code,
            raw_result=response_preview(tickets_text),
            request_url=tickets_url,
            submit_url=tickets_redirect,
            extra={"authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )
    if tickets_resp.status_code >= 400:
        return fail_result(
            "普通抢票 tickets 页访问失败",
            http_status=tickets_resp.status_code,
            raw_result=response_preview(tickets_text),
            request_url=tickets_url,
            extra={"authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )

    authenticity_token = extract_input_value(tickets_text, "authenticity_token")
    if not authenticity_token:
        preview = response_preview(tickets_text)
        log(
            "[flash-sale] TICKETS token missing "
            f"trace={trace_id}, contentType={tickets_resp.headers.get('Content-Type', '-')}, "
            f"preview={preview or '-'}"
        )
        return fail_result(
            "普通抢票 tickets 页 token 缺失，可能未进入真实 tickets 表单",
            http_status=tickets_resp.status_code,
            raw_result=preview,
            request_url=tickets_url,
            extra={"authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )
    options = parse_ticket_options(tickets_text)
    selected, selected_reason = choose_ticket_option(options, task_options, payload)
    if not selected:
        return fail_result(
            selected_reason,
            http_status=tickets_resp.status_code,
            raw_result=response_preview(tickets_text),
            request_url=tickets_url,
            extra={"availableSessions": options, "authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )
    try:
        quantity = resolve_quantity(selected, task_options, payload)
    except ValueError as exc:
        return fail_result(
            str(exc),
            http_status=tickets_resp.status_code,
            request_url=tickets_url,
            extra={"selectedSession": selected, "availableSessions": options, "authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )

    select_url = extract_select_seat_url(tickets_text, tickets_url)
    select_form = {
        "authenticity_token": authenticity_token,
        str(selected["fieldName"]): quantity,
    }
    log(
        "[flash-sale] POST select_seat "
        f"trace={trace_id}, url={select_url}, field={selected.get('fieldName')}, "
        f"ticketId={selected.get('ticketId')}, quantity={quantity}"
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
        "[flash-sale] SELECT response "
        f"trace={trace_id}, status={select_resp.status_code}, location={select_location or '-'}, "
        f"bytes={len(select_text)}, title={extract_page_title(select_text) or '-'}"
    )
    confirm_url = urljoin(LIVEPOCKET_BASE_URL, select_location) if "purchase/confirm" in select_location else parse_confirm_url_from_html(select_text)
    event_id, reserve_id = parse_confirm_params_from_url(confirm_url or "")
    if not event_id or not reserve_id:
        errors = extract_livepocket_errors(select_text)
        return fail_result(
            "select_seat 未返回确认页参数" + (f": {'; '.join(errors)}" if errors else ""),
            http_status=select_resp.status_code,
            raw_result=compact_text(visible_text(select_text)),
            request_url=tickets_url,
            submit_url=select_url,
            extra={"selectedSession": selected, "availableSessions": options, "authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )

    log(f"[flash-sale] GET confirm trace={trace_id}: {confirm_url}")
    confirm_resp = session.get(confirm_url, headers=request_headers(referer=tickets_url), allow_redirects=True, timeout=30)
    confirm_text = confirm_resp.text or ""
    log(
        "[flash-sale] CONFIRM response "
        f"trace={trace_id}, status={confirm_resp.status_code}, finalUrl={confirm_resp.url}, "
        f"bytes={len(confirm_text)}, title={extract_page_title(confirm_text) or '-'}, reserveId={reserve_id}, eventId={event_id}"
    )
    if confirm_resp.status_code >= 400:
        return fail_result(
            "普通抢票确认页访问失败",
            http_status=confirm_resp.status_code,
            raw_result=compact_text(visible_text(confirm_text)),
            request_url=confirm_url,
            extra={"selectedSession": selected, "reserveId": reserve_id, "authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
        )
    try:
        purchase_form, payment_context = build_purchase_form(confirm_text, event_id, reserve_id)
    except ValueError as exc:
        return fail_result(
            str(exc),
            http_status=confirm_resp.status_code,
            raw_result=compact_text(visible_text(confirm_text)),
            request_url=confirm_url,
            extra={"selectedSession": selected, "reserveId": reserve_id, "authSource": auth_source, "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False)},
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
        "[flash-sale] POST purchase "
        f"trace={trace_id}, url={PURCHASE_URL}, payment={purchase_form.get('order_form[payment_method]', '')}, "
        f"cvsType={purchase_form.get('order_form[sbps_web_cvs_type]', '-')}"
    )
    purchase_resp = session.post(PURCHASE_URL, data=purchase_form, headers=purchase_headers, allow_redirects=False, timeout=30)
    purchase_text = purchase_resp.text or ""
    purchase_location = purchase_resp.headers.get("Location", "")
    result_url = urljoin(LIVEPOCKET_BASE_URL, purchase_location) if purchase_location else purchase_resp.url
    order_id = parse_order_id(result_url) or parse_order_id(purchase_text)
    result_excerpt = compact_text(visible_text(purchase_text))
    errors = extract_livepocket_errors(purchase_text)
    log(
        "[flash-sale] PURCHASE response "
        f"trace={trace_id}, status={purchase_resp.status_code}, location={purchase_location or '-'}, "
        f"orderId={order_id or '-'}, bytes={len(purchase_text)}, title={extract_page_title(purchase_text) or '-'}"
    )
    if purchase_resp.status_code >= 400 or (errors and purchase_resp.status_code not in (302, 303)):
        return fail_result(
            "普通抢票提交失败" + (f": {'; '.join(errors)}" if errors else ""),
            http_status=purchase_resp.status_code,
            raw_result=result_excerpt,
            request_url=confirm_url,
            submit_url=PURCHASE_URL,
            extra={
                "selectedSession": selected,
                "ticketField": selected.get("fieldName"),
                "ticketId": selected.get("ticketId"),
                "confirmUrl": confirm_url,
                "reserveId": reserve_id,
                "availableSessions": options,
                "authSource": auth_source,
                "loginReqData": json.dumps(refreshed_login_context, ensure_ascii=False),
            },
        )

    message = "普通抢票提交完成"
    if payment_context.get("paymentStatus") == "offline_pending":
        message = "普通抢票提交完成，等待 Lawson 便利店支付"
    return {
        "success": True,
        "status": "submitted",
        "message": message,
        "httpStatus": purchase_resp.status_code,
        "requestUrl": tickets_url,
        "submitUrl": PURCHASE_URL,
        "selectedSession": selected,
        "selectedReason": selected_reason,
        "ticketField": selected.get("fieldName"),
        "ticketId": selected.get("ticketId"),
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
