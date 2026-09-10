"""
livepocket.jp 登录协议实现 - 支持 Cloudflare Turnstile 和旧版 Google reCAPTCHA v2
使用打码平台: CapSolver
"""
import requests
import time
import re
import uuid
import random
import html
import os
from html.parser import HTMLParser
from datetime import datetime
from urllib.parse import urlencode, quote
from typing import Any, Callable, Dict, List, Optional

from backend_api import TicketBackendApi
from ticket_runtime import get_logger, log_context
from capsolver_captcha import CaptchaSolver, capsolver_api_key
from livepocket_proxy import (
    LivePocketProxy,
    LivePocketProxyError,
    apply_proxy_to_session,
    get_proxy,
    is_blocked_livepocket_response,
    mark_proxy_failed,
    proxy_max_attempts,
)
from redis_events import LOGIN_RESULT_STREAM, RedisEventPublisher, env_text


LOGGER = get_logger("livepocket.login")
print = LOGGER.print


class _TurnstileConfigParser(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.detected = False
        self.site_key = ""
        self.action = ""
        self.cdata = ""

    def handle_starttag(self, tag, attrs):
        attributes = {str(name or "").lower(): str(value or "") for name, value in attrs}
        class_names = set(attributes.get("class", "").split())
        controllers = set(attributes.get("data-controller", "").split())
        script_src = attributes.get("src", "").lower()
        input_name = attributes.get("name", "").lower()
        is_widget = (
            "turnstile" in controllers
            or "turnstile-widget" in class_names
            or "cf-turnstile" in class_names
        )
        if (
            is_widget
            or input_name == "cf-turnstile-response"
            or "challenges.cloudflare.com/turnstile/" in script_src
        ):
            self.detected = True

        site_key = attributes.get("data-turnstile-sitekey-value", "")
        if not site_key and is_widget:
            site_key = attributes.get("data-sitekey", "")
        if site_key and not self.site_key:
            self.site_key = site_key
            self.action = (
                attributes.get("data-turnstile-action-value", "")
                or attributes.get("data-action", "")
            )
            self.cdata = (
                attributes.get("data-turnstile-cdata-value", "")
                or attributes.get("data-cdata", "")
            )


def extract_turnstile_config(html_text: str) -> Dict[str, Any]:
    parser = _TurnstileConfigParser()
    parser.feed(html_text or "")
    parser.close()
    return {
        "detected": parser.detected,
        "siteKey": parser.site_key,
        "action": parser.action,
        "cdata": parser.cdata,
    }


class LivePocketLogin:
    """livepocket.jp 登录"""

    def __init__(self, captcha_solver, use_proxy=False, proxy: Optional[LivePocketProxy] = None, proxy_key: str = ""):
        self.session = requests.Session()
        self.captcha_solver = captcha_solver
        self.use_proxy = use_proxy
        self.proxy_key = proxy_key
        self.proxy = proxy if proxy is not None else get_proxy(proxy_key)
        apply_proxy_to_session(self.session, self.proxy)
        if self.proxy:
            print(f"[Proxy] LivePocket 登录使用代理: {self.proxy.masked()}, key={self.proxy_key or '-'}")

        # 旧版 reCAPTCHA 回退配置；Turnstile 参数从登录页动态提取。
        self.site_key = '6Ld50ncqAAAAAJuHR7I6dNVXfnKme_WTP2SKS168'
        self.login_url = 'https://livepocket.jp/login'
        self.turnstile_config = {"detected": False, "siteKey": "", "action": "", "cdata": ""}

        # 设置请求头（使用手机模式 User-Agent）
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Referer': 'https://livepocket.jp/',
            'Origin': 'https://livepocket.jp',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1',
            'Sec-Fetch-Dest': 'document',
            'Sec-Fetch-Mode': 'navigate',
            'Sec-Fetch-Site': 'same-origin',
            'Sec-Fetch-User': '?1'
        })

    @staticmethod
    def _extract_input_value(html_text, input_name):
        """Extract an input value regardless of HTML attribute order."""
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
    def _safe_console_text(value):
        text = str(value or '')
        return text.encode('gbk', errors='backslashreplace').decode('gbk')

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
            or 'challenge.js' in body and 'awswaf.com' in body
        )

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

    def _refresh_waf_cookies_with_browser(self):
        """Run the AWS WAF JavaScript challenge in a real browser and copy cookies back."""
        browser_path = self._browser_executable_path()
        try:
            from playwright.sync_api import sync_playwright
        except Exception as exc:
            print(f"[X] AWS WAF challenge detected, but Playwright is unavailable: {exc}")
            return False

        print(f"[1] 检测到 AWS WAF challenge，使用浏览器获取 Cookie: {browser_path or 'playwright-default'}")
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
                page.goto(self.login_url, wait_until='domcontentloaded', timeout=60000)
                try:
                    page.wait_for_function(
                        "() => document.documentElement.innerHTML.includes('authenticity_token')",
                        timeout=20000,
                    )
                except Exception:
                    page.wait_for_timeout(8000)
                content = page.content()
                jar_items = context.cookies('https://livepocket.jp')
                for cookie in jar_items:
                    self.session.cookies.set(
                        cookie.get('name'),
                        cookie.get('value'),
                        domain=cookie.get('domain') or '.livepocket.jp',
                        path=cookie.get('path') or '/',
                    )
                auth_input_found = bool(self._extract_input_value(content, 'authenticity_token'))
                print(
                    "[1] 浏览器 Cookie 获取完成: "
                    f"title={self._safe_console_text(self._extract_page_title(content) or '-')}, "
                    f"authInputFound={auth_input_found}, "
                    f"browserJarCount={len(jar_items)}, "
                    f"browserJarNames={[cookie.get('name') for cookie in jar_items][:12]}"
                )
                browser.close()
                return bool(jar_items) or auth_input_found
        except Exception as exc:
            print(f"[X] AWS WAF Cookie 获取失败: {exc}")
            return False

    def export_login_context(self):
        """导出当前 Session 的完整登录上下文。

        数据只来自 auto-py 当前 requests.Session：
        - cookiesJson/cookies: 程序读取用
        - cookieHeader: curl -b 可直接使用的字符串
        - headers/userAgent: 当前 Session 请求头快照
        """
        cookies = []
        cookie_dict = {}
        cookie_pairs = []

        for cookie in self.session.cookies:
            cookie_item = {
                'name': cookie.name,
                'value': cookie.value,
                'domain': cookie.domain,
                'path': cookie.path or '/',
                'secure': bool(cookie.secure),
                'expires': cookie.expires
            }
            cookies.append(cookie_item)
            cookie_dict[cookie.name] = cookie.value
            cookie_pairs.append(f'{cookie.name}={cookie.value}')

        headers = dict(self.session.headers)

        return {
            'format': 'auto-py-livepocket-login-context-v1',
            'exportedAt': datetime.now().isoformat(timespec='seconds'),
            'loginUrl': self.login_url,
            'userAgent': headers.get('User-Agent', ''),
            'headers': headers,
            'cookies': cookies,
            'cookiesJson': cookie_dict,
            'cookieHeader': '; '.join(cookie_pairs),
            'proxy': {
                'enabled': bool(self.proxy),
                'provider': self.proxy.provider if self.proxy else '',
                'region': self.proxy.region if self.proxy else '',
                'host': self.proxy.host if self.proxy else '',
                'port': self.proxy.port if self.proxy else '',
            }
        }

    def _mark_proxy_failed(self, reason: str) -> None:
        if self.proxy:
            mark_proxy_failed(self.proxy, reason=reason, key=self.proxy_key)

    def get_authenticity_token(self):
        """获取登录页面的 CSRF token"""
        print("[1] 获取登录页面...")

        # 模拟人类延迟
        time.sleep(random.uniform(1.5, 3.0))

        try:
            resp = self.session.get(self.login_url, allow_redirects=True)
        except requests.RequestException as exc:
            self._mark_proxy_failed(f"登录页请求异常: {exc}")
            raise LivePocketProxyError(f"登录页请求异常: {exc}") from exc
        print(
            f"[1] 登录页响应: status={resp.status_code}, finalUrl={resp.url}, "
            f"bytes={len(resp.text or '')}, title={self._safe_console_text(self._extract_page_title(resp.text) or '-')}, "
            f"waf={self._is_waf_challenge(resp)}"
        )

        if self._is_waf_challenge(resp):
            if not self._refresh_waf_cookies_with_browser():
                reason = (
                    f"登录页 WAF challenge 处理失败: status={resp.status_code}, "
                    f"finalUrl={resp.url}"
                )
                self._mark_proxy_failed(reason)
                raise LivePocketProxyError(reason)
            try:
                resp = self.session.get(self.login_url, allow_redirects=True)
            except requests.RequestException as exc:
                reason = f"登录页 WAF 后重试请求异常: {exc}"
                self._mark_proxy_failed(reason)
                raise LivePocketProxyError(reason) from exc
            print(
                f"[1] WAF 后登录页响应: status={resp.status_code}, finalUrl={resp.url}, "
                f"bytes={len(resp.text or '')}, title={self._safe_console_text(self._extract_page_title(resp.text) or '-')}, "
                f"waf={self._is_waf_challenge(resp)}"
            )

        if is_blocked_livepocket_response(resp, require_token=True):
            reason = (
                f"登录页代理访问失败: status={resp.status_code}, finalUrl={resp.url}, "
                f"title={self._safe_console_text(self._extract_page_title(resp.text) or '-')}"
            )
            self._mark_proxy_failed(reason)
            raise LivePocketProxyError(reason)

        token = self._extract_input_value(resp.text, 'authenticity_token')
        if not token:
            excerpt = re.sub(r'\s+', ' ', (resp.text or '')[:500])
            reason = (
                "未找到 authenticity_token"
                f" (status={resp.status_code}, finalUrl={resp.url}, title={self._safe_console_text(self._extract_page_title(resp.text) or '-')}, "
                f"waf={self._is_waf_challenge(resp)}, excerpt={self._safe_console_text(excerpt)})"
            )
            self._mark_proxy_failed(reason)
            raise LivePocketProxyError(reason)

        csrf_token = self._extract_meta_content(resp.text, 'csrf-token') or token
        self.turnstile_config = extract_turnstile_config(resp.text)
        if self.turnstile_config["detected"] and not self.turnstile_config["siteKey"]:
            raise RuntimeError("登录页检测到 Cloudflare Turnstile，但未找到 site key")

        print(f"[1] authenticity_token: {token[:50]}...")
        print(f"[1] csrf_token: {csrf_token[:50]}...")
        if self.turnstile_config["detected"]:
            print(
                "[1] Turnstile 配置: "
                f"siteKey={self.turnstile_config['siteKey']}, "
                f"action={self.turnstile_config['action'] or '-'}, "
                f"hasCdata={bool(self.turnstile_config['cdata'])}"
            )

        # 模拟阅读页面
        time.sleep(random.uniform(2.0, 4.0))

        return token, csrf_token

    def submit_email_code(self, email_code):
        """提交邮件验证码"""
        print(f"[5] 提交邮件验证码: {email_code}")

        # 模拟人类延迟
        time.sleep(random.uniform(2.0, 4.0))

        # 访问验证码页面获取新的token
        resp = self.session.get('https://livepocket.jp/login/code_auth')
        if self._is_waf_challenge(resp):
            self._refresh_waf_cookies_with_browser()
            resp = self.session.get('https://livepocket.jp/login/code_auth')
        if is_blocked_livepocket_response(resp, require_token=True):
            reason = f"验证码页代理访问失败: status={resp.status_code}, finalUrl={resp.url}"
            self._mark_proxy_failed(reason)
            raise LivePocketProxyError(reason)

        authenticity_token = self._extract_input_value(resp.text, 'authenticity_token')
        if not authenticity_token:
            print(
                "[X] Failed to get authenticity_token from code_auth page: "
                f"status={resp.status_code}, finalUrl={resp.url}, title={self._safe_console_text(self._extract_page_title(resp.text) or '-')}, "
                f"waf={self._is_waf_challenge(resp)}"
            )
            return False

        csrf_token = self._extract_meta_content(resp.text, 'csrf-token') or authenticity_token

        # 模拟输入验证码
        time.sleep(random.uniform(1.5, 3.0))

        # 提交验证码
        form_data = {
            'authenticity_token': authenticity_token,
            'user[login_auth_code]': email_code
        }

        headers = {
            'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            'X-CSRF-Token': csrf_token,
            'X-Turbo-Request-Id': str(uuid.uuid4()),
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
        }

        resp = self.session.post(
            'https://livepocket.jp/login/code_auth',
            data=form_data,
            headers=headers,
            allow_redirects=False
        )

        print(f"[5] Response status: {resp.status_code}")

        if resp.status_code in [302, 303]:
            location = resp.headers.get('Location', '')
            print(f"[OK] Email verification success! Redirect to: {location}")
            return True
        elif resp.status_code == 200:
            if 'error' in resp.text.lower() or 'invalid' in resp.text.lower():
                print(f"[X] Invalid email code")
            else:
                print(f"[X] Verification failed")
            return False
        else:
            print(f"[X] Unexpected status: {resp.status_code}")
            return False

    def login(self, email, password, email_code=None):
        """执行登录"""
        # 步骤1: 获取 CSRF token
        authenticity_token, csrf_token = self.get_authenticity_token()

        # 添加关键请求头 (Turbo框架)
        headers = {
            'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            'Referer': self.login_url,
            'X-CSRF-Token': csrf_token,
            'X-Turbo-Request-Id': str(uuid.uuid4()),
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
        }

        if self.turnstile_config["detected"]:
            print("[2] 调用打码平台解决 Cloudflare Turnstile...")
            turnstile_response = self.captcha_solver.solve_turnstile(
                site_key=self.turnstile_config["siteKey"],
                page_url=self.login_url,
                action=self.turnstile_config["action"] or None,
                cdata=self.turnstile_config["cdata"] or None,
            )
            print(f"[2] cf-turnstile-response 已获取: tokenLength={len(turnstile_response)}")
            form_data = {
                'authenticity_token': authenticity_token,
                'user[email]': email,
                'user[password]': password,
                'cf-turnstile-response': turnstile_response,
                'commit': ''
            }
            print("[3] 提交 Turnstile 登录表单...")
            resp = self.session.post(
                self.login_url,
                data=form_data,
                headers=headers,
                allow_redirects=False
            )
            print(f"[3] Turnstile 登录响应: {resp.status_code}")
        else:
            print("[2] 调用打码平台解决 reCAPTCHA v2...")
            recaptcha_response = self.captcha_solver.solve_recaptcha_v2(
                site_key=self.site_key,
                page_url=self.login_url
            )
            print(f"[2] g-recaptcha-response: {recaptcha_response[:80]}...")

            print("[3] 提交登录表单（第一次POST）...")
            form_data = {
                'authenticity_token': authenticity_token,
                'user[email]': email,
                'user[password]': password,
                'g-recaptcha-response-data[login]': recaptcha_response,
                'g-recaptcha-response': '',
                'commit': ''
            }
            resp = self.session.post(
                self.login_url,
                data=form_data,
                headers=headers,
                allow_redirects=False
            )
            print(f"[3] 第一次POST响应: {resp.status_code}")
            if resp.status_code != 200:
                print("[X] 第一次POST失败")
                return False

            time.sleep(random.uniform(1.5, 3.0))
            print("[4] 提交登录表单（第二次POST）...")
            next_token = self._extract_input_value(resp.text, 'authenticity_token')
            if next_token:
                authenticity_token = next_token
            form_data['authenticity_token'] = authenticity_token
            form_data['g-recaptcha-response'] = recaptcha_response
            del form_data['g-recaptcha-response-data[login]']
            headers['X-Turbo-Request-Id'] = str(uuid.uuid4())
            resp = self.session.post(
                self.login_url,
                data=form_data,
                headers=headers,
                allow_redirects=False
            )
            print(f"[4] 第二次POST响应: {resp.status_code}")

        # 检查登录结果
        if resp.status_code in [302, 303]:
            location = resp.headers.get('Location', '')
            print(f"[OK] Login success! Redirect to: {location}")

            # 获取 session cookie
            cookies = self.session.cookies.get_dict()
            print(f"[OK] Session Cookie: {cookies.get('_session', 'N/A')[:50]}...")

            # 如果跳转到邮件验证页面，访问它（新注册账号会自动302跳转到my_top）
            if 'code_auth' in location:
                print(f"[!] Checking email verification page...")
                # location是相对路径，需要拼接完整URL
                full_url = f'https://livepocket.jp{location}' if location.startswith('/') else location
                resp = self.session.get(full_url, allow_redirects=True)

                # 检查最终URL
                if 'my_top' in resp.url or 'account' in resp.url:
                    print(f"[OK] Auto-skipped email verification, redirected to: {resp.url}")
                    return True
                elif 'code_auth' in resp.url:
                    # 仍在验证页面，需要输入验证码
                    print(f"[!] Need email verification code")
                    self._need_email_code = True
                    return False

            return True
        elif resp.status_code == 200:
            # 可能需要二次验证或其他步骤
            if 'code_auth' in resp.text:
                print(f"[!] Need 2FA verification")
            else:
                print(f"[X] Login failed, check credentials")
                print(f"[X] Response: {self._safe_console_text(resp.text[:200])}")
            return False
        else:
            print(f"[X] Login failed: {resp.status_code}")
            print(f"[X] Response: {self._safe_console_text(resp.text[:200])}")
            return False


def submit_email_code_with_retry(
    login_client: LivePocketLogin,
    email: str,
    fetch_code: Callable[[], Optional[str]],
    max_attempts: int = 3,
    label: str = "Login",
) -> bool:
    for attempt in range(1, max_attempts + 1):
        print(f"[{label}] 邮件验证码获取/提交尝试: {attempt}/{max_attempts}")
        try:
            verify_code = fetch_code()
        except Exception as exc:
            print(f"[{label}] 邮件验证码获取异常: {exc}")
            verify_code = None

        if not verify_code:
            print(f"[{label}] 邮件验证码获取失败: {attempt}/{max_attempts}")
            continue

        try:
            if login_client.submit_email_code(verify_code):
                print(f"[{label}] 邮件验证码提交成功: {attempt}/{max_attempts}")
                return True
        except Exception as exc:
            print(f"[{label}] 邮件验证码提交异常: {exc}")

        print(f"[{label}] 邮件验证码提交失败，继续重试: {attempt}/{max_attempts}")
        if attempt < max_attempts:
            time.sleep(2)

    print(f"[{label}] 邮件验证码重试失败: {max_attempts}/{max_attempts}")
    return False


def submit_manual_email_code_with_polling(
    login_client: LivePocketLogin,
    backend: TicketBackendApi,
    batch_id: Any,
    account_id: Any,
    email: str,
    timeout_seconds: int = 300,
    poll_interval_seconds: int = 2,
    max_attempts: int = 3,
) -> bool:
    request_id = str(uuid.uuid4())
    timeout_seconds = max(int(timeout_seconds or 300), 30)
    poll_interval_seconds = max(int(poll_interval_seconds or 2), 1)
    max_attempts = max(int(max_attempts or 3), 1)

    backend.request_manual_login_email_code(
        batch_id=batch_id,
        account_id=account_id,
        email=email,
        request_id=request_id,
        timeout_seconds=timeout_seconds,
    )

    deadline = time.time() + timeout_seconds
    for attempt in range(1, max_attempts + 1):
        remaining_seconds = int(deadline - time.time())
        if remaining_seconds <= 0:
            print(f"[Login] 人工邮箱验证码等待超时: requestId={request_id}, email={email}")
            return False

        poll_attempts = max(1, remaining_seconds // poll_interval_seconds)
        print(
            "[Login] 等待人工邮箱验证码: "
            f"requestId={request_id}, attempt={attempt}/{max_attempts}, timeoutLeft={remaining_seconds}s"
        )
        verify_code = backend.manual_login_email_code(
            request_id=request_id,
            attempts=poll_attempts,
            interval_seconds=poll_interval_seconds,
        )
        if not verify_code:
            print(f"[Login] 人工邮箱验证码未提交: requestId={request_id}, email={email}")
            return False

        try:
            if login_client.submit_email_code(verify_code):
                print(f"[Login] 人工邮箱验证码提交成功: requestId={request_id}, attempt={attempt}/{max_attempts}")
                return True
        except Exception as exc:
            print(f"[Login] 人工邮箱验证码提交异常: requestId={request_id}, error={exc}")

        backend.report_manual_login_email_code_invalid(
            batch_id=batch_id,
            account_id=account_id,
            email=email,
            request_id=request_id,
            message="验证码错误，请重新输入",
        )
        if attempt < max_attempts:
            print(f"[Login] 人工邮箱验证码错误，继续等待重新输入: requestId={request_id}, attempt={attempt}/{max_attempts}")

    print(f"[Login] 人工邮箱验证码错误次数已达上限: requestId={request_id}, email={email}")
    return False


def execute_login_for_account(
    account: Dict[str, Any],
    backend: TicketBackendApi,
    batch_id: Any = None,
    index: Any = None,
    total: Any = None,
    platform_code: str = "",
) -> Dict[str, Any]:
    email = str(account.get("email") or "").strip()
    password = str(account.get("password") or account.get("platformPassword") or "").strip()
    account_id = account.get("accountId")
    email_code_mode = str(account.get("emailCodeMode") or "auto").strip().lower()
    manual_code_timeout = int(account.get("manualEmailCodeTimeoutSeconds") or 300)
    manual_code_poll_interval = int(account.get("manualEmailCodePollIntervalSeconds") or 2)
    manual_code_max_attempts = int(account.get("manualEmailCodeMaxAttempts") or 3)
    with log_context(
        flow="login",
        stage="login",
        batchId=batch_id,
        index=index,
        total=total,
        platformCode=platform_code,
        accountId=account_id,
        email=email,
    ):
        if not email or not password:
            return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": "账号邮箱或密码为空"}

        last_error = ""
        login_client = None
        login_success = False
        max_proxy_attempts = proxy_max_attempts()
        for attempt in range(1, max_proxy_attempts + 1):
            with log_context(proxyAttempt=attempt):
                proxy = get_proxy(email, refresh=attempt > 1)
                login_client = LivePocketLogin(CaptchaSolver(api_key=capsolver_api_key()), use_proxy=False, proxy=proxy, proxy_key=email)
                try:
                    login_success = login_client.login(email, password)
                except LivePocketProxyError as exc:
                    last_error = str(exc)
                    if attempt < max_proxy_attempts:
                        print(f"[Login] LivePocket 代理失败，切换代理重试: {attempt}/{max_proxy_attempts}, email={email}, error={exc}")
                        continue
                    return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": f"LivePocket 代理访问失败：{exc}"}

                if not login_success and getattr(login_client, "_need_email_code", False):
                    with log_context(stage="email-code"):
                        if email_code_mode == "manual":
                            login_success = submit_manual_email_code_with_polling(
                                login_client,
                                backend,
                                batch_id=batch_id,
                                account_id=account_id,
                                email=email,
                                timeout_seconds=manual_code_timeout,
                                poll_interval_seconds=manual_code_poll_interval,
                                max_attempts=manual_code_max_attempts,
                            )
                        else:
                            login_success = submit_email_code_with_retry(
                                login_client,
                                email,
                                lambda: backend.email_verify_code(email),
                                max_attempts=3,
                                label="Login",
                            )
                    if not login_success:
                        message = "人工邮箱验证码获取或提交失败" if email_code_mode == "manual" else "邮箱验证码获取或提交失败"
                        return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": message}

                break

        if not login_success:
            return {"accountId": account_id, "email": email, "success": False, "status": "failed", "message": last_error or "LivePocket 登录失败"}

        return {
            "accountId": account_id,
            "email": email,
            "success": True,
            "status": "success",
            "message": "登录成功",
            "loginReqData": login_client.export_login_context(),
        }


def run_login_batch(
    batch_id: Any,
    platform_code: str,
    accounts: List[Dict[str, Any]],
    backend_base_url: str,
    result_stream: str = LOGIN_RESULT_STREAM,
) -> None:
    backend = TicketBackendApi(backend_base_url, platform_code)
    publisher = RedisEventPublisher()
    total = len(accounts or [])
    success_count = 0
    failed_count = 0

    for index, account in enumerate(accounts or [], start=1):
        try:
            result = execute_login_for_account(
                account,
                backend,
                batch_id=batch_id,
                index=index,
                total=total,
                platform_code=platform_code,
            )
        except Exception as exc:
            result = {
                "accountId": account.get("accountId"),
                "email": account.get("email"),
                "success": False,
                "status": "failed",
                "message": f"登录异常：{exc}",
            }
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
        "message": "登录批次完成",
    })
