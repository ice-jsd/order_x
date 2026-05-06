"""
livepocket.jp 注册功能 - 修复版(两次POST)
"""
import requests
import time
import re
import uuid
import random
from login_with_captcha import CaptchaSolver

class LivePocketRegister:
    """livepocket.jp 注册"""

    def __init__(self, captcha_solver, use_proxy=False):
        self.session = requests.Session()
        self.captcha_solver = captcha_solver
        self.use_proxy = use_proxy

        self.site_key = '6Ld50ncqAAAAAJuHR7I6dNVXfnKme_WTP2SKS168'
        self.signup_url = 'https://livepocket.jp/sign_up'


        self.session.headers.update({
            'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
            'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept-Encoding': 'gzip, deflate, br',
            'Referer': 'https://livepocket.jp/',
            'Origin': 'https://livepocket.jp',
            'Connection': 'keep-alive'
        })

    def register(self, email, password, last_name, first_name, phone_number, sex='male', birthday='2006-04-22'):
        """执行注册 - 模仿登录的两次POST"""
        print("=" * 60)
        print("Starting registration (two-step POST)...")

        print(f"  - 姓: {last_name}")
        print(f"  - 名: {first_name}")

        print("=" * 60)

        # 步骤1: 获取注册页面token
        print("[1] Getting signup page...")
        time.sleep(random.uniform(1.5, 3.0))

        resp = self.session.get(self.signup_url)
        token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
        csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)

        if not token_match:
            print("[X] Failed to get token")
            return False

        authenticity_token = token_match.group(1)
        csrf_token = csrf_match.group(1) if csrf_match else authenticity_token

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

        resp = self.session.post(
            'https://livepocket.jp/sign_up/confirm',
            data=form_data,
            headers=headers,
            allow_redirects=False
        )

        print(f"[3] First POST response: {resp.status_code}")

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

        resp = self.session.post(
            'https://livepocket.jp/sign_up/confirm',
            data=form_data,
            headers=headers,
            allow_redirects=False
        )

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


def main():
    captcha_solver = CaptchaSolver(
        api_key='CAP-200B6FC092214F1DDA9344DC2361E799BB8B822BA24E438BC458A933290A2DDC'
    )

    register = LivePocketRegister(captcha_solver, use_proxy=False)

    success = register.register(
        email='2373439138@qq.com',
        password='lgc@23014',
        last_name='刘',
        first_name='高呈',
        phone_number='13594147304',
        sex='male',
        birthday='2004-04-22'
    )

    if success:
        print("\n=== Registration completed ===")
    else:
        print("\n=== Registration failed ===")


if __name__ == '__main__':
    main()
