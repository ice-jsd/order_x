# Ticket Order Executor

这是商品抢购下单执行器，建议按下面这套方式使用：

1. 打开 [config.example.json](/D:/workspace/codex/order_x/go-ticket-executor/config.example.json)，复制一份为 `config.local.json`
2. 修改 `config.local.json` 里的数据库连接和端口
3. 双击 [start.bat](/D:/workspace/codex/order_x/go-ticket-executor/start.bat) 或运行 [start.ps1](/D:/workspace/codex/order_x/go-ticket-executor/start.ps1)

启动优先级：

- 如果目录里已经有 `ticket-order-executor.exe`，会直接启动 exe
- 否则会自动尝试 `go run .`
- 如果两者都没有，会提示先安装 Go 或先编译
- 脚本在未显式设置 `GOPROXY` 时，会默认使用 `https://goproxy.cn,direct`

配置说明：

- `port`: 服务端口，默认 `8099`
- `dsn`: MySQL 连接串
- `workers`: 并发 worker 数
- `queueSize`: 内部任务队列长度
- `requestTimeoutSeconds`: 调用平台下单接口超时时间，单位秒

健康检查：

- [http://127.0.0.1:8099/health](http://127.0.0.1:8099/health)

环境变量仍然可用，并且会覆盖配置文件：

- `TICKET_EXECUTOR_DSN`
- `TICKET_EXECUTOR_PORT`
- `TICKET_EXECUTOR_WORKERS`
- `TICKET_EXECUTOR_QUEUE_SIZE`
- `TICKET_EXECUTOR_REQUEST_TIMEOUT_SECONDS`
