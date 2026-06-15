# 表单抽票助手

Chrome 扩展版 Hands Form 抽票执行器。

## 功能

- 向后端领取 `hands-form` 待执行抽票任务
- 自动打开 `event.hands.net` 页面
- 自动填写 `姓名 / フリガナ / 邮箱`
- 让页面自己执行 reCAPTCHA 并提交 `entryForm`
- 进入确认页后继续提交 `/confirm`
- 成功页拿到 `登録番号` 后回写后端 `ticket_order_execution.order_no`

## 安装

1. 打开 Chrome 扩展管理页：`chrome://extensions`
2. 开启“开发者模式”
3. 点击“加载已解压的扩展程序”
4. 选择目录：

`D:\workspace\codex\order_x\chrome-extensions\hands-form-helper`

## 配置

扩展弹窗里填写：

- `后端地址`
  默认：`http://62.234.211.209:8081`
- `扩展密钥`
  默认值和后端配置一致：`change-me-hands-extension-secret`
- `自动轮询`
  打开后扩展会定时拉取下一条任务
- `轮询秒数`
  Chrome alarms 最小有效值大约是 30 秒

## 后端接口

- `POST /ticket/hands-form-extension/claim`
- `POST /ticket/hands-form-extension/heartbeat`
- `POST /ticket/hands-form-extension/report`

请求头需要：

- `X-Hands-Extension-Secret: <your-secret>`

## 备注

- 当前是 MV3 原生脚本版，不依赖打包工具。
- `hands-form` 的活动解析仍然走现有后端/Python 解析链路；只有提交执行切到了 Chrome 扩展。
