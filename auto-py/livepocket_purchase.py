"""
LivePocket order submitter for local protocol testing.

Flow:
1. Log in with the fixed account below.
2. Open the tickets page.
3. Select one parsed ticket type.
4. Open the purchase confirm page.
5. Submit the purchase form.

Usage:
  python main.py
"""
import argparse
import html
import json
import re
import sys
import time
import uuid
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple
from urllib.parse import parse_qs, urljoin, urlparse

import requests

from backend_api import DEFAULT_BACKEND_BASE_URL, DEFAULT_PLATFORM_CODE, TicketBackendApi
from ticket_runtime import get_logger
from capsolver_captcha import CaptchaSolver, capsolver_api_key
from livepocket_login import LivePocketLogin
from livepocket_proxy import LivePocketProxyError, get_proxy, proxy_max_attempts


LOGGER = get_logger("livepocket.purchase")
print = LOGGER.print

BACKEND_BASE_URL = DEFAULT_BACKEND_BASE_URL
PLATFORM_CODE = DEFAULT_PLATFORM_CODE

# Fixed local test account. Change these before running if needed.
LOGIN_EMAIL = "5ez7rw@gjcytech.com"
LOGIN_PASSWORD = "5ez7rw@ABC"

EVENT_TICKETS_URL = "https://livepocket.jp/e/6nuj_/receptions/853121/tickets"
PURCHASE_QUANTITY = 2
PAYMENT_METHOD = "cvs"
SBPS_WEB_CVS_TYPE = "016"

LIVEPOCKET_BASE_URL = "https://livepocket.jp"
PURCHASE_CONFIRM_URL = "https://livepocket.jp/purchase/confirm"
PURCHASE_URL = "https://livepocket.jp/purchase"


@dataclass
class TicketOption:
    field_name: str
    ticket_id: str
    value: str
    label: str = ""


def compact_text(text: str, limit: int = 1600) -> str:
    text = re.sub(r"\s+", " ", strip_tags(text or "")).strip()
    return text if len(text) <= limit else text[:limit] + "..."


def strip_tags(text: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", "", text or ""))


def parse_tag_attrs(tag: str) -> Dict[str, str]:
    attrs: Dict[str, str] = {}
    for match in re.finditer(r"([\w:-]+)\s*=\s*(['\"])(.*?)\2", tag, flags=re.S):
        attrs[match.group(1).lower()] = html.unescape(match.group(3))
    return attrs


def iter_input_attrs(html_text: str):
    for match in re.finditer(r"<input\b[^>]*>", html_text or "", flags=re.I | re.S):
        yield parse_tag_attrs(match.group(0)), match.group(0)


def extract_token(html_text: str, token_name: str = "authenticity_token") -> Optional[str]:
    if token_name == "csrf-token":
        match = re.search(
            r"<meta\s+name=['\"]csrf-token['\"]\s+content=['\"]([^'\"]+)",
            html_text or "",
            flags=re.I,
        )
        return html.unescape(match.group(1)) if match else None

    for attrs, _tag in iter_input_attrs(html_text):
        if attrs.get("name") == token_name and attrs.get("value"):
            return attrs["value"]
    return None


def extract_hidden_inputs(html_text: str) -> Dict[str, str]:
    values: Dict[str, str] = {}
    for attrs, _tag in iter_input_attrs(html_text):
        name = attrs.get("name")
        if not name:
            continue
        input_type = attrs.get("type", "").lower()
        if input_type in ("hidden", "submit") or name in ("authenticity_token", "_method"):
            values[name] = attrs.get("value", "")
    return values


def extract_livepocket_errors(html_text: str) -> List[str]:
    errors: List[str] = []
    patterns = [
        r"<p[^>]*class=['\"][^'\"]*form-error-text[^'\"]*['\"][^>]*>(.*?)</p>",
        r"<div[^>]*class=['\"][^'\"]*message--error[^'\"]*['\"][^>]*>(.*?)</div>",
        r"<li[^>]*class=['\"][^'\"]*error[^'\"]*['\"][^>]*>(.*?)</li>",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, html_text or "", flags=re.I | re.S):
            message = compact_text(match.group(1), limit=500)
            if message and message not in errors:
                errors.append(message)

    if not errors:
        lower = (html_text or "").lower()
        if "error" in lower or "エラー" in html_text or "失敗" in html_text:
            excerpt = compact_text(html_text, limit=500)
            if excerpt:
                errors.append(excerpt)
    return errors


def get_email_verify_code(
    email: str,
    max_retries: int = 10,
    interval_seconds: int = 3,
    backend_base_url: Optional[str] = None,
    platform_code: Optional[str] = None,
) -> Optional[str]:
    backend = TicketBackendApi(backend_base_url or BACKEND_BASE_URL, platform_code or PLATFORM_CODE)
    print(f"[Login] 请求邮件验证码: email={email}, backend={backend.base_url}, platformCode={backend.platform_code}")
    return backend.email_verify_code(email, attempts=max_retries, interval_seconds=interval_seconds, retry_rounds=1)


def submit_email_code_with_retry(
    login_client: LivePocketLogin,
    email: str,
    max_attempts: int = 3,
    backend_base_url: Optional[str] = None,
    platform_code: Optional[str] = None,
) -> bool:
    for attempt in range(1, max_attempts + 1):
        print(f"[Login-2] Fetch email verification code, attempt {attempt}/{max_attempts}")
        verify_code = get_email_verify_code(email, backend_base_url=backend_base_url, platform_code=platform_code)
        if not verify_code:
            print(f"[Login-2] No email verification code found, attempt {attempt}/{max_attempts}")
            continue

        try:
            ok = login_client.submit_email_code(verify_code)
        except Exception as exc:
            print(f"[Login-2] Email verification submit error, attempt {attempt}/{max_attempts}: {exc}")
            ok = False
        if ok:
            print(f"[Login-2] Email verification success, attempt {attempt}/{max_attempts}")
            return True

        print(f"[Login-2] Email verification failed, will retry if attempts remain ({attempt}/{max_attempts})")
        if attempt < max_attempts:
            time.sleep(2)

    print(f"[X] Login failed: email verification failed {max_attempts} times")
    return False


def login_livepocket(
    email: str,
    password: str,
    debug: bool = False,
    backend_base_url: Optional[str] = None,
    platform_code: Optional[str] = None,
) -> Optional[requests.Session]:
    last_error = ""
    for attempt in range(1, proxy_max_attempts() + 1):
        print("[Login-1] Init LivePocket login client")
        captcha_solver = CaptchaSolver(api_key=capsolver_api_key())
        proxy = get_proxy(email, refresh=attempt > 1)
        login_client = LivePocketLogin(captcha_solver, use_proxy=False, proxy=proxy, proxy_key=email)

        print(f"[Login-2] Login: {email}")
        try:
            login_success = login_client.login(email, password)
        except LivePocketProxyError as exc:
            last_error = str(exc)
            if attempt < proxy_max_attempts():
                print(f"[Login-2] LivePocket proxy failed, retry with another proxy: {attempt}/{proxy_max_attempts()}, email={email}, error={exc}")
                continue
            print(f"[X] Login failed: LivePocket proxy access failed: {exc}")
            return None
        if not login_success and getattr(login_client, "_need_email_code", False):
            print("[Login-2] Email verification required")
            login_success = submit_email_code_with_retry(
                login_client,
                email,
                max_attempts=3,
                backend_base_url=backend_base_url,
                platform_code=platform_code,
            )
        break

    if not login_success:
        print(f"[X] Login failed{': ' + last_error if last_error else ''}")
        return None

    if debug:
        context = login_client.export_login_context()
        print("[DEBUG] Login context:")
        print(json.dumps(context, ensure_ascii=False))

    print("[OK] Login success")
    return login_client.session


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


def event_detail_url_from_tickets_url(tickets_url: str) -> str:
    parsed = urlparse(tickets_url)
    match = re.match(r"^(/e/[^/]+)(?:/receptions/\d+/tickets)?$", parsed.path)
    if match:
        return parsed._replace(path=match.group(1), query="", fragment="").geturl()
    return LIVEPOCKET_BASE_URL + "/"


def extract_ticket_entry_url(event_html: str, fallback_url: str) -> str:
    links = []
    for match in re.finditer(r"<a\b[^>]*\bhref=(['\"])(.*?)\1[^>]*>", event_html or "", flags=re.I | re.S):
        tag = match.group(0)
        href = html.unescape(match.group(2))
        text = compact_text(tag, limit=200)
        if "/receptions/" in href and "/tickets" in href:
            links.append((href, text))
    if not links:
        return fallback_url

    purchase_words = ("チケットを購入", "購入", "申込", "予約")
    for href, text in links:
        if any(word in text for word in purchase_words):
            return urljoin(LIVEPOCKET_BASE_URL, href)
    return urljoin(LIVEPOCKET_BASE_URL, links[0][0])


def get_event_detail_page(session: requests.Session, event_url: str, debug: bool = False) -> Tuple[str, str]:
    print("")
    print("========== 第零步-进入活动详情页 ==========")
    print(f"[第零步] Request: GET {event_url}")
    response = session.get(event_url, headers=request_headers(referer=LIVEPOCKET_BASE_URL + "/"), allow_redirects=True, timeout=30)
    print(f"[第零步] Response status: {response.status_code}")
    print(f"[第零步] Final URL: {response.url}")
    print(f"[第零步] Content-Type: {response.headers.get('Content-Type', '')}")
    print(f"[第零步] HTML length: {len(response.text or '')}")
    if debug:
        print(f"[DEBUG][第零步] HTML excerpt: {compact_text(response.text, 700)}")
    if response.status_code >= 400:
        raise RuntimeError(f"Event detail page failed: HTTP {response.status_code}")
    return response.text, response.url


def get_tickets_page(
    session: requests.Session,
    tickets_url: str,
    event_url: str,
    debug: bool = False,
) -> Tuple[str, str]:
    print("")
    print("========== 第一步-获取票信息 ==========")
    print(f"[第一步] Request: GET {tickets_url}")
    headers = request_headers(
        accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
        referer=event_url,
        turbo=False,
    )
    # LivePocket may redirect /tickets back to the event page. Do not follow it
    # silently; the event page only contains the purchase link, not tickets[...] fields.
    headers["X-Turbo-Request-Id"] = str(uuid.uuid4())
    response = session.get(tickets_url, headers=headers, allow_redirects=False, timeout=30)
    location = response.headers.get("Location", "")
    redirect_url = urljoin(LIVEPOCKET_BASE_URL, location) if location else ""
    print(f"[第一步] Response status: {response.status_code}")
    print(f"[第一步] Response URL: {response.url}")
    if redirect_url:
        print(f"[第一步] Redirect: {redirect_url}")
    print(f"[第一步] Content-Type: {response.headers.get('Content-Type', '')}")
    print(f"[第一步] HTML length: {len(response.text or '')}")
    print(f"[第一步] Referer: {event_url}")
    if debug:
        print(f"[DEBUG][第一步] Request headers:")
        print(json.dumps(mask_headers(headers), ensure_ascii=False, indent=2))
        print(f"[DEBUG][第一步] HTML excerpt: {compact_text(response.text, 700)}")
    if response.status_code in (301, 302, 303, 307, 308):
        raise RuntimeError(
            "Tickets endpoint redirected before returning the ticket form. "
            f"redirect={redirect_url or location or '(empty)'}. "
            "This usually means LivePocket did not expose the purchase form for this session "
            "(sale not open, account not eligible, WAF/session issue, or required browser state missing)."
        )
    if response.status_code >= 400:
        raise RuntimeError(f"Tickets page failed: HTTP {response.status_code}")
    return response.text, tickets_url


def find_nearby_label(html_text: str, tag_start: int) -> str:
    window = html_text[max(0, tag_start - 1200) : tag_start + 600]
    candidates = re.findall(r"<(?:h\d|p|span|div|label)[^>]*>(.*?)</(?:h\d|p|span|div|label)>", window, flags=re.I | re.S)
    cleaned = [compact_text(item, 120) for item in candidates]
    cleaned = [item for item in cleaned if item and "authenticity_token" not in item]
    return cleaned[-1] if cleaned else ""


def parse_ticket_options(html_text: str) -> List[TicketOption]:
    options: List[TicketOption] = []
    seen = set()
    for match in re.finditer(r"<input\b[^>]*\bname=(['\"])(tickets\[(\d+)\])\1[^>]*>", html_text or "", flags=re.I | re.S):
        tag = match.group(0)
        attrs = parse_tag_attrs(tag)
        if "disabled" in tag.lower():
            continue
        field_name = attrs.get("name") or match.group(2)
        ticket_id = match.group(3)
        key = (field_name, ticket_id)
        if key in seen:
            continue
        seen.add(key)
        value = attrs.get("value", "")
        label = find_nearby_label(html_text, match.start())
        options.append(TicketOption(field_name=field_name, ticket_id=ticket_id, value=value, label=label))

    if options:
        return options

    for ticket_id in sorted(set(re.findall(r"tickets\[(\d+)\]", html_text or ""))):
        options.append(TicketOption(field_name=f"tickets[{ticket_id}]", ticket_id=ticket_id, value="", label=""))
    return options


def select_ticket_option(options: List[TicketOption], ticket_index: int) -> TicketOption:
    if not options:
        raise RuntimeError(
            "No ticket fields found. The HTML does not contain tickets[...], "
            "so this is probably the event detail page or an unavailable-sales page, not the ticket form."
        )

    print(f"[第一步] Parsed ticket option count: {len(options)}")
    for index, option in enumerate(options):
        suffix = f" - {option.label}" if option.label else ""
        print(f"[第一步]   [{index}] {option.field_name}, default={option.value!r}{suffix}")

    if ticket_index < 0 or ticket_index >= len(options):
        raise RuntimeError(f"ticket-index {ticket_index} out of range, parsed {len(options)} options")
    chosen = options[ticket_index]
    print(f"[第一步] Selected ticket: index={ticket_index}, field={chosen.field_name}, ticketId={chosen.ticket_id}")
    return chosen


def select_seat_url_from_tickets_url(tickets_url: str) -> str:
    parsed = urlparse(tickets_url)
    if parsed.path.endswith("/tickets"):
        path = parsed.path[: -len("/tickets")] + "/select_seat"
        return parsed._replace(path=path, query="", fragment="").geturl()
    return urljoin(tickets_url, "select_seat")


def parse_confirm_params_from_url(url: str) -> Tuple[Optional[str], Optional[str]]:
    query = parse_qs(urlparse(url).query)
    event_id = (query.get("id") or [None])[0]
    reserve_id = (query.get("reserve_id") or [None])[0]
    return event_id, reserve_id


def parse_confirm_url_from_html(html_text: str) -> Optional[str]:
    patterns = [
        r"['\"]([^'\"]*/purchase/confirm\?[^'\"]+)['\"]",
        r"href=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
        r"action=['\"]([^'\"]*purchase/confirm\?[^'\"]+)['\"]",
    ]
    for pattern in patterns:
        match = re.search(pattern, html_text or "", flags=re.I)
        if match:
            return urljoin(LIVEPOCKET_BASE_URL, html.unescape(match.group(1)))
    return None


def select_seat(
    session: requests.Session,
    tickets_url: str,
    tickets_html: str,
    option: TicketOption,
    quantity: int,
    debug: bool = False,
) -> Tuple[str, str, str]:
    print("")
    print("========== 第二步-获取票ID ==========")
    authenticity_token = extract_token(tickets_html)
    if not authenticity_token:
        raise RuntimeError("Tickets page has no authenticity_token")

    select_url = select_seat_url_from_tickets_url(tickets_url)
    form_data = {
        "authenticity_token": authenticity_token,
        option.field_name: str(quantity),
    }
    headers = request_headers(
        accept="text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        referer=tickets_url,
        turbo=False,
    )
    headers["Content-Type"] = "application/x-www-form-urlencoded"

    print(f"[第二步] Request: POST {select_url}")
    print(f"[第二步] Referer: {tickets_url}")
    print(f"[第二步] authenticity_token: {authenticity_token[:50]}...")
    print(f"[第二步] Ticket field: {option.field_name}")
    print(f"[第二步] Ticket id: {option.ticket_id}")
    print(f"[第二步] Quantity: {quantity}")
    if debug:
        print("[DEBUG][第二步] Request headers:")
        print(json.dumps(mask_headers(headers), ensure_ascii=False, indent=2))
        print("[DEBUG][第二步] Form data:")
        print(mask_form_data(form_data))

    response = session.post(select_url, data=form_data, headers=headers, allow_redirects=False, timeout=30)
    location = response.headers.get("Location", "")
    redirect_url = urljoin(LIVEPOCKET_BASE_URL, location) if location else response.url
    print(f"[第二步] Response status: {response.status_code}")
    print(f"[第二步] Response URL: {response.url}")
    print(f"[第二步] Location: {redirect_url}")
    print(f"[第二步] Content-Type: {response.headers.get('Content-Type', '')}")
    print(f"[第二步] HTML length: {len(response.text or '')}")

    confirm_url = redirect_url if "purchase/confirm" in redirect_url else parse_confirm_url_from_html(response.text)
    event_id, reserve_id = parse_confirm_params_from_url(confirm_url or "")

    if not event_id or not reserve_id:
        errors = extract_livepocket_errors(response.text)
        if errors:
            print_errors(errors)
        if debug:
            print("[DEBUG][第二步] Response text:")
            print(response.text[:1600])
        raise RuntimeError("select_seat did not return id/reserve_id")

    print(f"[第二步] Confirm URL: {confirm_url}")
    print(f"[第二步] Parsed event_id: {event_id}")
    print(f"[第二步] Parsed reserve_id: {reserve_id}")
    return confirm_url or PURCHASE_CONFIRM_URL, event_id, reserve_id


def load_confirm_page(
    session: requests.Session,
    confirm_url: str,
    event_id: str,
    reserve_id: str,
    referer: str,
    debug: bool = False,
) -> str:
    url = confirm_url
    if "id=" not in url or "reserve_id=" not in url:
        url = f"{PURCHASE_CONFIRM_URL}?id={event_id}&reserve_id={reserve_id}"

    print(f"[6] GET confirm page: {url}")
    response = session.get(url, headers=request_headers(referer=referer), allow_redirects=True, timeout=30)
    print(f"[6] Status: {response.status_code}, final URL: {response.url}")
    if debug:
        print(f"[DEBUG] Confirm page excerpt: {compact_text(response.text, 900)}")
    if response.status_code >= 400:
        raise RuntimeError(f"Confirm page failed: HTTP {response.status_code}")
    return response.text


def build_purchase_form(confirm_html: str, event_id: str, reserve_id: str) -> Dict[str, str]:
    form_data = extract_hidden_inputs(confirm_html)
    authenticity_token = extract_token(confirm_html)
    if not authenticity_token:
        raise RuntimeError("Confirm page has no authenticity_token")

    form_data["authenticity_token"] = authenticity_token
    form_data["id"] = event_id
    form_data["order_form[reserve_id]"] = reserve_id
    form_data["order_form[event_id]"] = event_id
    form_data["order_form[payment_method]"] = PAYMENT_METHOD
    form_data["order_form[sbps_web_cvs_type]"] = SBPS_WEB_CVS_TYPE
    form_data["order_form[follow_notification]"] = "1"
    form_data["order_form[purchase_agreement_content]"] = "1"
    return form_data


def submit_purchase(
    session: requests.Session,
    confirm_html: str,
    event_id: str,
    reserve_id: str,
    debug: bool = False,
) -> bool:
    form_data = build_purchase_form(confirm_html, event_id, reserve_id)
    csrf_token = extract_token(confirm_html, "csrf-token") or form_data.get("authenticity_token", "")
    headers = request_headers(
        accept="text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
        referer=f"{PURCHASE_CONFIRM_URL}?id={event_id}&reserve_id={reserve_id}",
        turbo=True,
    )
    headers["X-CSRF-Token"] = csrf_token

    print(f"[7] POST purchase: {PURCHASE_URL}")
    if debug:
        print("[DEBUG] purchase form:")
        print(mask_form_data(form_data))

    response = session.post(PURCHASE_URL, data=form_data, headers=headers, allow_redirects=False, timeout=30)
    location = response.headers.get("Location", "")
    redirect_url = urljoin(LIVEPOCKET_BASE_URL, location) if location else response.url
    order_id = parse_order_id(redirect_url) or parse_order_id(response.text)
    print(f"[7] Status: {response.status_code}, location: {redirect_url}")
    if order_id:
        print(f"[7] Parsed order_id={order_id}")

    errors = extract_livepocket_errors(response.text)
    if errors and response.status_code not in (302, 303):
        print_errors(errors)
        if debug:
            print("[DEBUG] purchase response:")
            print(response.text[:2000])
        return False

    if response.status_code in (200, 302, 303):
        print("[OK] Purchase submitted")
        if redirect_url:
            print(f"[OK] Result URL: {redirect_url}")
        return True

    print(f"[X] Purchase failed: HTTP {response.status_code}")
    if debug:
        print(response.text[:2000])
    return False


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


def mask_form_data(form_data: Dict[str, str]) -> str:
    masked = {}
    for key, value in form_data.items():
        text = str(value)
        if key == "authenticity_token" or "token" in key.lower():
            text = text[:50] + "..." if len(text) > 50 else text
        masked[key] = text
    return json.dumps(masked, ensure_ascii=False, indent=2)


def mask_headers(headers: Dict[str, str]) -> Dict[str, str]:
    masked = {}
    for key, value in headers.items():
        text = str(value)
        if key.lower() in ("cookie", "authorization"):
            text = text[:80] + "..." if len(text) > 80 else text
        masked[key] = text
    return masked


def print_errors(errors: List[str]) -> None:
    print("[X] LivePocket returned errors:")
    for error in errors:
        print(f"  - {error}")
