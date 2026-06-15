import json
import html
import re
import time
from typing import Any, Callable, Dict, Optional, Tuple
from urllib.parse import urlparse

import requests

from livepocket_proxy import apply_proxy_to_session, get_proxy


DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/147.0.0.0 Safari/537.36"
)
LIVEPOCKET_BASE_URL = "https://livepocket.jp"
LIVEPOCKET_LOGIN_CHECK_URL = "https://livepocket.jp/my_top"


def parse_json_maybe(value: Any) -> Dict[str, Any]:
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
    cookies: Dict[str, str] = {}
    for part in (cookie_header or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name = name.strip()
        if name:
            cookies[name] = value.strip()
    return cookies


def session_cookie_names(session: requests.Session) -> list[str]:
    return sorted({cookie.name for cookie in session.cookies})[:20]


def restore_session_from_login_context(login_context: Dict[str, Any], proxy_key: str = "") -> requests.Session:
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
            "Origin": LIVEPOCKET_BASE_URL,
        }
    )

    for name, value in parse_cookie_header(login_context.get("cookieHeader") or "").items():
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


def export_session_login_context(session: requests.Session) -> Dict[str, Any]:
    cookies = []
    cookies_json: Dict[str, str] = {}
    cookie_header_parts = []
    for cookie in session.cookies:
        cookies.append(
            {
                "name": cookie.name,
                "value": cookie.value,
                "domain": cookie.domain or ".livepocket.jp",
                "path": cookie.path or "/",
            }
        )
        cookies_json[cookie.name] = cookie.value
        cookie_header_parts.append(f"{cookie.name}={cookie.value}")

    headers = dict(session.headers or {})
    user_agent = headers.get("User-Agent") or DEFAULT_USER_AGENT
    return {
        "format": "auto-py-livepocket-login-context-v1",
        "exportedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "loginUrl": LIVEPOCKET_LOGIN_CHECK_URL,
        "userAgent": user_agent,
        "headers": {
            "User-Agent": user_agent,
            "Accept-Language": headers.get("Accept-Language", "ja,en-US;q=0.9,en;q=0.8"),
        },
        "cookies": cookies,
        "cookiesJson": cookies_json,
        "cookieHeader": "; ".join(cookie_header_parts),
    }


def check_login_state(session: requests.Session, request_headers: Optional[Callable[..., Dict[str, str]]] = None) -> Dict[str, Any]:
    headers = (
        request_headers(referer=LIVEPOCKET_BASE_URL + "/")
        if callable(request_headers)
        else {"Referer": LIVEPOCKET_BASE_URL + "/"}
    )
    response = session.get(
        LIVEPOCKET_LOGIN_CHECK_URL,
        headers=headers,
        allow_redirects=True,
        timeout=30,
    )
    page_text = response.text or ""
    final_path = urlparse(response.url or "").path
    title_match = re.search(r"<title[^>]*>(.*?)</title>", page_text, flags=re.I | re.S)
    page_title = html.unescape(title_match.group(1).strip()) if title_match else ""
    redirected_to_login = "/login" in final_path
    title_is_login = "ログイン" in page_title or "login" in page_title.lower()
    waf_action = response.headers.get("x-amzn-waf-action", "")
    blocked = (
        response.status_code == 202
        or waf_action.lower() == "challenge"
        or "AwsWafIntegration" in page_text
        or ("challenge.js" in page_text and "awswaf.com" in page_text)
    )
    return {
        "loggedIn": response.status_code < 400 and not blocked and not redirected_to_login and not title_is_login,
        "status": response.status_code,
        "finalUrl": response.url,
        "title": page_title,
        "redirectedToLogin": redirected_to_login,
        "blocked": blocked,
    }


def resolve_authenticated_session(
    *,
    email: str,
    password: str,
    login_context: Dict[str, Any],
    login_func: Callable[[], Optional[requests.Session]],
    logger: Callable[[str], None],
    trace_id: str,
    flow_label: str,
    request_headers: Optional[Callable[..., Dict[str, str]]] = None,
) -> Tuple[Optional[requests.Session], str, Dict[str, Any], str]:
    if login_context:
        try:
            session = restore_session_from_login_context(login_context, proxy_key=email)
            login_state = check_login_state(session, request_headers=request_headers)
            logger(
                f"[{flow_label}] LOGIN context check trace={trace_id}, authSource=context, "
                f"email={email}, loggedIn={login_state.get('loggedIn')}, status={login_state.get('status')}, "
                f"finalUrl={login_state.get('finalUrl')}, title={login_state.get('title') or '-'}"
            )
            if login_state.get("loggedIn"):
                return session, "context", login_state, ""
        except Exception as exc:
            logger(f"[{flow_label}] LOGIN context invalid trace={trace_id}, authSource=context, email={email}, error={exc}")

    if not password:
        return None, "none", {}, "账号缺少密码，且登录上下文不可用"

    logger(f"[{flow_label}] LOGIN runtime start trace={trace_id}, authSource=runtime-login, email={email}")
    session = login_func()
    if not session:
        return None, "runtime-login", {}, "现场登录失败"
    try:
        login_state = check_login_state(session, request_headers=request_headers)
        logger(
            f"[{flow_label}] LOGIN runtime check trace={trace_id}, authSource=runtime-login, "
            f"email={email}, loggedIn={login_state.get('loggedIn')}, status={login_state.get('status')}, "
            f"finalUrl={login_state.get('finalUrl')}, title={login_state.get('title') or '-'}"
        )
        if not login_state.get("loggedIn"):
            return session, "runtime-login", login_state, "现场登录后登录态仍无效"
    except Exception as exc:
        logger(f"[{flow_label}] LOGIN runtime check trace={trace_id}, authSource=runtime-login, email={email}, error={exc}")
    return session, "runtime-login", {}, ""
