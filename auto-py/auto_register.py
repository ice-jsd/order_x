"""
自动获取注册信息并完成注册
"""
import requests
import time
import argparse
from register_v2 import LivePocketRegister
from login_with_captcha import CaptchaSolver, LivePocketLogin


def get_next_register():
    """获取下一个待注册的账号信息"""
    url = "http://62.234.211.209:8081/ticket/external/account/next-register"
    params = {
        "platformCode": "livepocket"
    }

    response = requests.post(url, params=params)
    print(response.text)

    if response.status_code == 200:
        result = response.json()
        if result.get('code') == 200:
            return result.get('data')
    return None


def get_email_activation_link(email):
    """获取邮箱激活链接"""
    url = "http://62.234.211.209:8081/ticket/external/account/email-activation-link"
    params = {
        "platformCode": "livepocket",
        "email": email
    }
    response = requests.get(url, params=params)

    if response.status_code == 200:
        result = response.json()
        if result.get('code') == 200:
            return result['data'].get('activationUrl')
    return None


def get_email_verify_code(email):
    """获取邮箱验证码（登录用）"""
    url = "http://62.234.211.209:8081/ticket/external/account/email-verify-code"
    params = {
        "platformCode": "livepocket",
        "email": email
    }
    response = requests.get(url, params=params)

    if response.status_code == 200:
        result = response.json()
        if result.get('code') == 200:
            return result['data'].get('verifyCode')
    return None


def get_phone_activation_code(email):
    """获取手机激活码"""
    url = "http://62.234.211.209:8081/ticket/external/account/phone-activation-code"
    params = {
        "platformCode": "livepocket",
        "email": email
    }
    response = requests.get(url, params=params)

    print(f"  [DEBUG] 接口响应: {response.status_code}, 内容: {response.text[:200]}")

    if response.status_code == 200:
        result = response.json()
        if result.get('code') == 200:
            return result['data'].get('verifyCode')
    return None


def notify_success(email):
    """通知后台账号激活成功"""
    url = "http://62.234.211.209:8081/ticket/external/account/activate-confirm"
    headers = {
        "Content-Type": "application/json"
    }
    data = {
        "platformCode": "livepocket",
        "email": email,
        "success": True
    }

    try:
        response = requests.post(url, headers=headers, json=data)
        print(f"[通知后台] 响应: {response.status_code}, {response.text}")
        return response.status_code == 200
    except Exception as e:
        print(f"[通知后台] 失败: {e}")
        return False


def convert_gender(gender_str):
    """转换性别格式"""
    return 'male' if gender_str == 'male' else 'female'


def format_birthday(year, month, day):
    """格式化生日"""
    return f"{year}-{month:02d}-{day:02d}"


def register_one_account():
    """注册单个账号的完整流程"""

    # 步骤1: 获取注册信息
    print("\n[1] 获取注册信息...")
    register_data = get_next_register()

    if not register_data:
        print("[X] 获取注册信息失败")
        return

    print(f"[OK] 获取到账号信息:")
    print(f"  - 邮箱: {register_data['email']}")
    print(f"  - 姓名: {register_data['familyName']} {register_data['givenName']}")
    print(f"  - 手机: {register_data['phoneNumber']}")
    print(f"  - 生日: {register_data['birthYear']}-{register_data['birthMonth']}-{register_data['birthDay']}")

    # 步骤2: 初始化验证码解决器和注册器
    print("\n[2] 初始化注册器...")
    captcha_solver = CaptchaSolver(
        api_key='CAP-200B6FC092214F1DDA9344DC2361E799BB8B822BA24E438BC458A933290A2DDC'
    )
    register = LivePocketRegister(captcha_solver, use_proxy=False)

    # 步骤3: 执行注册
    print("\n[3] 开始注册...")
    success = register.register(
        email=register_data['email'],
        password=register_data['password'],
        last_name=register_data['familyName'],
        first_name=register_data['givenName'],
        phone_number=register_data['phoneNumber'],
        sex=convert_gender(register_data['gender']),
        birthday=format_birthday(
            register_data['birthYear'],
            register_data['birthMonth'],
            register_data['birthDay']
        )
    )

    if success:
        print("\n" + "=" * 60)
        print("注册成功!")
        print("=" * 60)
        print(f"账号信息:")
        print(f"  邮箱: {register_data['email']}")
        print(f"  密码: {register_data['password']}")

        # 步骤4: 等待网站发送激活邮件
        import time
        print("\n[4] 等待网站发送激活邮件...")
        activation_url = None
        for i in range(10):
            time.sleep(3)
            print(f"  等待中... ({(i+1)*3}秒)")

            activation_url = get_email_activation_link(register_data['email'])
            if activation_url:
                print(f"[OK] 获取到激活链接: {activation_url}")
                break
        else:
            print("[X] 未获取到激活链接，请稍后手动调用接口")

        # 步骤5: 激活邮箱
        email_activated = False
        if activation_url:
            print("\n[5] 激活邮箱...")
            session = requests.Session()
            session.headers.update({
                'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
            })

            try:
                resp = session.get(activation_url, allow_redirects=True)
                if resp.status_code == 200:
                    print(f"[OK] 邮箱激活成功")
                    print(f"  最终URL: {resp.url}")
                    email_activated = True
                else:
                    print(f"[X] 激活失败，状态码: {resp.status_code}")
            except Exception as e:
                print(f"[X] 激活请求失败: {e}")

        # 步骤6: 登录账号
        if email_activated:
            print("\n[6] 登录账号...")
            time.sleep(2)

            login = LivePocketLogin(captcha_solver, use_proxy=False)
            login_success = login.login(
                email=register_data['email'],
                password=register_data['password']
            )

            # 检查是否需要邮件验证码（无论login返回True还是False）
            if hasattr(login, '_need_email_code') and login._need_email_code:
                print("\n[7] 获取邮件验证码...")
                for i in range(5):
                    time.sleep(2)
                    email_code = get_email_verify_code(register_data['email'])
                    if email_code:
                        print(f"[OK] 邮件验证码: {email_code}")
                        # 提交验证码
                        login_success = login.submit_email_code(email_code)
                        if login_success:
                            print(f"[OK] 邮件验证通过")
                            break
                        else:
                            print(f"[X] 验证码提交失败")
                else:
                    print("[X] 未获取到邮件验证码")
                    return
            elif login_success:
                print(f"[OK] 登录成功")

            # 步骤8: 提交手机号并获取验证码
            if login_success:
                print("\n[8] 访问手机验证页面...")
                time.sleep(2)

                import re
                # 访问手机编辑页（确保使用手机UA）
                mobile_headers = {
                    'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0'
                }
                resp = login.session.get('https://livepocket.jp/account/phone_number/edit', headers=mobile_headers, allow_redirects=False)

                # 如果重定向，跟随重定向（保持手机UA）
                if resp.status_code == 302:
                    redirect_url = resp.headers.get('Location', '')
                    if redirect_url.startswith('http'):
                        resp = login.session.get(redirect_url, headers=mobile_headers)
                    else:
                        resp = login.session.get(f'https://livepocket.jp{redirect_url}', headers=mobile_headers)

                token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)

                if token_match:
                    authenticity_token = token_match.group(1)

                    # 提取 csrf token
                    csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)
                    csrf_token = csrf_match.group(1) if csrf_match else authenticity_token

                    # 提交手机号
                    print("\n[9] 提交手机号...")
                    phone_data = {
                        '_method': 'put',
                        'authenticity_token': authenticity_token,
                        'user[unconfirmed_phone_country_id]': '107',
                        'user[unconfirmed_phone_number]': register_data['phoneNumber'],
                        'user[auth_method]': 'sms',
                        'button': ''
                    }

                    import uuid
                    phone_headers = {
                        'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
                        'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
                        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                        'X-CSRF-Token': csrf_token,
                        'X-Turbo-Request-Id': str(uuid.uuid4()),
                        'Referer': 'https://livepocket.jp/account/phone_number/edit',
                        'Sec-Fetch-Mode': 'cors',
                        'Sec-Fetch-Dest': 'empty'
                    }

                    resp = login.session.post(
                        'https://livepocket.jp/account/phone_number',
                        data=phone_data,
                        headers=phone_headers,
                        allow_redirects=False
                    )

                    if resp.status_code == 302:
                        print(f"[OK] 手机号提交成功，网站正在发送验证码...")

                        # 等待并获取验证码
                        print("\n[10] 获取手机验证码...")
                        verify_code = None
                        for i in range(20):
                            time.sleep(5)
                            print(f"  等待中... ({(i+1)*5}秒)")
                            verify_code = get_phone_activation_code(register_data['email'])
                            if verify_code:
                                print(f"[OK] 验证码: {verify_code}")
                                break

                        if verify_code:
                            # 提交验证码
                            print("\n[11] 提交验证码...")
                            verify_url = resp.headers.get('Location', '')
                            if verify_url:
                                # 提取 sms_verifies_token
                                from urllib.parse import urlparse, parse_qs
                                parsed = urlparse(verify_url)
                                query_params = parse_qs(parsed.query)
                                sms_token = query_params.get('sms_verifies_token', [''])[0]

                                # 判断是否为完整URL
                                if verify_url.startswith('http'):
                                    resp = login.session.get(verify_url)
                                else:
                                    resp = login.session.get(f"https://livepocket.jp{verify_url}")

                                token_match = re.search(r'name="authenticity_token".*?value="([^"]+)"', resp.text)
                                csrf_match = re.search(r'<meta name="csrf-token" content="([^"]+)"', resp.text)

                                if token_match:
                                    import uuid
                                    verify_data = {
                                        'authenticity_token': token_match.group(1),
                                        'sms_verifies_token': sms_token,
                                        'user[auth_code]': verify_code,
                                        'button': ''
                                    }

                                    verify_headers = {
                                        'User-Agent': 'Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Mobile Safari/537.36 Edg/147.0.0.0',
                                        'Accept': 'text/vnd.turbo-stream.html, text/html, application/xhtml+xml',
                                        'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8',
                                        'X-CSRF-Token': csrf_match.group(1) if csrf_match else token_match.group(1),
                                        'X-Turbo-Request-Id': str(uuid.uuid4()),
                                        'Referer': verify_url if verify_url.startswith('http') else f'https://livepocket.jp{verify_url}',
                                        'Sec-Fetch-Mode': 'cors',
                                        'Sec-Fetch-Dest': 'empty'
                                    }

                                    resp = login.session.post(
                                        'https://livepocket.jp/account/phone_number/sms_verify',
                                        data=verify_data,
                                        headers=verify_headers,
                                        allow_redirects=False
                                    )

                                    if resp.status_code == 200:
                                        print(f"[OK] 手机验证成功！")
                                        print(f"\n=== 全流程完成 ===")

                                        # 通知后台成功
                                        print("\n[12] 通知后台账号激活成功...")
                                        notify_success(register_data['email'])
                                    elif resp.status_code in [302, 303]:
                                        print(f"[OK] 手机验证成功！")
                                        print(f"\n=== 全流程完成 ===")

                                        # 通知后台成功
                                        print("\n[12] 通知后台账号激活成功...")
                                        notify_success(register_data['email'])
                                    else:
                                        print(f"[X] 验证失败: {resp.status_code}")
                                        return False
                        else:
                            print("[X] 未获取到验证码")
                            return False
                    else:
                        print(f"[X] 提交手机号失败: {resp.status_code}")
                        return False
                else:
                    print("[X] 未找到token")
                    return False
            else:
                print(f"[X] 登录失败，无法继续手机验证")
                return False
    else:
        print("\n" + "=" * 60)
        print("注册失败")
        print("=" * 60)
        return False


def main():
    parser = argparse.ArgumentParser(description='批量注册账号')
    parser.add_argument('--count', type=int, default=1, help='注册账号数量（默认: 1）')
    parser.add_argument('--interval', type=int, default=3, help='每次注册间隔时间（秒，默认: 3）')
    args = parser.parse_args()

    success_count = 0
    fail_count = 0

    print(f"\n{'='*60}")
    print(f"开始批量注册，目标数量: {args.count}")
    print(f"{'='*60}\n")

    for i in range(args.count):
        print(f"\n{'='*60}")
        print(f"第 {i+1}/{args.count} 个账号")
        print(f"{'='*60}\n")

        if register_one_account():
            success_count += 1
        else:
            fail_count += 1

        # 如果不是最后一个账号，等待指定的间隔时间
        if i < args.count - 1:
            print(f"\n等待 {args.interval} 秒后继续下一个账号...\n")
            time.sleep(args.interval)

    print(f"\n{'='*60}")
    print(f"批量注册完成")
    print(f"成功: {success_count}/{args.count}")
    print(f"失败: {fail_count}/{args.count}")
    print(f"{'='*60}\n")


if __name__ == '__main__':
    main()
