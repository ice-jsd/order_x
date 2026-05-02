"""
完整的登录流程整合
1. 从服务器获取待登录账号信息
2. 调用登录模块进行登录和邮件验证码发送
3. 获取邮件验证码
4. 提交验证码完成登录
5. 确认登录成功
"""
import requests
import time
import argparse
from login_with_captcha import CaptchaSolver, LivePocketLogin


class CompleteLoginFlow:
    """完整登录流程"""

    def __init__(self, capsolver_api_key, platform_code="livepocket"):
        self.platform_code = platform_code
        self.base_url = "http://62.234.211.209:8081/ticket/external/account"

        # 初始化验证码解决器和登录实例
        self.captcha_solver = CaptchaSolver(api_key=capsolver_api_key)
        self.login_client = LivePocketLogin(self.captcha_solver, use_proxy=False)

    def get_next_login(self):
        """获取下一个待登录的账号信息"""
        print("[Step 1] 获取待登录账号信息...")

        url = f"{self.base_url}/next-offline"
        params = {"platformCode": self.platform_code}

        try:
            response = requests.get(url, params=params)
            response.raise_for_status()
            result = response.json()

            if result.get("code") == 200 and result.get("data"):
                email = result["data"].get("email")
                password = result["data"].get("password")
                print(f"[Step 1] 获取成功: {email}")
                return email, password
            else:
                print(f"[Step 1] 获取失败: {result.get('msg', 'Unknown error')}")
                return None, None

        except Exception as e:
            print(f"[Step 1] 请求异常: {e}")
            return None, None

    def get_auth_code(self, email):
        """获取邮件验证码"""
        print(f"[Step 3] 获取邮件验证码: {email}")

        url = f"{self.base_url}/email-verify-code"
        params = {
            "platformCode": self.platform_code,
            "email": email
        }

        max_retries = 10
        for i in range(max_retries):
            try:
                time.sleep(3)
                response = requests.get(url, params=params)
                response.raise_for_status()
                result = response.json()

                if result.get("code") == 200 and result.get("data"):
                    verify_code = result["data"].get("verifyCode")
                    if verify_code:
                        print(f"[Step 3] 验证码获取成功: {verify_code}")
                        return verify_code
                    else:
                        print(f"[Step 3] 验证码为空，重试 {i+1}/{max_retries}")
                else:
                    print(f"[Step 3] 获取失败: {result.get('msg', 'Unknown error')}, 重试 {i+1}/{max_retries}")

            except Exception as e:
                print(f"[Step 3] 请求异常: {e}, 重试 {i+1}/{max_retries}")

        print(f"[Step 3] 验证码获取失败，已重试 {max_retries} 次")
        return None

    def sure_login(self, email, cookies_dict=None):
        """确认登录成功"""
        print(f"[Step 5] 确认登录成功: {email}")

        import json
        cookies_json = json.dumps(cookies_dict) if cookies_dict else ""
        print(f"[Step 5] Cookies JSON: {cookies_json}")

        url = f"{self.base_url}/login-success"
        headers = {"Content-Type": "application/json"}
        data = {
            "platformCode": self.platform_code,
            "email": email,
            "loginReqData": cookies_json
        }

        try:
            response = requests.post(url, headers=headers, json=data)
            response.raise_for_status()
            result = response.json()

            if result.get("code") == 200:
                print(f"[Step 5] 登录确认成功")
                return True
            else:
                print(f"[Step 5] 登录确认失败: {result.get('msg', 'Unknown error')}")
                return False

        except Exception as e:
            print(f"[Step 5] 请求异常: {e}")
            return False

    def execute_login(self):
        """执行完整登录流程"""
        print("\n========== 开始完整登录流程 ==========\n")

        # Step 1: 获取待登录账号
        email, password = self.get_next_login()
        if not email or not password:
            print("\n[失败] 无法获取待登录账号")
            return False

        print(f"\n账号: {email}\n密码: {password}\n")

        # 每次登录前重新创建 login_client，避免 session 污染
        self.login_client = LivePocketLogin(self.captcha_solver, use_proxy=False)

        # Step 2: 执行登录（包含验证码处理）
        print("[Step 2] 开始登录流程...")
        login_success = self.login_client.login(email, password)

        if not login_success:
            # 检查是否需要邮件验证码
            if hasattr(self.login_client, '_need_email_code') and self.login_client._need_email_code:
                print("[Step 2] 需要邮件验证码，继续流程...")
            else:
                print("\n[失败] 登录失败")
                return False
        else:
            # 登录成功，直接跳过验证码步骤
            print("[Step 2] 登录成功，无需邮件验证")
            cookies_dict = dict(self.login_client.session.cookies)
            print(f"[Step 2] 所有 Cookies: {cookies_dict}")
            return self.sure_login(email, cookies_dict)

        # Step 3: 获取邮件验证码
        verify_code = self.get_auth_code(email)
        if not verify_code:
            print("\n[失败] 无法获取邮件验证码")
            return False

        # Step 4: 提交邮件验证码
        print(f"\n[Step 4] 提交邮件验证码...")
        code_success = self.login_client.submit_email_code(verify_code)

        if not code_success:
            print("\n[失败] 邮件验证码提交失败")
            return False

        # Step 5: 确认登录成功
        cookies_dict = dict(self.login_client.session.cookies)
        print(f"[Step 4] 所有 Cookies: {cookies_dict}")
        final_success = self.sure_login(email, cookies_dict)

        if final_success:
            print("\n========== 登录流程完成 ==========\n")
            return True
        else:
            print("\n[失败] 登录确认失败")
            return False


def main():
    """主函数"""
    parser = argparse.ArgumentParser(description='批量登录账号')
    parser.add_argument('--count', type=int, default=1, help='登录账号数量（默认: 1）')
    parser.add_argument('--test', action='store_true', help='测试模式：使用指定账号')
    parser.add_argument('--email', type=str, help='测试模式的邮箱')
    parser.add_argument('--password', type=str, help='测试模式的密码')
    args = parser.parse_args()

    # CapSolver API Key
    CAPSOLVER_API_KEY = 'CAP-200B6FC092214F1DDA9344DC2361E799BB8B822BA24E438BC458A933290A2DDC'

    # 创建登录流程实例
    login_flow = CompleteLoginFlow(
        capsolver_api_key=CAPSOLVER_API_KEY,
        platform_code="livepocket"
    )

    success_count = 0
    fail_count = 0

    print(f"\n{'='*60}")
    print(f"开始批量登录，目标数量: {args.count}")
    print(f"{'='*60}\n")

    for i in range(args.count):
        print(f"\n{'='*60}")
        print(f"第 {i+1}/{args.count} 个账号")
        print(f"{'='*60}\n")

        if args.test:
            # 测试模式：使用指定账号
            email = args.email or "d796ue@gjcytech.com"
            password = args.password or "d796ue@ABC"
            print(f"测试模式 - 账号: {email}\n")

            # 重新创建 login_client
            login_flow.login_client = LivePocketLogin(login_flow.captcha_solver, use_proxy=False)

            # 执行登录
            login_success = login_flow.login_client.login(email, password)

            if not login_success:
                if hasattr(login_flow.login_client, '_need_email_code') and login_flow.login_client._need_email_code:
                    print("[需要邮件验证码]")
                    verify_code = login_flow.get_auth_code(email)
                    if verify_code:
                        code_success = login_flow.login_client.submit_email_code(verify_code)
                        if code_success:
                            cookies_dict = dict(login_flow.login_client.session.cookies)
                            if login_flow.sure_login(email, cookies_dict):
                                print("✓ 登录成功")
                                success_count += 1
                            else:
                                print("✗ 确认登录失败")
                                fail_count += 1
                        else:
                            print("✗ 验证码提交失败")
                            fail_count += 1
                    else:
                        print("✗ 获取验证码失败")
                        fail_count += 1
                else:
                    print("✗ 登录失败")
                    fail_count += 1
            else:
                cookies_dict = dict(login_flow.login_client.session.cookies)
                if login_flow.sure_login(email, cookies_dict):
                    print("✓ 登录成功")
                    success_count += 1
                else:
                    print("✗ 确认登录失败")
                    fail_count += 1
        else:
            # 执行登录
            success = login_flow.execute_login()
            if success:
                print("✓ 登录成功")
                success_count += 1
            else:
                print("✗ 登录失败")
                fail_count += 1

        time.sleep(2)

    print(f"\n{'='*60}")
    print(f"批量登录完成")
    print(f"成功: {success_count}/{args.count}")
    print(f"失败: {fail_count}/{args.count}")
    print(f"{'='*60}\n")


if __name__ == '__main__':
    main()
