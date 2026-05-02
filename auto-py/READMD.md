# auto-py 使用说明

本目录是 LivePocket 自动注册、自动登录的本地 Python 脚本集合。脚本会调用线上 Java 后端接口获取账号、邮箱、手机号、验证码等信息，然后用 `requests` 模拟 LivePocket 页面请求。

## 1. 运行前准备

### 1.1 安装 Python

建议使用 Python 3.10+。

```bash
python --version
```

如果命令不可用，需要先安装 Python，并确认已加入 PATH。

### 1.2 安装依赖

当前脚本只依赖 `requests`：

```bash
pip install requests
```

如果你的电脑有多个 Python 版本，也可以使用：

```bash
python -m pip install requests
```

### 1.3 配置打码平台 Key

注册和登录都会调用 CapSolver 处理 LivePocket 的 reCAPTCHA。

需要在以下文件中填写 `api_key`：

- `auto_register.py`
- `complete_login.py`
- `login_with_captcha.py`
- `register_v2.py`

搜索下面这种代码：

```python
api_key=''
```

或：

```python
CAPSOLVER_API_KEY = ''
```

改成你的 CapSolver API Key。

示例：

```python
CAPSOLVER_API_KEY = '你的_CapSolver_Key'
```

如果不填，脚本会在验证码识别阶段失败。

### 1.4 确认后端服务可用

脚本当前写死调用线上后端：

```text
http://62.234.211.209:8081/ticket/external/account
```

运行前请确认后端接口正常，例如：

```bash
curl "http://62.234.211.209:8081/ticket/external/account/next-offline?platformCode=livepocket"
```

注册流程还依赖这些后端能力：

- 邮箱账号池可自动创建或有可用邮箱
- 短信平台取号接口可用
- 邮箱 IMAP 读取验证码/激活链接正常
- `platformCode=livepocket` 平台配置存在

## 2. 注册账号

### 2.1 单个注册

```bash
python auto_register.py
```

完整流程包括：

1. 调用后端 `next-register` 获取注册资料
2. 提交 LivePocket 注册表单
3. 获取邮箱激活链接并激活
4. 登录账号
5. 提交手机号
6. 获取短信验证码
7. 提交短信验证码
8. 通知后端账号激活成功

### 2.2 批量注册

```bash
python auto_register.py --count 10 --interval 5
```

参数说明：

- `--count 10`：注册 10 个账号
- `--interval 5`：每个账号之间间隔 5 秒，避免请求太快

## 3. 登录账号

### 3.1 批量登录离线账号

```bash
python complete_login.py --count 5
```

完整流程包括：

1. 调用后端 `next-offline` 获取一个离线账号
2. 登录 LivePocket
3. 如果需要邮箱验证码，则调用后端获取验证码
4. 提交验证码
5. 登录成功后把 Cookie 回写到后端 `login-success`

### 3.2 使用指定账号测试登录

```bash
python complete_login.py --test --email xxx@xxx.com --password xxx
```

这个模式不会从后端取账号，而是直接使用命令行传入的邮箱和密码测试登录。

## 4. 常见问题

### 4.1 提示验证码识别失败

通常是 CapSolver API Key 没填、余额不足、网络访问 CapSolver 失败，或者 LivePocket 的 reCAPTCHA 参数变化。

### 4.2 提示获取注册信息失败

检查后端 `next-register` 接口是否正常。常见原因：

- 没有可用邮箱且 Stalwart 创建邮箱失败
- 短信平台取号失败
- 固定短信设备当前不可用
- LivePocket 平台配置不存在

### 4.3 提示没有激活链接或邮箱验证码

先在后台邮箱账号池确认对应邮箱是否已经收到邮件。也可能是邮件进入垃圾箱，或后端 IMAP 扫描范围没有包含对应文件夹。

### 4.4 提示没有手机验证码

确认短信平台后台是否已经收到短信。号码倒计时过期后，后端会尝试重新取同一个号码或重新取号，具体以当前后端实现为准。

### 4.5 批量统计显示成功数不正确

当前 `auto_register.py` 的主流程偏验证脚本，部分成功分支可能没有明确返回 `True`。如果要长期批量跑，建议后续把每个阶段的返回值和失败原因再规范化。

## 5. 文件说明

- `auto_register.py`：注册总入口
- `complete_login.py`：登录总入口
- `register_v2.py`：LivePocket 注册请求实现
- `login_with_captcha.py`：LivePocket 登录和 CapSolver 验证码实现
- `READMD.md`：当前说明文件
