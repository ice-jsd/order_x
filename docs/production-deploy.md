# OrderX 线上发布流程

本文记录当前双服务器发布方式，供后续发布时复用。文档不保存服务器密码、Redis 密码、代理 Token 等敏感信息，发布前请从安全渠道获取。

## 服务器分工

- `43.167.9.242`：Python 执行器、Python 使用的 Redis。
- `62.234.211.209`：Java 后端、前端、MySQL、默认 Redis。

关键路径：

- Python 目录：`/opt/orderx/auto-py`
- Python 服务：`orderx-python-executor`
- Java Jar：`/opt/orderx/backend/ruoyi-admin.jar`
- 前端目录：`/opt/orderx/frontend/dist`
- Docker Compose：`/opt/orderx/docker-compose.yml`
- 后端容器：`orderx-backend`
- 前端容器：`orderx-frontend`

## 本地发布前检查

在仓库根目录执行：

```powershell
Get-ChildItem auto-py -Filter *.py | ForEach-Object { python -m py_compile $_.FullName }
```

```powershell
cd RuoYi-Vue-Plus
mvn -pl ruoyi-admin -am -DskipTests package
```

```powershell
cd ruoyi-plus-soybean
pnpm build
```

发布产物：

- `RuoYi-Vue-Plus/ruoyi-admin/target/ruoyi-admin.jar`
- `ruoyi-plus-soybean/dist`
- `auto-py` 源码包，不包含 `.venv`、`__pycache__`、`.cache`、`.runtime`、`.env.local`、`*.pyc`

## 数据库与平台配置同步

本次上线前，先在 `62.234.211.209` 对应的 MySQL 执行以下 SQL：

- `RuoYi-Vue-Plus/script/sql/update/update_5.6.43_ticket_lottery_batch_task.sql`
- `RuoYi-Vue-Plus/script/sql/update/update_5.6.44_ticket_mail_overview_menu.sql`
- `RuoYi-Vue-Plus/script/sql/update/update_5.6.45_ticket_hands_form_platform.sql`
- `RuoYi-Vue-Plus/script/sql/update/update_5.6.50_ticket_menu_utf8mb4_repair.sql`

执行方式必须固定为 `utf8mb4`，否则带中文菜单名的 SQL 会把 `sys_menu.menu_name/remark` 写成乱码。不要直接省略字符集参数。

推荐命令：

```bash
docker exec -i 1Panel-mysql-hy9d mysql --default-character-set=utf8mb4 -uroot -p'你的数据库密码' order_x < /path/to/update_xxx.sql
```

如果已经进入 MySQL 交互式终端，也要先执行：

```sql
SET NAMES utf8mb4;
```

其中 `update_5.6.45_ticket_hands_form_platform.sql` 会同步：

- 新平台 `hands-form`
- 历史错误编码 `handsform -> hands-form`
- `ticket_platform_config` 的能力字段：`supports_email=1`、`supports_phone_identity=0`、`supports_batch_register=0`、`supports_batch_login=0`

其中 `update_5.6.50_ticket_menu_utf8mb4_repair.sql` 用于回正已经被错误字符集写坏的票务菜单，支持重复执行。

Hands Form 默认由 43 服务器上的 Python Playwright 执行器处理，不再依赖 Chrome 扩展。旧扩展接口保留作紧急回退，但 `ticket.hands-form-extension.enabled` 默认关闭。

## 打包产物

在仓库根目录执行，生成时间戳目录：

```powershell
$ts = Get-Date -Format 'yyyyMMddHHmmss'
$deployDir = Join-Path (Resolve-Path tmp) "deploy-$ts"
New-Item -ItemType Directory -Path $deployDir -Force | Out-Null
$pythonArchive = Join-Path $deployDir "auto-py-$ts.tar.gz"
$frontendArchive = Join-Path $deployDir "frontend-dist-$ts.tar.gz"
$env:PYTHON_ARCHIVE = $pythonArchive
$env:FRONTEND_ARCHIVE = $frontendArchive

@'
import os, tarfile
from pathlib import Path

root = Path.cwd()

with tarfile.open(Path(os.environ["PYTHON_ARCHIVE"]), "w:gz") as tar:
    base = root / "auto-py"
    for path in base.rglob("*"):
        rel = path.relative_to(base)
        rel_text = str(rel).replace("\\", "/")
        if path.is_dir():
            continue
        if rel_text.startswith((".venv/", "__pycache__/", ".cache/", ".runtime/")):
            continue
        if rel_text == ".env.local":
            continue
        if rel_text.endswith((".pyc", ".pyo")):
            continue
        tar.add(path, arcname=rel_text)

with tarfile.open(Path(os.environ["FRONTEND_ARCHIVE"]), "w:gz") as tar:
    base = root / "ruoyi-plus-soybean" / "dist"
    for path in base.rglob("*"):
        if path.is_file():
            tar.add(path, arcname=str(path.relative_to(base)).replace("\\", "/"))
'@ | python -
```

## 发布 Python 到 43

上传 `auto-py-{timestamp}.tar.gz` 到 43 的 `/tmp` 后执行：

首次部署或更换密钥时，需要在 43 上维护线上私密配置。这个文件不从 Git 拉取，也不会进发布包；后续发布只覆盖代码，保留服务器上的真实 key。

```bash
cd /opt/orderx/auto-py
cp -n .env.example .env.local
chmod 600 .env.local
vi .env.local
```

至少填写：

```env
CAPSOLVER_API_KEY=真实值
LOTTERY_REDIS_PASSWORD=真实值
LOTTERY_DELAYED_PROMOTER_ENABLED=false
HANDS_FORM_PROXY_ENABLED=false
HANDS_FORM_CAPTCHA_PROVIDER=capsolver
HANDS_FORM_CAPTCHA_HIGH_SCORE=false
HANDS_FORM_CAPTCHA_SESSION_MODE=true
HANDS_FORM_DRY_RUN=true
```

首次灰度时保持 `HANDS_FORM_DRY_RUN=true`，只验证填写页到最终确认页，不会点击最终提交。Hands 当前使用 reCAPTCHA v3 Enterprise，因此 `CAPSOLVER_API_KEY` 必须可用。IPRoyal 访问 Hands 可能被目标站重置，灰度期间保持 `HANDS_FORM_PROXY_ENABLED=false`，不要让 LivePocket 的全局代理设置自动套用到 Hands。确认执行结果包含 `HANDS_DRY_RUN_CONFIRM_READY`，并检查 `/opt/orderx/auto-py/.runtime/hands-form` 的截图和 HTML 后，再改为 `false`。
重启 Python 服务后先访问 `/health`，确认返回的 `handsFormDryRun=true`、`delayedPromoterEnabled=false`，再创建单账号灰度任务。

当前 Java 默认保持 `HANDS_FORM_EXECUTION_MODE=extension`。2026-10-05 对当前 Hands 活动做过真实 dry-run：直连可以打开并填写表单，提交请求也包含 reCAPTCHA token，但站点的 reCAPTCHA v3 Enterprise 拒绝了浏览器原生 token、CapSolver 标准 token、会话模式 token 和 M1 token；IPRoyal 访问该域名还会被连接重置。因此在 CapSolver 完成该 site key 的站点适配并重新 dry-run 成功前，不得把生产配置切到 `python`。旧扩展回退配置为：

```env
HANDS_FORM_EXECUTION_MODE=extension
HANDS_FORM_EXTENSION_ENABLED=true
```

两个开关必须成对修改；如果只切到 `extension` 而未启用扩展接口，执行记录会直接失败并提示配置错误，不会无限停留在“等待扩展领取”。回退后重启 Java 服务，新领取和到期推进的 Hands 任务会恢复为“等待 Hands Chrome 扩展领取”。已经进入 Python ready Stream 的任务不受该开关追溯影响，回退前应先停止 Python 执行器并确认没有正在运行的 Hands 任务，再切换 Java 配置；处理完残留任务后可恢复 Python 服务。`LOTTERY_DELAYED_PROMOTER_ENABLED` 应继续保持 `false`，延时任务统一由 Java 推进，单次推进异常会在 5 秒后重新入队。

如果 Python 服务通过 systemd 指定了其它工作目录也没关系，代码会按自身文件位置读取 `/opt/orderx/auto-py/.env.local`。

```bash
set -euo pipefail
TS=替换为时间戳
mkdir -p /opt/orderx/backup/$TS
cd /opt/orderx
if [ -d auto-py ]; then
  tar --exclude=auto-py/.venv --exclude=auto-py/__pycache__ --exclude=auto-py/.cache \
    -czf /opt/orderx/backup/$TS/auto-py-before.tar.gz auto-py
fi

cd /opt/orderx/auto-py
tar -xzf /tmp/auto-py-$TS.tar.gz
if [ ! -f .env.local ]; then
  cp .env.example .env.local
  chmod 600 .env.local
  echo "已创建 /opt/orderx/auto-py/.env.local，请填写线上真实 key 后重新执行发布命令"
  exit 1
fi
rm -rf __pycache__
.venv/bin/python -m py_compile *.py
systemctl restart orderx-python-executor
sleep 3
systemctl is-active orderx-python-executor
curl -sS --max-time 8 http://127.0.0.1:8098/health
```

常用日志：

```bash
journalctl -u orderx-python-executor -f
journalctl -u orderx-python-executor -n 120 --no-pager
```

## 发布 Java 和前端到 62

上传 `ruoyi-admin.jar` 和 `frontend-dist-{timestamp}.tar.gz` 到 62 的 `/tmp` 后执行：

```bash
set -euo pipefail
TS=替换为时间戳
cd /opt/orderx
mkdir -p /opt/orderx/backup/$TS /opt/orderx/backend/backup /opt/orderx/frontend/backup

if [ -f /opt/orderx/backend/ruoyi-admin.jar ]; then
  cp /opt/orderx/backend/ruoyi-admin.jar /opt/orderx/backup/$TS/ruoyi-admin.jar
  cp /opt/orderx/backend/ruoyi-admin.jar /opt/orderx/backend/backup/ruoyi-admin.jar.$TS
fi

if [ -d /opt/orderx/frontend/dist ]; then
  tar -czf /opt/orderx/backup/$TS/frontend-dist.tar.gz -C /opt/orderx/frontend dist
  tar -czf /opt/orderx/frontend/backup/dist-$TS.tar.gz -C /opt/orderx/frontend dist
fi

cp /tmp/ruoyi-admin-$TS.jar /opt/orderx/backend/ruoyi-admin.jar
rm -rf /opt/orderx/frontend/dist.new
mkdir -p /opt/orderx/frontend/dist.new
tar -xzf /tmp/frontend-dist-$TS.tar.gz -C /opt/orderx/frontend/dist.new
rm -rf /opt/orderx/frontend/dist
mv /opt/orderx/frontend/dist.new /opt/orderx/frontend/dist

docker compose -f /opt/orderx/docker-compose.yml up -d --build orderx-backend orderx-frontend
sleep 8
docker compose -f /opt/orderx/docker-compose.yml ps
```

常用日志：

```bash
docker logs -f orderx-backend
docker logs --tail 120 orderx-backend
docker logs --tail 80 orderx-frontend
```

## 发布后验证

43：

```bash
systemctl is-active orderx-python-executor
curl -sS --max-time 8 http://127.0.0.1:8098/health
```

62：

```bash
cd /opt/orderx
docker compose -f /opt/orderx/docker-compose.yml ps
curl -sS --max-time 12 http://127.0.0.1:8081/ | head -c 200
curl -sS --max-time 12 http://127.0.0.1/ | head -c 200
docker exec orderx-backend sh -lc "curl -sS --max-time 8 http://host.docker.internal:8098/health | head -c 600"
```

后端异常快速筛选：

```bash
docker logs --since 2m orderx-backend 2>&1 | grep -Ei "error|exception|failed|缺少|refused|timeout" | tail -40
```

## 回滚

回滚 Python：

```bash
TS=要回滚的备份时间戳
cd /opt/orderx
rm -rf auto-py.rollback
mkdir -p auto-py.rollback
tar -xzf /opt/orderx/backup/$TS/auto-py-before.tar.gz -C auto-py.rollback
rsync -a --delete auto-py.rollback/auto-py/ /opt/orderx/auto-py/
systemctl restart orderx-python-executor
curl -sS --max-time 8 http://127.0.0.1:8098/health
```

回滚 Java 和前端：

```bash
TS=要回滚的备份时间戳
cp /opt/orderx/backup/$TS/ruoyi-admin.jar /opt/orderx/backend/ruoyi-admin.jar
rm -rf /opt/orderx/frontend/dist
tar -xzf /opt/orderx/backup/$TS/frontend-dist.tar.gz -C /opt/orderx/frontend
docker compose -f /opt/orderx/docker-compose.yml up -d --build orderx-backend orderx-frontend
docker compose -f /opt/orderx/docker-compose.yml ps
```

## 配置约定

- Java 调 Python：`ticket.python-executor.base-url`
- Python 回调 Java：`TICKET_BACKEND_BASE_URL`
- Java 默认 Redis：`spring.data.redis`
- Java 访问 Python Redis：`ticket.python-executor.redis`
- Python 本机 Redis：`LOTTERY_REDIS_HOST=127.0.0.1`
- LivePocket 代理开关：`LIVEPOCKET_PROXY_ENABLED`

Decodo 住宅代理使用 `LIVEPOCKET_PROXY_PROVIDER=decodo`、`gate.decodo.com:7000` 和代理专用用户名/密码。LivePocket 属于 Decodo 默认限制的 Ticketing 类目标，必须先由 Decodo 客服审核解锁，再设置 `LIVEPOCKET_PROXY_TARGET_APPROVED=true`、修改线上环境并重启 Python 服务。控制台登录密码和代理凭据均不得提交到 Git。

IPRoyal 住宅代理使用 `LIVEPOCKET_PROXY_PROVIDER=iproyal`、`geo.iproyal.com:12321` 和 Residential Proxy 专用用户名/密码。程序为每个账号生成独立的日本粘性 session；首次切换必须先用小批量账号验证登录、验证码、提交和失败换 IP，再逐步扩大并发。

配置变更后必须重启对应服务，并重新执行发布后验证。
