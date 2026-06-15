# auto-py 票务执行器

`auto-py` 现在使用一个统一入口启动，不再区分 HTTP/Redis 模式：

```bash
python main.py --host 0.0.0.0 --port 8098
```

启动后会同时提供 HTTP 接口，并启动 Redis Worker 消费抽票/活动解析队列。

## 文件结构

- `main.py`：唯一启动入口。
- `ticket_python_server.py`：统一票务 Python 宿主导出，建议新的宿主引用都从这里进入。
- `http_server.py`：HTTP 服务导出。
- `redis_worker.py`：Redis Worker 导出。
- `redis_events.py`：Redis 结果流发布。
- `backend_api.py`：调用 Java 后端获取账号、邮箱验证码、短信验证码。
- `ticket_runtime.py`：统一日志、环境变量、脱敏与运行时公共工具。
- `captcha_base.py`：验证码公共轮询底座与 `HCaptchaVerifier` 接口。
- `capsolver_captcha.py`：CapSolver 验证器实现。
- `ticket_captcha.py`：验证码聚合导出与 Jump Shop hCaptcha 选择工厂。
- `livepocket_proxy.py`：LivePocket 动态代理池，仅代理 LivePocket 页面/API 请求。
- `livepocket_register.py`：批量注册。
- `livepocket_login.py`：批量登录和验证码登录。
- `livepocket_profile.py`：改姓。
- `livepocket_purchase.py`：抢票/普通下单。
- `livepocket_lottery.py`：LivePocket 抽票、活动解析，以及历史沿用的宿主实现。
- `jump_shop.py`：Jump Shop 商品解析、注册/登录、Shopify checkout 执行。

## HTTP 接口

- `GET /health`
- `POST /livepocket/register-batch`
- `POST /livepocket/login-batch`
- `POST /livepocket/profile-last-name`
- `GET /livepocket/lottery-event-info?url=...`
- `POST /livepocket/lottery-entry`
- `POST /jump-shop/register-batch`
- `POST /jump-shop/login-batch`
- `GET /jump-shop/product-info?url=...`

批量注册参数：

```json
{
  "batchId": 1,
  "platformCode": "livepocket",
  "count": 10
}
```

批量登录参数：

```json
{
  "batchId": 1,
  "platformCode": "livepocket",
  "accounts": [
    {
      "accountId": 1001,
      "email": "user@example.com",
      "password": "password"
    }
  ]
}
```

`backendBaseUrl` 不建议在页面或手工请求里维护；Java 调 Python 时会统一从 `ticket.python-executor.backend-base-url` 传入。直接运行 Python 调试时，可用 `TICKET_BACKEND_BASE_URL` 覆盖默认值。

## Redis 结果流

- 注册结果：`ticket:livepocket:register:stream:result`
- 登录结果：`ticket:livepocket:login:stream:result`

Python 每个账号发布一条 `account_result`，批次结束发布 `batch_completed`。Java 消费结果流后更新原有注册/登录批次表、明细表和账号池。

## 环境变量

本地运行可以复制 `auto-py/.env.example` 为 `auto-py/.env.local` 后填写真实值；`.env.local` 已加入忽略列表，不要提交真实 key。服务器部署建议使用 systemd、Docker Compose 或 CI/CD 的环境变量/Secrets 注入。

- `CAPSOLVER_API_KEY`：CapSolver API Key。
- `JUMP_SHOP_BROWSER_PATH`：Jump Shop 浏览器可执行文件路径；未设置时只走系统默认 Chrome/Chromium/Edge 探测。
- `TICKET_BACKEND_BASE_URL`：直接调试 Python 时使用的 Java 外部账号接口地址；正常页面流程由 Java 配置传入。
- `TICKET_PLATFORM_CODE`：直接调试 Python 时使用的平台编码，默认 `livepocket`。
- `LOTTERY_REDIS_HOST` / `LOTTERY_REDIS_PORT` / `LOTTERY_REDIS_DB` / `LOTTERY_REDIS_PASSWORD`：Redis 连接。
- `LOTTERY_WORKERS`：抽票 Worker 数。
- `LOTTERY_EVENT_PARSE_WORKERS`：活动解析 Worker 数。

LivePocket 动态代理：

- `LIVEPOCKET_PROXY_ENABLED`：是否开启 LivePocket 代理，默认 `false`。
- `LIVEPOCKET_PROXY_SCOPE`：代理生效范围，默认 `all`；可选 `server-only`、`local-only`、`disabled`。
- `LIVEPOCKET_PROXY_RUNTIME`：手动指定当前运行环境是 `server` 还是 `local`；默认 Windows 视为 `local`，其他系统视为 `server`。
- `LIVEPOCKET_PROXY_PROVIDER`：代理供应商，默认 `ipweb`。
- `LIVEPOCKET_PROXY_REGION`：代理地区，默认 `JP`。
- `LIVEPOCKET_PROXY_API_TOKEN`：ipweb API Token。
- `LIVEPOCKET_PROXY_API_URL`：ipweb 动态代理 API，默认 `http://api.ipweb.cc:8004/api/agent/account2`。
- `LIVEPOCKET_PROXY_GATEWAY`：ipweb 网关，默认 `gate1.ipweb.cc:7778`。
- `LIVEPOCKET_PROXY_POOL_MIN` / `LIVEPOCKET_PROXY_BATCH_SIZE`：代理池补充阈值和单次拉取数量。
- `LIVEPOCKET_PROXY_MAX_ATTEMPTS`：代理失败后更换代理重试次数，默认 `3`。
- `LIVEPOCKET_PROXY_STATIC`：本地调试用固定代理，格式 `host:port:user:pass` 或 `http://user:pass@host:port`。

Jump Shop 当前验证码策略：

- `hCaptcha`：固定使用 `CapSolver`。
- `reCAPTCHA`：固定使用 `CapSolver`。

兼容读取 `PYTHON_EXECUTOR_REDIS_*` Redis 环境变量。
