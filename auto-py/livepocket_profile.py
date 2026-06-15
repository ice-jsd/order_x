"""
LivePocket profile updater.

This script does exactly one thing:
1. Log in to LivePocket with email/password.
2. Open the profile page.
3. Update the profile to the preset value captured from browser traffic.

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

import requests

from backend_api import normalize_backend_base_url, normalize_platform_code
from ticket_runtime import get_logger
from capsolver_captcha import CaptchaSolver, capsolver_api_key
from livepocket_login import LivePocketLogin, submit_email_code_with_retry
from livepocket_proxy import LivePocketProxyError, get_proxy, proxy_max_attempts
from livepocket_session import (
    check_login_state,
    export_session_login_context,
    parse_json_maybe,
    restore_session_from_login_context,
)


LOGGER = get_logger("livepocket.profile")
print = LOGGER.print

PROFILE_URL = "https://livepocket.jp/account/profile"
# Captured preset value: U+115F + U+1160 + SPACE.
PRESET_LAST_NAME = "ᅟᅠ "
PRESET_PREFECTURE_ID = "19"
PRESET_COUNTRY_ID = ""

def extract_token(html_text, token_name="authenticity_token"):
    """Extract hidden input token or csrf-token meta from HTML."""
    if token_name == "csrf-token":
        match = re.search(r'<meta\s+name="csrf-token"\s+content="([^"]+)"', html_text)
        return match.group(1) if match else None

    match = re.search(rf'name="{re.escape(token_name)}"[^>]*value="([^"]+)"', html_text)
    if match:
        return match.group(1)

    match = re.search(rf'value="([^"]+)"[^>]*name="{re.escape(token_name)}"', html_text)
    return match.group(1) if match else None


def extract_input_value(html_text, input_name):
    """Extract an input value regardless of HTML attribute order."""
    for match in re.finditer(r"<input\b[^>]*>", html_text or "", flags=re.I | re.S):
        tag = match.group(0)
        name_match = re.search(r"\bname\s*=\s*(['\"])(.*?)\1", tag, flags=re.I | re.S)
        if not name_match or name_match.group(2) != input_name:
            continue
        value_match = re.search(r"\bvalue\s*=\s*(['\"])(.*?)\1", tag, flags=re.I | re.S)
        return html.unescape(value_match.group(2)) if value_match else ""
    return None


def extract_selected_option_value(html_text, select_name):
    """Extract the selected option value from a select field."""
    select_pattern = rf"<select\b(?=[^>]*\bname\s*=\s*(['\"]){re.escape(select_name)}\1)[^>]*>(.*?)</select>"
    match = re.search(select_pattern, html_text or "", flags=re.I | re.S)
    if not match:
        return None
    block = match.group(2)
    selected_match = re.search(r"<option\b(?=[^>]*\bselected\b)[^>]*\bvalue\s*=\s*(['\"])(.*?)\1", block, flags=re.I | re.S)
    if selected_match:
        return html.unescape(selected_match.group(2))
    return None


def extract_livepocket_errors(html_text):
    """Extract concise error messages from LivePocket turbo/html response."""
    errors = []
    for match in re.finditer(r'<p[^>]*class="[^"]*form-error-text[^"]*"[^>]*>(.*?)</p>', html_text, re.S):
        text = re.sub(r"<[^>]+>", "", match.group(1))
        text = re.sub(r"\s+", " ", text).strip()
        if text:
            errors.append(text)

    if not errors and "message--error" in html_text:
        match = re.search(r'<div[^>]*class="[^"]*message--error[^"]*"[^>]*>(.*?)</div>', html_text, re.S)
        if match:
            text = re.sub(r"<[^>]+>", "", match.group(1))
            text = re.sub(r"\s+", " ", text).strip()
            if text:
                errors.append(text)
    return errors


def get_email_verify_code(email, max_retries=10, interval_seconds=3, backend_base_url=None, platform_code=None):
    """Get email login verification code from backend."""
    resolved_backend_base_url = normalize_backend_base_url(backend_base_url)
    resolved_platform_code = normalize_platform_code(platform_code)
    url = f"{resolved_backend_base_url}/email-verify-code"
    params = {
        "platformCode": resolved_platform_code,
        "email": email,
    }
    print(f"[Login] 请求邮件验证码: email={email}, backend={resolved_backend_base_url}, platformCode={resolved_platform_code}")

    for i in range(max_retries):
        time.sleep(interval_seconds)
        try:
            response = requests.get(url, params=params, timeout=30)
            response.raise_for_status()
            result = response.json()
            if result.get("code") == 200 and result.get("data"):
                verify_code = result["data"].get("verifyCode")
                if verify_code:
                    print(f"[Login] 邮件验证码获取成功: {verify_code}")
                    return verify_code
            print(f"[Login] 邮件验证码未就绪，重试 {i + 1}/{max_retries}: {result.get('msg', '')}")
        except Exception as e:
            print(f"[Login] 获取邮件验证码异常，重试 {i + 1}/{max_retries}: {e}")
    return None


def login_livepocket(email, password, debug=False, backend_base_url=None, platform_code=None):
    """Reuse existing auto-py LivePocket login flow and return the logged-in session."""
    last_error = ""
    for attempt in range(1, proxy_max_attempts() + 1):
        print("[1] 初始化登录器...")
        captcha_solver = CaptchaSolver(api_key=capsolver_api_key())
        proxy = get_proxy(email, refresh=attempt > 1)
        login_client = LivePocketLogin(captcha_solver, use_proxy=False, proxy=proxy, proxy_key=email)

        print(f"[2] 开始登录: {email}")
        try:
            login_success = login_client.login(email, password)
        except LivePocketProxyError as exc:
            last_error = str(exc)
            if attempt < proxy_max_attempts():
                print(f"[ProfileLogin] LivePocket 代理失败，切换代理重试: {attempt}/{proxy_max_attempts()}, email={email}, error={exc}")
                continue
            print(f"[X] 登录失败：LivePocket 代理访问失败：{exc}")
            return None

        if not login_success:
            if hasattr(login_client, "_need_email_code") and login_client._need_email_code:
                print("[2] 需要邮件验证码，开始获取...")
                login_success = submit_email_code_with_retry(
                    login_client,
                    email,
                    lambda: get_email_verify_code(email, backend_base_url=backend_base_url, platform_code=platform_code),
                    max_attempts=3,
                    label="ProfileLogin",
                )
                if not login_success:
                    print("[X] 登录失败：邮件验证码获取或提交失败")
                    return None
            else:
                print("[X] 登录失败")
                return None
        break

    if not login_success:
        print(f"[X] 登录失败：{last_error or '验证码提交后仍未成功'}")
        return None

    if debug:
        context = login_client.export_login_context()
        print("[DEBUG] 登录上下文 JSON:")
        print(json.dumps(context, ensure_ascii=False))
        print("[DEBUG] Cookie Header:")
        print(context.get("cookieHeader", ""))

    print("[OK] 登录成功")
    return login_client.session


def update_profile_last_name(session, last_name, debug=False):
    """Update only LivePocket user[last_name], preserving other profile values where possible."""
    if not last_name:
        print("[X] 姓不能为空")
        return False

    print("[3] 获取资料页 token...")
    get_resp = session.get(PROFILE_URL, allow_redirects=True)

    if debug:
        print(f"[3] GET status: {get_resp.status_code}")
        print(f"[3] Final URL: {get_resp.url}")

    authenticity_token = extract_token(get_resp.text, "authenticity_token")
    csrf_token = extract_token(get_resp.text, "csrf-token") or authenticity_token

    if not authenticity_token:
        print("[X] 获取资料页失败：未找到 authenticity_token，可能登录态无效")
        if debug:
            print(get_resp.text[:1200])
        return False

    first_name = extract_input_value(get_resp.text, "user[first_name]")
    prefecture_id = extract_selected_option_value(get_resp.text, "user[prefecture_id]")
    country_id = extract_selected_option_value(get_resp.text, "user[country_id]")

    form_data = {
        "_method": "patch",
        "authenticity_token": authenticity_token,
        "user[last_name]": last_name,
        "button": "",
    }
    if first_name is not None:
        form_data["user[first_name]"] = first_name
    if prefecture_id is not None:
        form_data["user[prefecture_id]"] = prefecture_id
    if country_id is not None:
        form_data["user[country_id]"] = country_id

    headers = {
        "Accept": "text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "Origin": "https://livepocket.jp",
        "Referer": PROFILE_URL,
        "X-CSRF-Token": csrf_token or authenticity_token,
        "X-Turbo-Request-Id": str(uuid.uuid4()),
        "Sec-Fetch-Dest": "empty",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Site": "same-origin",
    }

    print("[4] 提交资料更新...")
    if debug:
        print("[DEBUG] 提交字段:")
        for key, value in form_data.items():
            if key == "authenticity_token":
                value = f"{value[:50]}..."
            print(f"  - {key}: {repr(value)}")

    post_resp = session.post(PROFILE_URL, data=form_data, headers=headers, allow_redirects=False)

    if debug:
        print(f"[4] PATCH status: {post_resp.status_code}")
        if post_resp.headers.get("Location"):
            print(f"[4] Redirect: {post_resp.headers.get('Location')}")

    errors = extract_livepocket_errors(post_resp.text)
    if errors:
        print("[X] LivePocket 返回错误:")
        for error in errors:
            print(f"  - {error}")
        if debug:
            print("[DEBUG] Response text:")
            print(post_resp.text[:1600])
        return False

    if post_resp.status_code in [200, 302, 303]:
        print("[OK] 资料更新成功")
        return True

    print(f"[X] 资料更新失败，状态码: {post_resp.status_code}")
    if debug:
        print(post_resp.text[:1600])
    return False

def update_livepocket_last_name(email, password, last_name, debug=False, backend_base_url=None, platform_code=None, login_context=None):
    """Update only the LivePocket last name using an existing login context."""
    trace_id = str(uuid.uuid4())[:12]
    parsed_context = parse_json_maybe(login_context)
    if not parsed_context:
        return {
            "success": False,
            "status": "failed",
            "message": "账号缺少登录上下文，请先批量登录成功后再改姓",
            "authSource": "none",
            "loginState": {},
        }
    try:
        session = restore_session_from_login_context(parsed_context, proxy_key=email)
        login_state = check_login_state(session)
        print(
            f"[livepocket.profile] LOGIN context check trace={trace_id}, authSource=context, "
            f"email={email}, loggedIn={login_state.get('loggedIn')}, status={login_state.get('status')}, "
            f"finalUrl={login_state.get('finalUrl')}, title={login_state.get('title') or '-'}"
        )
    except Exception as exc:
        return {
            "success": False,
            "status": "failed",
            "message": f"登录上下文校验失败，请先批量登录成功后再改姓: {exc}",
            "authSource": "context",
            "loginState": {},
        }
    if not login_state.get("loggedIn"):
        return {
            "success": False,
            "status": "failed",
            "message": "登录上下文已失效，请先批量登录成功后再改姓",
            "authSource": "context",
            "loginState": login_state,
        }
    ok = update_profile_last_name(session, last_name, debug=debug)
    result = {
        "success": bool(ok),
        "status": "completed" if ok else "failed",
        "message": "LivePocket 姓氏更新成功" if ok else "LivePocket 姓氏更新失败",
        "authSource": "context",
    }
    if ok:
        result["loginReqData"] = json.dumps(export_session_login_context(session), ensure_ascii=False)
    return result


def keepalive_livepocket_login(email, login_context=None):
    """Check the saved LivePocket login context with /my_top and refresh it if valid."""
    trace_id = str(uuid.uuid4())[:12]
    parsed_context = parse_json_maybe(login_context)
    if not parsed_context:
        return {
            "success": True,
            "loggedIn": False,
            "status": "offline",
            "message": "缺少登录上下文",
            "authSource": "none",
            "loginState": {},
        }
    try:
        session = restore_session_from_login_context(parsed_context, proxy_key=email)
        login_state = check_login_state(session)
        logged_in = bool(login_state.get("loggedIn"))
        print(
            f"[livepocket.keepalive] /my_top trace={trace_id}, email={email}, "
            f"loggedIn={logged_in}, status={login_state.get('status')}, "
            f"finalUrl={login_state.get('finalUrl')}, title={login_state.get('title') or '-'}"
        )
        result = {
            "success": True,
            "loggedIn": logged_in,
            "status": "logged_in" if logged_in else "offline",
            "message": "登录态有效" if logged_in else "登录上下文已失效",
            "authSource": "context",
            "loginState": login_state,
        }
        if logged_in:
            result["loginReqData"] = json.dumps(export_session_login_context(session), ensure_ascii=False)
        return result
    except Exception as exc:
        return {
            "success": False,
            "loggedIn": False,
            "status": "error",
            "message": f"登录态保活异常: {exc}",
            "authSource": "context",
            "loginState": {},
        }
