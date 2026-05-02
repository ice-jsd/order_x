"""
livepocket.jp 登录协议实现 - 带 Google reCAPTCHA v2 处理
使用打码平台: CapSolver
"""
import requests
import time
import re
import uuid
import random
from urllib.parse import urlencode, quote



class CaptchaSolver:
    """CapSolver 打码平台接口"""

    def __init__(self, api_key):
        self.api_key = api_key
        self.create_url = 'https://api.capsolver.com/createTask'
        self.result_url = 'https://api.capsolver.com/getTaskResult'

    def solve_recaptcha_v2(self, site_key, page_url, enterprise=False, action=None):
        """解决 reCAPTCHA v2"""
        # 提交任务
        task_config = {
            "type": "ReCaptchaV2EnterpriseTaskProxyLess" if enterprise else "ReCaptchaV2TaskProxyLess",
            "websiteURL": page_url,
            "websiteKey": site_key
        }

        # 如果是 Enterprise,添加 isEnterprise 标志
        if enterprise:
            task_config["isEnterprise"] = True
            # 如果有 action,添加到 enterprisePayload
            if action:
                task_config["enterprisePayload"] = {"s": action}

        task_data = {
            "clientKey": self.api_key,
            "task": task_config
        }

        resp = requests.post(self.create_url, json=task_data)
        result = resp.json()

        if result['errorId'] != 0:
            raise Exception(f"提交验证码失败: {result.get('errorDescription', result)}")

        task_id = result['taskId']
        print(f"[CapSolver] 任务ID: {task_id}, 等待识别...")

        # 轮询结果
        for i in range(60):
            time.sleep(3)
            data = {
                "clientKey": self.api_key,
                "taskId": task_id
            }
            resp = requests.post(self.result_url, json=data)
            result = resp.json()

            if result['errorId'] != 0:
                raise Exception(f"获取结果失败: {result.get('errorDescription', result)}")

            if result['status'] == 'ready':
                print(f"[CapSolver] 识别成功!")
                return result['solution']['gRecaptchaResponse']
            elif result['status'] != 'processing':
                raise Exception(f"识别失败: {result}")

        raise Exception("识别超时")


class LivePocketLogin:
    """livepocket.jp 登录"""

    def __init__(self, captcha_solver, use_proxy=False):
        self.session = requests.Session()
        self.captcha_solver = captcha_solver
        self.use_proxy = use_proxy

        # 从抓包提取的关键信息
        self.site_key = '6Ld50ncqAAAAAJuHR7I6dNVXfnKme_WTP2SKS168'
        self.login_url = 'https://livepocket.jp/login'

        # 设置请求头（使用手机模式 User-Agent）
        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept-Encoding': 'gzip, deflate, br',
            'Referer': 'https://livepocket.jp/',
            'Origin': 'https://livepocket.jp',
            'Connection': 'keep-alive',
            'Upgrade-Insecure-Requests': '1',
            'Sec-Fetch-Dest': 'document',
            'Sec-Fetch-Mode': 'navigate',
            'Sec-Fetch-Site': 'same-origin',
            'Sec-Fetch-User': '?1'
        })

    def get_authenticity_token(self):
        """获取登录页面的 CSRF token"""
        print("[1] 获取登录页面...")

        # 模拟人类延迟
        time.sleep(random.uniform(1.5, 3.0))

        resp = self.session.get(self.login_url)

        # 提取 authenticity_token
        match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
        if not match:
            raise Exception("未找到 authenticity_token")

        token = match.group(1)

        # 提取 x-csrf-token (meta标签)
        csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)
        csrf_token = csrf_match.group(1) if csrf_match else token

        print(f"[1] authenticity_token: {token[:50]}...")
        print(f"[1] csrf_token: {csrf_token[:50]}...")

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
        csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)
        token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)

        if not token_match:
            print("[X] Failed to get authenticity_token from code_auth page")
            return False

        authenticity_token = token_match.group(1)
        csrf_token = csrf_match.group(1) if csrf_match else authenticity_token

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

        # 步骤2: 解决验证码
        print(f"[2] 调用打码平台解决 reCAPTCHA v2...")
        recaptcha_response = self.captcha_solver.solve_recaptcha_v2(
            site_key=self.site_key,
            page_url=self.login_url
        )
        print(f"[2] g-recaptcha-response: {recaptcha_response[:80]}...")

        # 步骤3: 提交登录表单（两次POST，类似注册）
        print(f"[3] 提交登录表单（第一次POST）...")

        # 第一次POST - 使用 g-recaptcha-response-data[login]
        form_data = {
            'authenticity_token': authenticity_token,
            'user[email]': email,
            'user[password]': password,
            'g-recaptcha-response-data[login]': recaptcha_response,
            'g-recaptcha-response': '',
            'commit': ''
        }

        # 添加关键请求头 (Turbo框架)
        headers = {
            'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
            'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
            'X-CSRF-Token': csrf_token,
            'X-Turbo-Request-Id': str(uuid.uuid4()),
            'Sec-Fetch-Mode': 'cors',
            'Sec-Fetch-Dest': 'empty'
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

        # 第二次POST - 使用完整的 g-recaptcha-response
        print(f"[4] 提交登录表单（第二次POST）...")

        # 提取新token
        token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
        if token_match:
            authenticity_token = token_match.group(1)

        # 修改form_data
        form_data['authenticity_token'] = authenticity_token
        form_data['g-recaptcha-response'] = recaptcha_response
        del form_data['g-recaptcha-response-data[login]']

        # 更新headers中的X-Turbo-Request-Id
        headers['X-Turbo-Request-Id'] = str(uuid.uuid4())

        resp = self.session.post(
            self.login_url,
            data=form_data,
            headers=headers,
            allow_redirects=False
        )

        print(f"[4] 第二次POST响应: {resp.status_code}")

        # 检查登录结果
        if resp.status_code == 303:
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
                print(f"[X] Response: {resp.text[:200]}")
            return False
        else:
            print(f"[X] Login failed: {resp.status_code}")
            print(f"[X] Response: {resp.text[:200]}")
            return False


def main():
    """使用示例"""

    # 配置 CapSolver 打码平台
    captcha_solver = CaptchaSolver(
        api_key='CAP-200B6FC092214F1DDA9344DC2361E799BB8B822BA24E438BC458A933290A2DDC'
    )

    # 创建登录实例 - 不使用代理,只用延迟
    login = LivePocketLogin(captcha_solver, use_proxy=False)

    # 执行登录
    success = login.login(
        email='6bqkxt@gjcytech.com',
        password='123@Live888'
    )

    if success:
        print("\n=== Login completed ===")
    else:
        print("\n=== Login failed ===")


if __name__ == '__main__':
    main()
