"""
livepocket.jp 注册功能 - 修复版(两次POST)
"""
import requests
import time
import re
import uuid
import random
import os
import html
import json
from capsolver_captcha import CaptchaSolver, capsolver_api_key
from livepocket_login import LivePocketLogin, submit_email_code_with_retry
from backend_api import TicketBackendApi
from ticket_runtime import builtins_print, env_text, get_logger, log_context
from livepocket_proxy import (
    LivePocketProxy,
    LivePocketProxyError,
    apply_proxy_to_session,
    get_proxy,
    is_blocked_livepocket_response,
    mark_proxy_failed,
    proxy_max_attempts,
)
from redis_events import REGISTER_RESULT_STREAM, RedisEventPublisher
from typing import Any, Dict, Optional

LOGGER = get_logger("livepocket.register")
print = LOGGER.print
DEFAULT_LIVEPOCKET_FIRST_NAME = "￻"

class LivePocketRegister:
    """livepocket.jp 注册"""

    def __init__(self, captcha_solver, use_proxy=False, proxy: Optional[LivePocketProxy] = None, proxy_key: str = ""):
        self.session = requests.Session()
        self.captcha_solver = captcha_solver
        self.use_proxy = use_proxy
        self.proxy_key = proxy_key
        self.proxy = proxy if proxy is not None else get_proxy(proxy_key)
        apply_proxy_to_session(self.session, self.proxy)
        if self.proxy:
            print(f"[Proxy] LivePocket 注册使用代理: {self.proxy.masked()}, key={self.proxy_key or '-'}")

        self.site_key = '6Ld50ncqAAAAAJuHR7I6dNVXfnKme_WTP2SKS168'
        self.signup_url = 'https://livepocket.jp/sign_up'


        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Referer': 'https://livepocket.jp/',
            'Origin': 'https://livepocket.jp',
            'Connection': 'keep-alive'
        })

    @staticmethod
    def _extract_input_value(html_text, input_name):
        for match in re.finditer(r'<input\b[^>]*>', html_text or '', flags=re.I | re.S):
            tag = match.group(0)
            name_match = re.search(r'\bname\s*=\s*([\'"])(.*?)\1', tag, flags=re.I | re.S)
            if not name_match or name_match.group(2) != input_name:
                continue
            value_match = re.search(r'\bvalue\s*=\s*([\'"])(.*?)\1', tag, flags=re.I | re.S)
            return html.unescape(value_match.group(2)) if value_match else ''
        return None

    @staticmethod
    def _extract_meta_content(html_text, meta_name):
        pattern = rf'<meta\b(?=[^>]*\bname\s*=\s*([\'"]){re.escape(meta_name)}\1)[^>]*>'
        for match in re.finditer(pattern, html_text or '', flags=re.I | re.S):
            content_match = re.search(r'\bcontent\s*=\s*([\'"])(.*?)\1', match.group(0), flags=re.I | re.S)
            if content_match:
                return html.unescape(content_match.group(2))
        return None

    @staticmethod
    def _extract_page_title(html_text):
        match = re.search(r'<title[^>]*>(.*?)</title>', html_text or '', flags=re.I | re.S)
        return re.sub(r'\s+', ' ', html.unescape(match.group(1))).strip() if match else ''

    @staticmethod
    def _browser_executable_path():
        candidates = [
            os.environ.get('LIVEPOCKET_BROWSER_PATH', ''),
            '/usr/bin/google-chrome',
            '/usr/bin/google-chrome-stable',
            '/usr/bin/chromium',
            '/usr/bin/chromium-browser',
            '/snap/bin/chromium',
            r'C:\Program Files\Google\Chrome\Application\chrome.exe',
            r'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
            r'C:\Program Files\Microsoft\Edge\Application\msedge.exe',
            r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe',
        ]
        return next((path for path in candidates if path and os.path.exists(path)), None)

    @staticmethod
    def _is_waf_challenge(resp):
        if resp is None:
            return False
        waf_action = resp.headers.get('x-amzn-waf-action', '')
        body = resp.text or ''
        return (
            resp.status_code == 202
            or waf_action.lower() == 'challenge'
            or 'AwsWafIntegration' in body
            or ('challenge.js' in body and 'awswaf.com' in body)
        )

    def _refresh_waf_cookies_with_browser(self):
        """Run the AWS WAF JavaScript challenge in a real browser and copy cookies back."""
        try:
            from playwright.sync_api import sync_playwright
        except Exception as exc:
            print(f"[X] AWS WAF challenge detected, but Playwright is unavailable: {exc}")
            return False

        browser_path = self._browser_executable_path()
        print(f"[1] 检测到 AWS WAF challenge，使用浏览器获取注册 Cookie: {browser_path or 'playwright-default'}")
        try:
            with sync_playwright() as playwright:
                launch_options = {"headless": True}
                if browser_path:
                    launch_options["executable_path"] = browser_path
                if self.proxy:
                    launch_options["proxy"] = self.proxy.playwright_proxy()
                browser = playwright.chromium.launch(**launch_options)
                context = browser.new_context(
                    user_agent=self.session.headers.get('User-Agent'),
                    locale='zh-CN',
                    extra_http_headers={
                        'Accept-Language': self.session.headers.get('Accept-Language', 'zh-CN,zh;q=0.9,en;q=0.8')
                    },
                )
                page = context.new_page()
                page.goto(self.signup_url, wait_until='domcontentloaded', timeout=60000)
                try:
                    page.wait_for_function(
                        "() => document.documentElement.innerHTML.includes('authenticity_token')",
                        timeout=20000,
                    )
                except Exception:
                    page.wait_for_timeout(8000)
                content = page.content()
                cookies = context.cookies('https://livepocket.jp')
                for cookie in cookies:
                    self.session.cookies.set(
                        cookie.get('name'),
                        cookie.get('value'),
                        domain=cookie.get('domain') or '.livepocket.jp',
                        path=cookie.get('path') or '/',
                    )
                browser.close()
                print(f"[1] 注册 Cookie 获取完成: hasToken={bool('authenticity_token' in content)}, cookieCount={len(cookies)}")
                return bool(cookies)
        except Exception as exc:
            print(f"[X] AWS WAF 注册 Cookie 获取失败: {exc}")
            return False

    def register(self, email, password, last_name, first_name, phone_number, sex='male', birthday='2006-04-22'):
        """执行注册 - 模仿登录的两次POST"""
        print("[Register] 开始 LivePocket 注册")
        print(f"[Register] 姓: {last_name}")
        print(f"[Register] 名: {first_name!r} codepoints={[hex(ord(c)) for c in first_name]}")

        # 步骤1: 获取注册页面token
        print("[1] Getting signup page...")
        time.sleep(random.uniform(1.5, 3.0))

        try:
            resp = self.session.get(self.signup_url)
        except requests.RequestException as exc:
            if self.proxy:
                mark_proxy_failed(self.proxy, reason=f"注册页请求异常: {exc}", key=self.proxy_key)
            raise LivePocketProxyError(f"注册页请求异常: {exc}") from exc
        if self._is_waf_challenge(resp) or not self._extract_input_value(resp.text, 'authenticity_token'):
            if not self._refresh_waf_cookies_with_browser():
                reason = (
                    f"注册页浏览器 Cookie 处理失败: status={resp.status_code}, finalUrl={resp.url}, "
                    f"title={self._extract_page_title(resp.text) or '-'}"
                )
                if self.proxy:
                    mark_proxy_failed(self.proxy, reason=reason, key=self.proxy_key)
                raise LivePocketProxyError(reason)
            resp = self.session.get(self.signup_url)
        if is_blocked_livepocket_response(resp, require_token=False):
            reason = f"注册页代理访问失败: status={resp.status_code}, finalUrl={resp.url}, title={self._extract_page_title(resp.text) or '-'}"
            if self.proxy:
                mark_proxy_failed(self.proxy, reason=reason, key=self.proxy_key)
            raise LivePocketProxyError(reason)
        authenticity_token = self._extract_input_value(resp.text, 'authenticity_token')
        csrf_token = self._extract_meta_content(resp.text, 'csrf-token') or authenticity_token

        if not authenticity_token:
            excerpt = re.sub(r'\s+', ' ', (resp.text or '')[:300])
            reason = (
                "注册页未找到 authenticity_token"
                f" (status={resp.status_code}, finalUrl={resp.url}, title={self._extract_page_title(resp.text) or '-'}, excerpt={excerpt})"
            )
            if self.proxy:
                mark_proxy_failed(self.proxy, reason=reason, key=self.proxy_key)
            raise LivePocketProxyError(reason)

        print(f"[1] Token: {authenticity_token[:50]}...")

        # 步骤2: 解决验证码
        print("[2] Solving reCAPTCHA v2...")
        recaptcha_response = self.captcha_solver.solve_recaptcha_v2(
            site_key=self.site_key,
            page_url=self.signup_url,
            enterprise=False
        )
        print(f"[2] Token: {recaptcha_response[:80]}...")

        time.sleep(random.uniform(2.0, 4.0))

        year, month, day = birthday.split('-')

        # 步骤3: 第一次POST - 使用 g-recaptcha-response-data[sign_up]
        print("[3] First POST with g-recaptcha-response-data[sign_up]...")

        form_data = {
            'authenticity_token': authenticity_token,
            'user[email]': email,
            'user[password]': password,
            'user[password_confirmation]': password,
            'user[last_name]': last_name,
            'user[first_name]': first_name,
            'user[sex]': sex,
            'user[birth_year]': year,
            'user[birth_month]': month,
            'user[birth_day]': day,
            'user[unconfirmed_phone_country_id]': '107',
            'user[unconfirmed_phone_number]': phone_number,
            'user[prefecture_id]': '14',
            'user[country_id]': '6',
            'user[language]': 'ja',
            'user[is_newsletter_sendable]': '1',
            'user[agree_terms]': '1',
            'g-recaptcha-response-data[sign_up]': recaptcha_response,
            'g-recaptcha-response': ''
        }

        headers = {
            'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            'X-CSRF-Token': csrf_token,
            'X-Turbo-Request-Id': str(uuid.uuid4()),
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
        }

        first_request = requests.Request(
            'POST',
            'https://livepocket.jp/sign_up/confirm',
            data=form_data,
            headers=headers,
        )
        prepared_first_request = self.session.prepare_request(first_request)
        builtins_print(f"[livepocket.register.raw] [3] First POST outgoing body: {prepared_first_request.body}", flush=True)
        resp = self.session.send(prepared_first_request, allow_redirects=False)

        print(f"[3] First POST response: {resp.status_code}")
        validation_text = re.sub(r'\s+', ' ', re.sub(r'<[^>]+>', ' ', resp.text or '')).strip()
        if "入力してください" in validation_text or "氏名" in validation_text:
            print(f"[3] First POST validation text: {validation_text[:500]}")

        if resp.status_code != 200:
            print("[X] First POST failed")
            return False

        time.sleep(random.uniform(1.5, 3.0))

        # 步骤4: 第二次POST - 使用完整的 g-recaptcha-response
        print("[4] Second POST with g-recaptcha-response...")

        # 提取新token
        token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
        if token_match:
            authenticity_token = token_match.group(1)

        # 修改form_data,使用完整token
        form_data['authenticity_token'] = authenticity_token
        form_data['g-recaptcha-response'] = recaptcha_response
        del form_data['g-recaptcha-response-data[sign_up]']

        # 更新headers中的X-Turbo-Request-Id
        headers['X-Turbo-Request-Id'] = str(uuid.uuid4())

        print(f"[DEBUG] Second POST form_data keys: {list(form_data.keys())}")

        second_request = requests.Request(
            'POST',
            'https://livepocket.jp/sign_up/confirm',
            data=form_data,
            headers=headers,
        )
        prepared_second_request = self.session.prepare_request(second_request)
        builtins_print(f"[livepocket.register.raw] [4] Second POST outgoing body: {prepared_second_request.body}", flush=True)
        resp = self.session.send(prepared_second_request, allow_redirects=False)

        print(f"[4] Second POST response: {resp.status_code}")
        if resp.status_code != 200:
            print(f"[DEBUG] Response text: {resp.text[:1000]}")

        if resp.status_code != 200:
            print("[X] Second POST failed")
            return False

        # 检查confirm页面是否成功
        if 'もう一度' in resp.text:
            print("[X] Captcha verification failed")
            return False

        time.sleep(random.uniform(1.5, 3.0))

        # 步骤5: 最终提交
        print("[5] Final submit...")

        token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
        if token_match:
            authenticity_token = token_match.group(1)

        final_data = {
            'authenticity_token': authenticity_token,
            'button': ''
        }

        resp = self.session.post(
            'https://livepocket.jp/sign_up',
            data=final_data,
            headers=headers,
            allow_redirects=False
        )

        print(f"[5] Final response: {resp.status_code}")

        if resp.status_code == 302:
            location = resp.headers.get('Location', '')
            print(f"[OK] Registration success! Redirect to: {location}")
            return True
        else:
            print("[X] Registration failed")
            return False


def convert_gender(gender: Any) -> str:
    value = str(gender or "").strip().lower()
    if value == "male":
        return "male"
    if value == "female":
        return "female"
    return "unselect"


def format_birthday(year: Any, month: Any, day: Any) -> str:
    return f"{int(year):04d}-{int(month):02d}-{int(day):02d}"


def activate_email(activation_url: str, proxy: Optional[LivePocketProxy] = None) -> bool:
    session = requests.Session()
    session.headers.update({"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"})
    apply_proxy_to_session(session, proxy)
    resp = session.get(activation_url, allow_redirects=True, timeout=30)
    return resp.status_code == 200


def verify_phone(login: LivePocketLogin, phone_number: str, backend: TicketBackendApi, email: str) -> bool:
    print(f"[Phone] 开始手机号验证: email={email}, phone={phone_number}")
    mobile_headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0"
    }
    print("[Phone] 获取手机号编辑页...")
    resp = login.session.get(
        "https://livepocket.jp/account/phone_number/edit",
        headers=mobile_headers,
        allow_redirects=False,
        timeout=60,
    )
    print(f"[Phone] 手机号编辑页响应: status={resp.status_code}, finalUrl={resp.url}")
    if resp.status_code == 302:
        redirect_url = resp.headers.get("Location", "")
        full_redirect_url = redirect_url if redirect_url.startswith("http") else f"https://livepocket.jp{redirect_url}"
        print(f"[Phone] 手机号编辑页跳转: {full_redirect_url}")
        resp = login.session.get(full_redirect_url, headers=mobile_headers, timeout=60)
        print(f"[Phone] 手机号编辑页跳转后响应: status={resp.status_code}, finalUrl={resp.url}")

    token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
    csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)
    if not token_match:
        raise RuntimeError("手机验证页未找到 authenticity_token")
    authenticity_token = token_match.group(1)
    csrf_token = csrf_match.group(1) if csrf_match else authenticity_token

    phone_data = {
        "_method": "put",
        "authenticity_token": authenticity_token,
        "user[unconfirmed_phone_country_id]": "107",
        "user[unconfirmed_phone_number]": phone_number,
        "user[auth_method]": "sms",
        "button": "",
    }
    phone_headers = {
        **mobile_headers,
        "Accept": "text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "X-CSRF-Token": csrf_token,
        "X-Turbo-Request-Id": str(uuid.uuid4()),
        "Referer": "https://livepocket.jp/account/phone_number/edit",
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
    }
    print("[Phone] 提交手机号...")
    resp = login.session.post(
        "https://livepocket.jp/account/phone_number",
        data=phone_data,
        headers=phone_headers,
        allow_redirects=False,
        timeout=60,
    )
    print(f"[Phone] 手机号提交响应: status={resp.status_code}, location={resp.headers.get('Location', '')}")
    if resp.status_code != 302:
        raise RuntimeError(f"手机号提交失败: HTTP {resp.status_code}")
    verify_url = resp.headers.get("Location", "")
    if not verify_url:
        raise RuntimeError("手机号提交后未返回验证地址")

    from urllib.parse import parse_qs, urlparse

    parsed = urlparse(verify_url)
    sms_token = parse_qs(parsed.query).get("sms_verifies_token", [""])[0]
    full_verify_url = verify_url if verify_url.startswith("http") else f"https://livepocket.jp{verify_url}"
    print(f"[Phone] 获取短信验证码页: {full_verify_url}")
    resp = login.session.get(full_verify_url, headers=mobile_headers, timeout=60)
    print(f"[Phone] 短信验证码页响应: status={resp.status_code}, finalUrl={resp.url}")
    token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
    csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)
    if not token_match:
        raise RuntimeError("短信验证码页未找到 authenticity_token")

    print("[Phone] 开始获取短信验证码...")
    verify_code = backend.phone_activation_code(email)
    if not verify_code:
        raise RuntimeError("未收到短信验证码")

    verify_data = {
        "authenticity_token": token_match.group(1),
        "sms_verifies_token": sms_token,
        "user[auth_code]": verify_code,
        "button": "",
    }
    verify_headers = {
        **mobile_headers,
        "Accept": "text/vnd.turbo-stream.html, text/html, application/xhtml+xml",
        "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8",
        "X-CSRF-Token": csrf_match.group(1) if csrf_match else token_match.group(1),
        "X-Turbo-Request-Id": str(uuid.uuid4()),
        "Referer": full_verify_url,
        "Sec-Fetch-Mode": "cors",
        "Sec-Fetch-Dest": "empty",
    }
    print(f"[Phone] 提交短信验证码: {verify_code}")
    resp = login.session.post(
        "https://livepocket.jp/account/phone_number/sms_verify",
        data=verify_data,
        headers=verify_headers,
        allow_redirects=False,
        timeout=60,
    )
    print(f"[Phone] 短信验证码提交响应: status={resp.status_code}, location={resp.headers.get('Location', '')}")
    return resp.status_code in (200, 302, 303)


def register_one_account(
    backend: TicketBackendApi,
    batch_id: Any = None,
    index: Any = None,
    total: Any = None,
    platform_code: str = "",
) -> Dict[str, Any]:
    with log_context(flow="register", stage="allocate", batchId=batch_id, index=index, total=total, platformCode=platform_code):
        register_data = backend.next_register()
        if not register_data:
            raise RuntimeError("获取注册信息失败")

    email = register_data.get("email")
    account_id = register_data.get("accountId")
    livepocket_last_name = f"{register_data.get('familyName', '')}  {register_data.get('givenName', '')}".strip()
    livepocket_first_name = DEFAULT_LIVEPOCKET_FIRST_NAME
    last_error = ""
    with log_context(
        flow="register",
        stage="account",
        batchId=batch_id,
        index=index,
        total=total,
        platformCode=platform_code,
        accountId=account_id,
        email=email,
    ):
        print("[Register] 开始处理账号")
        for attempt in range(1, proxy_max_attempts() + 1):
            proxy = get_proxy(email, refresh=attempt > 1)
            captcha_solver = CaptchaSolver(api_key=capsolver_api_key())
            register = LivePocketRegister(captcha_solver, use_proxy=False, proxy=proxy, proxy_key=email)
            try:
                with log_context(stage="signup", proxyAttempt=attempt):
                    ok = register.register(
                        email=email,
                        password=register_data.get("password"),
                        last_name=livepocket_last_name,
                        first_name=livepocket_first_name,
                        phone_number=register_data.get("phoneNumber"),
                        sex=convert_gender(register_data.get("gender")),
                        birthday=format_birthday(register_data.get("birthYear"), register_data.get("birthMonth"), register_data.get("birthDay")),
                    )
                if not ok:
                    return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": "LivePocket 注册失败"}

                with log_context(stage="email-activation", proxyAttempt=attempt):
                    activation_url = backend.email_activation_link(email)
                    if not activation_url or not activate_email(activation_url, proxy=proxy):
                        return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": "邮箱激活失败或未收到激活链接"}

                with log_context(stage="register-login", proxyAttempt=attempt):
                    login = LivePocketLogin(captcha_solver, use_proxy=False, proxy=proxy, proxy_key=email)
                    login_success = login.login(email=email, password=register_data.get("password"))
                    if not login_success and getattr(login, "_need_email_code", False):
                        login_success = submit_email_code_with_retry(
                            login,
                            email,
                            lambda: backend.email_verify_code(email, attempts=5, interval_seconds=2),
                            max_attempts=3,
                            label="RegisterLogin",
                        )
                    if not login_success:
                        return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": "注册后登录失败"}

                with log_context(stage="phone-verify", proxyAttempt=attempt):
                    if not verify_phone(login, register_data.get("phoneNumber"), backend, email):
                        return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": "手机号验证失败"}
                    login_req_data = login.export_login_context()
                break
            except LivePocketProxyError as exc:
                last_error = str(exc)
                if attempt < proxy_max_attempts():
                    print(f"[Register] LivePocket 代理失败，切换代理重试: {attempt}/{proxy_max_attempts()}, email={email}, error={exc}")
                    continue
                return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": f"LivePocket 代理访问失败：{last_error}"}

    return {
        "accountId": account_id,
        "email": email,
        "success": True,
        "status": "success",
        "message": "注册并激活成功",
        "accountInfo": {
            "familyName": register_data.get("familyName"),
            "givenName": register_data.get("givenName"),
            "platformPassword": register_data.get("password"),
        },
        "loginReqData": json.dumps(login_req_data, ensure_ascii=False),
    }


def run_register_batch(
    batch_id: Any,
    platform_code: str,
    count: int,
    backend_base_url: str,
    result_stream: str = REGISTER_RESULT_STREAM,
) -> None:
    backend = TicketBackendApi(backend_base_url, platform_code)
    publisher = RedisEventPublisher()
    total = max(int(count or 0), 0)
    success_count = 0
    failed_count = 0
    for index in range(1, total + 1):
        try:
            result = register_one_account(
                backend,
                batch_id=batch_id,
                index=index,
                total=total,
                platform_code=platform_code,
            )
        except Exception as exc:
            result = {"success": False, "status": "failed", "message": f"注册异常：{exc}"}
        if result.get("success"):
            success_count += 1
        else:
            failed_count += 1
        result.update({
            "eventType": "account_result",
            "batchId": batch_id,
            "platformCode": platform_code,
            "index": index,
            "totalCount": total,
            "successCount": success_count,
            "failedCount": failed_count,
        })
        publisher.publish(result_stream, result)

    publisher.publish(result_stream, {
        "eventType": "batch_completed",
        "batchId": batch_id,
        "platformCode": platform_code,
        "totalCount": total,
        "successCount": success_count,
        "failedCount": failed_count,
        "status": "partial" if failed_count else "completed",
        "message": "注册批次完成",
    })
