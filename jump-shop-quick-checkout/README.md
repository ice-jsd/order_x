# Jump Shop Quick Checkout

这个目录是明早抢货用的独立入口。核心流程已经写在本目录的 `jump_shop_quick_checkout.py` 里，不依赖 `../auto-py`，也不需要后端、Redis、验证码模块。

## 先安装

在本目录执行一次：

```bash
pip install -r requirements.txt
playwright install chromium
```

## 要改的文件

1. `cookies.csv`
   - 表头：`cookie,email`
   - `cookie` 填浏览器复制的 Jump Shop Cookie 字符串。
   - `email` 只用于日志和结果 CSV 标记账号，可以留空。

2. `quick_checkout.config.json`
   - `productUrl` 改成目标商品链接。
   - `startAt` 可选：填 `"09:00:00"` 或 `"2026-05-22 09:00:00"` 会等到这个时间再开始；留空就是立即执行。
   - 售卖期外商品建议把 `startAt` 设成开售时间，`addToCartRetries` 设为 `10`；失败后会立即重试。
   - `profile` 里改收货地址、电话、卡号、有效期、CVV。
   - 演练先保持 `submitPayment=false` 和 `headless=false`。
   - 正式跑前再改 `submitPayment=true`，必要时把 `headless=true`。

## 运行

Windows:

```powershell
cd jump-shop-quick-checkout
.\run_windows.ps1
```

Linux/macOS:

```bash
cd jump-shop-quick-checkout
bash ./run_linux.sh
```

或通用方式：

```bash
python run.py
```

如果配置了 `startAt`，可以提前运行命令，脚本会先检查配置和商品参数，然后倒计时等待。

结果会生成在本目录：

```text
quick_checkout_results_YYYYMMDD_HHMMSS.csv
```

## 查询商品参数

换商品时可以先跑：

```bash
python product_info.py https://jumpshop-benelic.com/collections/sjc2026/products/18068413
```

也可以直接打开 `product_info.py`，改顶部的 `DEFAULT_PRODUCT_URL` 后运行：

```bash
python product_info.py
```

## 注意

- 当前脚本以速度优先：加购接口成功后会直接进入 checkout，不再读取购物车校验。
