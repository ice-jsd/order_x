"""
Jump Shop (Shopify) 自动化能力

范围：
- 商品页解析
- 账号批量注册 / 登录
- 普通购买执行（加购 -> checkout -> 填单 -> 提交）
"""
import html
import json
import os
import random
import re
import time
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import parse_qs, urljoin, urlparse

import requests

from backend_api import TicketBackendApi
from ticket_runtime import get_logger, log_context
from capsolver_captcha import CaptchaSolver, capsolver_api_key
from ticket_captcha import build_jump_shop_hcaptcha_verifier
from redis_events import LOGIN_RESULT_STREAM, REGISTER_RESULT_STREAM, RedisEventPublisher


LOGGER = get_logger("jump-shop")
print = LOGGER.print

JUMP_SHOP_BASE_URL = "https://jumpshop-benelic.com"
JUMP_SHOP_LOGIN_URL = f"{JUMP_SHOP_BASE_URL}/account/login"
JUMP_SHOP_REGISTER_URL = f"{JUMP_SHOP_BASE_URL}/account/register"
JUMP_SHOP_CHECKOUT_URL = f"{JUMP_SHOP_BASE_URL}/checkout"
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/148.0.0.0 Safari/537.36"
)
DEFAULT_PRODUCT_MAX_QUANTITY = 10
HCAPTCHA_SITE_KEY = "f06e6c50-85a8-45c8-87d0-21a2b65856fe"
MAX_REGISTER_PHONE_RETRIES = 5
LOGIN_ACTION_TIMEOUT_MS = 15000

JUMP_SHOP_FAMILY_NAMES = [
    ("佐藤", "サトウ"),
    ("鈴木", "スズキ"),
    ("高橋", "タカハシ"),
    ("田中", "タナカ"),
    ("伊藤", "イトウ"),
    ("渡辺", "ワタナベ"),
    ("山本", "ヤマモト"),
    ("中村", "ナカムラ"),
]

JUMP_SHOP_GIVEN_NAMES = [
    ("太一", "タイチ", "male"),
    ("悠斗", "ユウト", "male"),
    ("蓮", "レン", "male"),
    ("結衣", "ユイ", "female"),
    ("美咲", "ミサキ", "female"),
    ("さくら", "サクラ", "female"),
    ("陽菜", "ヒナ", "female"),
    ("葵", "アオイ", "female"),
]

JUMP_SHOP_ADDRESSES = [
    {
        "postalCode": "1500001",
        "province": "東京都",
        "city": "渋谷区",
        "address1": "神宮前1-1-1",
        "address2": "青山ハイツ101",
    },
    {
        "postalCode": "1600022",
        "province": "東京都",
        "city": "新宿区",
        "address1": "新宿3-5-8",
        "address2": "メゾン新宿303",
    },
    {
        "postalCode": "5300001",
        "province": "大阪府",
        "city": "大阪市北区",
        "address1": "梅田2-2-2",
        "address2": "梅田タワー505",
    },
    {
        "postalCode": "4600008",
        "province": "愛知県",
        "city": "名古屋市中区",
        "address1": "栄3-3-3",
        "address2": "サカエビル808",
    },
]


@dataclass
class JumpShopIdentity:
    last_name: str
    first_name: str
    last_name_kana: str
    first_name_kana: str
    gender: str
    phone: str
    postal_code: str
    province: str
    city: str
    address1: str
    address2: str

    def to_req_data(self) -> Dict[str, Any]:
        return {
            "lastName": self.last_name,
            "firstName": self.first_name,
            "lastNameKana": self.last_name_kana,
            "firstNameKana": self.first_name_kana,
            "gender": self.gender,
            "phoneNumber": self.phone,
            "postalCode": self.postal_code,
            "province": self.province,
            "city": self.city,
            "address1": self.address1,
            "address2": self.address2,
            "countryCode": "JP",
            "generatedBy": "jump-shop-register-v1",
        }


@dataclass(frozen=True)
class RecaptchaConfig:
    site_key: Optional[str] = None
    enterprise: bool = False
    invisible: bool = False
    page_action: str = ""
    enterprise_payload_s: str = ""
    recaptcha_data_s_value: str = ""


def compact_text(value: Any, limit: int = 240) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:limit]


def customer_fields_endpoint_name(url: str) -> str:
    text = str(url or "")
    if "cfcs.heliumdev.workers.dev/session_tokens" in text:
        return "session_tokens"
    if "app.customerfields.com/embed_api/v4/customers.json" in text:
        return "customers"
    return ""


def summarize_customer_fields_response(url: str, status: Any, body: Any, limit: int = 800) -> Dict[str, Any]:
    endpoint = customer_fields_endpoint_name(url)
    text = str(body or "")
    summary: Dict[str, Any] = {
        "endpoint": endpoint or "unknown",
        "status": status,
    }
    if endpoint == "session_tokens" and int(status or 0) < 400:
        summary["bodyLength"] = len(text)
    else:
        summary["body"] = compact_text(text, limit)
    return summary


def attach_customer_fields_network_debug(page: Any, events: List[Dict[str, Any]]) -> None:
    def on_response(response: Any) -> None:
        url = str(getattr(response, "url", "") or "")
        if not customer_fields_endpoint_name(url):
            return
        status = getattr(response, "status", None)
        try:
            body = response.text()
        except Exception as exc:
            events.append(
                {
                    "endpoint": customer_fields_endpoint_name(url),
                    "status": status,
                    "bodyError": compact_text(str(exc), 300),
                }
            )
            return
        events.append(summarize_customer_fields_response(url, status, body))

    page.on("response", on_response)


def visible_text(page_text: str) -> str:
    text = re.sub(r"<(script|style)\b.*?</\1>", " ", page_text or "", flags=re.I | re.S)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"</(p|div|li|dt|dd|tr|h\d)>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    return re.sub(r"\n+", "\n", text).strip()


def extract_page_title(page_text: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", page_text or "", flags=re.I | re.S)
    return compact_text(visible_text(match.group(1)), 300) if match else ""


def first_query_value(query: Dict[str, List[str]], key: str) -> str:
    return str((query.get(key) or [""])[0] or "").strip()


def extract_recaptcha_config(page_text: str) -> RecaptchaConfig:
    text = page_text or ""
    for url in re.findall(
        r'https://www\.google\.com/recaptcha/(?:enterprise/)?(?:anchor|bframe)[^"\'<>\s]+',
        text,
        flags=re.I,
    ):
        parsed = urlparse(html.unescape(url))
        query = parse_qs(parsed.query)
        site_key = first_query_value(query, "k")
        if site_key and site_key != HCAPTCHA_SITE_KEY:
            return RecaptchaConfig(
                site_key=site_key,
                enterprise="/enterprise/" in parsed.path,
                invisible=first_query_value(query, "size").lower() == "invisible",
                page_action=first_query_value(query, "sa"),
                enterprise_payload_s=first_query_value(query, "s"),
                recaptcha_data_s_value=first_query_value(query, "s"),
            )
    patterns: List[Tuple[str, bool]] = [
        (r'g-recaptcha[^>]+data-sitekey=["\']([^"\']+)["\']', False),
        (r'grecaptcha\.enterprise\.render\([^,]+,\s*\{[^}]*sitekey["\']?\s*:\s*["\']([^"\']+)["\']', True),
        (r'grecaptcha\.render\([^,]+,\s*\{[^}]*sitekey["\']?\s*:\s*["\']([^"\']+)["\']', False),
        (r'["\']sitekey["\']\s*:\s*["\'](6L[\w-]+)["\']', False),
        (r'recaptcha/api\.js\?render=(6L[\w-]+)', False),
    ]
    for pattern, enterprise in patterns:
        for candidate in re.findall(pattern, text, flags=re.I | re.S):
            value = str(candidate or "").strip()
            if value and value != HCAPTCHA_SITE_KEY:
                return RecaptchaConfig(site_key=value, enterprise=enterprise)
    return RecaptchaConfig()


def request_headers(referer: str = "") -> Dict[str, str]:
    headers = {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ja,en-US;q=0.9,en;q=0.8,zh-CN;q=0.7",
    }
    if referer:
        headers["Referer"] = referer
    return headers


def browser_executable_path() -> Optional[str]:
    candidates = [
        os.environ.get("JUMP_SHOP_BROWSER_PATH", ""),
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    ]
    return next((path for path in candidates if path and os.path.exists(path)), None)


def parse_json_maybe(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            data = json.loads(value)
            return data if isinstance(data, dict) else {}
        except json.JSONDecodeError:
            return {}
    return {}


def normalize_jump_shop_product_url(product_url: str) -> str:
    parsed = urlparse(str(product_url or "").strip())
    if not parsed.scheme or not parsed.netloc:
        raise ValueError("商品链接不合法")
    return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"


def create_jump_shop_identity(phone: Optional[str] = None) -> JumpShopIdentity:
    family_name, family_kana = random.choice(JUMP_SHOP_FAMILY_NAMES)
    given_name, given_kana, gender = random.choice(JUMP_SHOP_GIVEN_NAMES)
    address = random.choice(JUMP_SHOP_ADDRESSES)
    return JumpShopIdentity(
        last_name=family_name,
        first_name=given_name,
        last_name_kana=family_kana,
        first_name_kana=given_kana,
        gender=gender,
        phone=(str(phone or "").strip() or random_jump_shop_phone()),
        postal_code=address["postalCode"],
        province=address["province"],
        city=address["city"],
        address1=address["address1"],
        address2=address["address2"],
    )


def random_jump_shop_phone() -> str:
    return "090" + "".join(random.choice("0123456789") for _ in range(8))


def is_jump_shop_phone_conflict(text: str) -> bool:
    normalized = compact_text(text, 1000)
    if not normalized:
        return False
    phone_keywords = ("電話番号", "携帯番号", "phone", "tel")
    duplicate_keywords = ("既に", "すでに", "登録", "使用", "重複", "already", "taken", "exists", "used")
    return any(keyword in normalized for keyword in phone_keywords) and any(
        keyword in normalized for keyword in duplicate_keywords
    )


def extract_input_value(page_text: str, input_name: str) -> Optional[str]:
    pattern = (
        r"<input\b(?=[^>]*\bname=(['\"])"
        + re.escape(input_name)
        + r"\1)(?=[^>]*\bvalue=(['\"])(.*?)\2)[^>]*>"
    )
    match = re.search(pattern, page_text or "", flags=re.I | re.S)
    return html.unescape(match.group(3)) if match else None


def extract_meta_content(page_text: str, key: str, attr: str = "property") -> Optional[str]:
    pattern = (
        r"<meta\b(?=[^>]*\b"
        + re.escape(attr)
        + r"=(['\"])"
        + re.escape(key)
        + r"\1)[^>]*\bcontent=(['\"])(.*?)\2[^>]*>"
    )
    match = re.search(pattern, page_text or "", flags=re.I | re.S)
    return html.unescape(match.group(3)) if match else None


def extract_variant_id_from_json(page_text: str) -> Optional[int]:
    patterns = [
        r'"selected_or_first_available_variant"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)',
        r'"variants"\s*:\s*\[\s*\{[^}]*"id"\s*:\s*(\d+)',
        r'"variantId"\s*:\s*"?(\\d+)"?',
    ]
    for pattern in patterns:
        match = re.search(pattern, page_text or "", flags=re.I | re.S)
        if match:
            return int(match.group(1))
    return None


def extract_max_quantity_hint(page_text: str) -> int:
    hints = re.findall(r"(?:購入制限|お一人様|上限|最大)[^0-9]{0,8}(\d{1,2})", visible_text(page_text or ""))
    values = [int(item) for item in hints if item.isdigit()]
    return max(values) if values else DEFAULT_PRODUCT_MAX_QUANTITY


def fetch_jump_shop_product_info(product_url: str) -> Dict[str, Any]:
    normalized_url = normalize_jump_shop_product_url(product_url)
    session = requests.Session()
    response = session.get(normalized_url, headers=request_headers(), timeout=60)
    response.raise_for_status()
    page_text = response.text or ""
    variant_id = extract_input_value(page_text, "id")
    product_id = extract_input_value(page_text, "product-id")
    section_id = extract_input_value(page_text, "section-id")
    title = (
        extract_meta_content(page_text, "og:title")
        or extract_meta_content(page_text, "twitter:title", "name")
        or extract_page_title(page_text)
    )
    image_url = extract_meta_content(page_text, "og:image") or ""
    available = "sold_out" not in page_text.lower() and "売り切れ" not in visible_text(page_text)
    parsed = {
        "productUrl": normalized_url,
        "title": compact_text(title, 300),
        "imageUrl": image_url,
        "variantId": int(variant_id) if variant_id and variant_id.isdigit() else extract_variant_id_from_json(page_text),
        "productId": int(product_id) if product_id and product_id.isdigit() else None,
        "sectionId": section_id or "",
        "available": bool(available),
        "maxQuantity": min(max(extract_max_quantity_hint(page_text), 1), DEFAULT_PRODUCT_MAX_QUANTITY),
        "currency": "JPY",
        "purchaseMode": "cart_checkout",
        "paymentMode": "credit_card",
    }
    if not parsed["variantId"] or not parsed["productId"] or not parsed["sectionId"]:
        raise RuntimeError("商品页缺少必要的 Shopify 参数（variantId/productId/sectionId）")
    return parsed


def export_browser_login_context(context: Any) -> Dict[str, Any]:
    cookies = context.cookies(JUMP_SHOP_BASE_URL)
    cookie_pairs = [f"{item.get('name')}={item.get('value')}" for item in cookies if item.get("name")]
    return {
        "format": "auto-py-jump-shop-login-context-v1",
        "exportedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "loginUrl": JUMP_SHOP_LOGIN_URL,
        "userAgent": DEFAULT_USER_AGENT,
        "headers": {
            "User-Agent": DEFAULT_USER_AGENT,
            "Accept-Language": "ja,en-US;q=0.9,en;q=0.8,zh-CN;q=0.7",
        },
        "cookies": cookies,
        "cookiesJson": {item.get("name"): item.get("value") for item in cookies if item.get("name")},
        "cookieHeader": "; ".join(cookie_pairs),
    }


def add_cookies_from_login_context(context: Any, login_context: Dict[str, Any]) -> None:
    cookies = login_context.get("cookies")
    if isinstance(cookies, list) and cookies:
        normalized = []
        for item in cookies:
            if not isinstance(item, dict) or not item.get("name"):
                continue
            normalized.append(
                {
                    "name": item.get("name"),
                    "value": item.get("value", ""),
                    "domain": item.get("domain") or ".jumpshop-benelic.com",
                    "path": item.get("path") or "/",
                    "secure": bool(item.get("secure", True)),
                    "httpOnly": bool(item.get("httpOnly", False)),
                    "sameSite": item.get("sameSite") or "Lax",
                }
            )
        if normalized:
            context.add_cookies(normalized)
            return
    cookie_map = login_context.get("cookiesJson")
    if isinstance(cookie_map, dict) and cookie_map:
        context.add_cookies(
            [
                {
                    "name": str(name),
                    "value": str(value),
                    "domain": ".jumpshop-benelic.com",
                    "path": "/",
                    "secure": True,
                    "sameSite": "Lax",
                }
                for name, value in cookie_map.items()
            ]
        )


def set_captcha_response(page: Any, hcaptcha_token: str = "", recaptcha_token: str = "") -> None:
    page.evaluate(
        """
        ({ hcaptchaToken, recaptchaToken }) => {
          const updateField = (selector, value) => {
            document.querySelectorAll(selector).forEach((element) => {
              element.value = value;
              element.innerHTML = value;
              element.textContent = value;
              element.dispatchEvent(new Event('input', { bubbles: true }));
              element.dispatchEvent(new Event('change', { bubbles: true }));
            });
          };
          if (hcaptchaToken) {
            updateField('textarea[name="h-captcha-response"], input[name="h-captcha-response"]', hcaptchaToken);
          }
          if (recaptchaToken) {
            updateField('textarea[name="g-recaptcha-response"], input[name="g-recaptcha-response"]', recaptchaToken);
          }
        }
        """,
        {"hcaptchaToken": hcaptcha_token, "recaptchaToken": recaptcha_token},
    )


def apply_customer_fields_recaptcha_token(page: Any, token: str) -> None:
    if not token:
        return
    if hasattr(page, "wait_for_function"):
        try:
            page.wait_for_function(
                """
                () => !!(
                  (window.CF && window.CF.customer && window.CF.customer.grecaptcha &&
                    window.CF.customer.grecaptcha.enterprise && window.CF.customer.grecaptcha.enterprise.execute) ||
                  (window.grecaptcha && window.grecaptcha.enterprise && window.grecaptcha.enterprise.execute)
                )
                """,
                timeout=5000,
            )
        except Exception:
            pass
    page.evaluate(
        """
        ({ token }) => {
          const customer = window.CF && window.CF.customer ? window.CF.customer : null;
          const target = (customer && customer.grecaptcha) || window.grecaptcha || {};
          target.enterprise = target.enterprise || {};
          window.__jumpShopRecaptchaPatched = true;
          window.__jumpShopRecaptchaExecuteCalls = [];
          window.__jumpShopRecaptchaTokenLength = String(token || '').length;
          target.enterprise.execute = async (siteKey, options = {}) => {
            window.__jumpShopRecaptchaExecuteCalls.push({
              siteKey: String(siteKey || ''),
              action: String(options && options.action || ''),
              at: Date.now(),
            });
            return token;
          };
          window.grecaptcha = target;
          if (customer) {
            window.CF.customer.grecaptcha = target;
          }
        }
        """,
        {"token": token},
    )


def _call_with_optional_timeout(method: Any, timeout_ms: int) -> Any:
    try:
        return method(timeout=timeout_ms)
    except TypeError:
        return method()


def maybe_click(page: Any, selectors: Iterable[str], timeout_ms: int = LOGIN_ACTION_TIMEOUT_MS) -> bool:
    for selector in selectors:
        locator = page.locator(selector)
        if locator.count():
            _call_with_optional_timeout(locator.first.click, timeout_ms)
            return True
    return False


def maybe_fill(page: Any, selectors: Iterable[str], value: str, frame: Any = None, timeout_ms: int = LOGIN_ACTION_TIMEOUT_MS) -> bool:
    if value is None:
        return False
    target = frame or page
    for selector in selectors:
        locator = target.locator(selector)
        if locator.count():
            try:
                locator.first.fill(str(value), timeout=timeout_ms)
            except TypeError:
                locator.first.fill(str(value))
            return True
    return False


def set_field_value(page: Any, selectors: Iterable[str], value: str, frame: Any = None) -> bool:
    if value is None:
        return False
    target = frame or page
    for selector in selectors:
        locator = target.locator(selector)
        if not locator.count():
            continue
        target.evaluate(
            """
            ({ selector, value }) => {
              document.querySelectorAll(selector).forEach((element) => {
                element.value = String(value);
                element.setAttribute('value', String(value));
                element.dispatchEvent(new Event('input', { bubbles: true }));
                element.dispatchEvent(new Event('change', { bubbles: true }));
              });
            }
            """,
            {"selector": selector, "value": str(value)},
        )
        return True
    return False


def maybe_focus(page: Any, selectors: Iterable[str], frame: Any = None) -> bool:
    target = frame or page
    for selector in selectors:
        locator = target.locator(selector)
        if locator.count():
            try:
                locator.first.focus()
                return True
            except Exception:
                continue
    return False


def maybe_click_radio(page: Any, selector: str, value: str, frame: Any = None) -> bool:
    target = frame or page
    locator = target.locator(selector)
    if not locator.count():
        return False
    for idx in range(locator.count()):
        item = locator.nth(idx)
        try:
            current = item.get_attribute("value")
            if current == value:
                item.check()
                return True
        except Exception:
            continue
    return False


def maybe_select(page: Any, selectors: Iterable[str], value: str, labels: Optional[List[str]] = None, frame: Any = None) -> bool:
    target = frame or page
    labels = labels or []
    for selector in selectors:
        locator = target.locator(selector)
        if not locator.count():
            continue
        try:
            locator.first.select_option(value=value)
            return True
        except Exception:
            pass
        for label in labels:
            try:
                locator.first.select_option(label=label)
                return True
            except Exception:
                continue
    return False


def load_register_captcha_context(page: Any) -> Tuple[str, RecaptchaConfig]:
    """等待 Jump Shop 注册页的动态验证码脚本注入完成后再读取 DOM。"""
    maybe_focus(page, ['#create_customer input[name="customer[email]"]', '#create_customer input[name="email"]'])
    page.wait_for_timeout(2500)
    try:
        page.wait_for_function(
            """
            () => !!(
              document.querySelector('script[src*="recaptcha/enterprise.js"]') ||
              document.querySelector('iframe[src*="recaptcha/enterprise/anchor"]') ||
              document.querySelector('iframe[src*="recaptcha/api2/anchor"]')
            )
            """,
            timeout=5000,
        )
    except Exception:
        pass
    page.wait_for_timeout(800)
    register_html = page.content()
    recaptcha_config = extract_recaptcha_config(register_html)
    return register_html, recaptcha_config


def collect_register_debug_state(page: Any) -> Dict[str, Any]:
    try:
        return page.evaluate(
            """
            () => {
              const visible = (el) => !!(el && (el.offsetWidth || el.offsetHeight || el.getClientRects().length));
              const valueOf = (el) => {
                if (!el) return '';
                const raw = typeof el.value === 'string' ? el.value : (el.textContent || '');
                return String(raw || '');
              };
              const checkedValue = (selector) => {
                const item = document.querySelector(`${selector}:checked`);
                return item ? valueOf(item) : '';
              };
              const visibleTexts = (selector) => {
                return Array.from(document.querySelectorAll(selector))
                  .filter(visible)
                  .map((el) => String(el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim())
                  .filter(Boolean)
                  .slice(0, 8);
              };
              const fieldState = (selector) => {
                const nodes = Array.from(document.querySelectorAll(selector));
                return {
                  count: nodes.length,
                  visibleCount: nodes.filter(visible).length,
                  nonEmptyCount: nodes.filter((el) => valueOf(el).trim()).length,
                };
              };
              const form = document.querySelector('#create_customer');
              const submit = document.querySelector('#create_customer button[type="submit"], form[action="/account"] button[type="submit"]');
              return {
                url: location.href,
                formAction: form ? form.getAttribute('action') || '' : '',
                formDataCfState: form ? form.getAttribute('data-cf-state') || '' : '',
                formHcaptchaBound: !!(form && form.dataset && form.dataset.hcaptchaBound),
                formRecaptchaBound: !!(form && form.dataset && form.dataset.recaptchaBound),
                hasRecaptchaEnterpriseScript: !!document.querySelector('script[src*="recaptcha/enterprise.js"]'),
                hasRecaptchaEnterpriseAnchor: !!document.querySelector('iframe[src*="recaptcha/enterprise/anchor"]'),
                hasRecaptchaApi2Anchor: !!document.querySelector('iframe[src*="recaptcha/api2/anchor"]'),
                hasHcaptchaScript: !!document.querySelector('script[src*="js.hcaptcha.com"]'),
                recaptchaPatch: {
                  patched: !!window.__jumpShopRecaptchaPatched,
                  customerHasGrecaptcha: !!(window.CF && window.CF.customer && window.CF.customer.grecaptcha),
                  customerExecutePatched: !!(
                    window.CF && window.CF.customer && window.CF.customer.grecaptcha &&
                    window.CF.customer.grecaptcha.enterprise &&
                    window.CF.customer.grecaptcha.enterprise.execute &&
                    window.CF.customer.grecaptcha.enterprise.execute.toString().includes('__jumpShopRecaptchaExecuteCalls')
                  ),
                  executeCallCount: Array.isArray(window.__jumpShopRecaptchaExecuteCalls) ? window.__jumpShopRecaptchaExecuteCalls.length : 0,
                  lastExecute: Array.isArray(window.__jumpShopRecaptchaExecuteCalls) && window.__jumpShopRecaptchaExecuteCalls.length
                    ? window.__jumpShopRecaptchaExecuteCalls[window.__jumpShopRecaptchaExecuteCalls.length - 1]
                    : null,
                  tokenLength: window.__jumpShopRecaptchaTokenLength || 0,
                },
                hcaptchaResponse: fieldState('textarea[name="h-captcha-response"], input[name="h-captcha-response"]'),
                grecaptchaResponse: fieldState('textarea[name="g-recaptcha-response"], input[name="g-recaptcha-response"]'),
                customerFieldsErrors: visibleTexts('.cf-form-errors, .cf-form-error, .cf-field-error, [class*="cf-"][class*="error"], [role="alert"]'),
                visibleFields: {
                  lastName: fieldState('input[name="last_name"]'),
                  firstName: fieldState('input[name="first_name"]'),
                  email: fieldState('input[name="email"]'),
                  customerEmail: fieldState('input[name="customer[email]"]'),
                  customerPassword: fieldState('input[name="customer[password]"]'),
                  zip: fieldState('input[name="default_address.zip"]'),
                  province: fieldState('select[name="default_address.province"]'),
                  city: fieldState('input[name="default_address.city"]'),
                  address1: fieldState('input[name="default_address.address1"]'),
                  address2: fieldState('input[name="default_address.address2"]'),
                  phone: fieldState('input[name="phone"]'),
                  gender: fieldState('input[name="gender"]'),
                  terms: fieldState('input[name="new_single_checkbox_field"]'),
                },
                selectedGender: checkedValue('input[name="gender"]'),
                termsChecked: !!document.querySelector('input[name="new_single_checkbox_field"]:checked'),
                submitVisible: visible(submit),
                submitDisabled: !!(submit && submit.disabled),
              };
            }
            """
        )
    except Exception as exc:
        return {"debugError": str(exc), "url": getattr(page, "url", "")}


def launch_browser_context():
    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:
        raise RuntimeError(f"Playwright 不可用: {exc}") from exc
    return sync_playwright()


def create_browser(playwright: Any):
    launch_options: Dict[str, Any] = {"headless": True}
    browser_path = browser_executable_path()
    if browser_path:
        launch_options["executable_path"] = browser_path
    return playwright.chromium.launch(**launch_options)


def extract_hcaptcha_site_key_from_page(page: Any) -> str:
    try:
        site_key = page.evaluate(
            """
            () => {
              const keys = Array.from(document.querySelectorAll('[data-sitekey], [data-hcaptcha-sitekey]'))
                .map((el) => el.getAttribute('data-sitekey') || el.getAttribute('data-hcaptcha-sitekey') || '')
                .map((value) => String(value || '').trim())
                .filter(Boolean)
                .filter((value) => !value.startsWith('6L'));
              return keys[0] || '';
            }
            """
        )
        return str(site_key or "").strip()
    except Exception:
        return ""


def login_captcha_context(page: Any) -> Dict[str, Any]:
    try:
        return page.evaluate(
            """
            () => {
              const fieldState = (selector) => {
                const nodes = Array.from(document.querySelectorAll(selector));
                return {
                  count: nodes.length,
                  nonEmptyCount: nodes.filter((el) => String(el.value || el.textContent || '').trim()).length,
                };
              };
              return {
                url: location.href,
                hasHcaptchaScript: !!document.querySelector('script[src*="js.hcaptcha.com"]'),
                hcaptchaSiteKey: Array.from(document.querySelectorAll('[data-sitekey], [data-hcaptcha-sitekey]'))
                  .map((el) => el.getAttribute('data-sitekey') || el.getAttribute('data-hcaptcha-sitekey') || '')
                  .filter(Boolean)[0] || '',
                hcaptchaResponse: fieldState('textarea[name="h-captcha-response"], input[name="h-captcha-response"]'),
                email: fieldState('#CustomerEmail, input[name="customer[email]"]'),
                password: fieldState('#CustomerPassword, input[name="customer[password]"]'),
              };
            }
            """
        )
    except Exception as exc:
        return {"error": str(exc), "url": getattr(page, "url", "")}


def login_jump_shop(
    email: str,
    password: str,
    *,
    return_browser: bool = False,
) -> Tuple[Optional[Dict[str, Any]], Optional[Any], Optional[Any]]:
    if not email or not password:
        raise RuntimeError("缺少 Jump Shop 登录邮箱或密码")
    LOGGER.info("[jump-shop-login] stage=browser-start email=%s", email)
    playwright_manager = launch_browser_context()
    playwright = playwright_manager.__enter__()
    browser = create_browser(playwright)
    context = browser.new_context(user_agent=DEFAULT_USER_AGENT, locale="ja-JP")
    page = context.new_page()
    try:
        page.goto(JUMP_SHOP_LOGIN_URL, wait_until="domcontentloaded", timeout=90000)
        LOGGER.info("[jump-shop-login] stage=page-ready url=%s email=%s", getattr(page, "url", ""), email)
        site_key = extract_hcaptcha_site_key_from_page(page) or HCAPTCHA_SITE_KEY
        LOGGER.info(
            "[jump-shop-login] stage=captcha-context url=%s siteKeyDefault=%s state=%s email=%s",
            getattr(page, "url", ""),
            site_key == HCAPTCHA_SITE_KEY,
            json.dumps(login_captcha_context(page), ensure_ascii=False),
            email,
        )
        email_filled = maybe_fill(page, ['#CustomerEmail', 'input[name="customer[email]"]'], email)
        password_filled = maybe_fill(page, ['#CustomerPassword', 'input[name="customer[password]"]'], password)
        LOGGER.info(
            "[jump-shop-login] stage=form-filled results=%s email=%s",
            json.dumps({"email": email_filled, "password": password_filled}, ensure_ascii=False),
            email,
        )
        page_url = getattr(page, "url", "") or JUMP_SHOP_LOGIN_URL
        LOGGER.info("[jump-shop-login] stage=solve-hcaptcha url=%s email=%s", page_url, email)
        hcaptcha_solver = build_jump_shop_hcaptcha_verifier()
        hcaptcha_token = hcaptcha_solver.solve_hcaptcha(site_key, page_url)
        LOGGER.info("[jump-shop-login] stage=hcaptcha-ready tokenLength=%s email=%s", len(str(hcaptcha_token or "")), email)
        set_captcha_response(page, hcaptcha_token=hcaptcha_token)
        LOGGER.info("[jump-shop-login] stage=captcha-applied email=%s", email)
        clicked = maybe_click(page, ['form[action="/account/login"] button[type="submit"]', '#customer_login button[type="submit"]'])
        if not clicked:
            raise RuntimeError("未找到 Jump Shop 登录提交按钮")
        LOGGER.info("[jump-shop-login] stage=after-click url=%s email=%s", getattr(page, "url", ""), email)
        try:
            page.wait_for_load_state("domcontentloaded", timeout=90000)
        except Exception as exc:
            LOGGER.warning("[jump-shop-login] stage=wait-load-timeout url=%s error=%s email=%s", getattr(page, "url", ""), exc, email)
        page.wait_for_timeout(2500)
        current_url = page.url
        LOGGER.info("[jump-shop-login] stage=post-wait url=%s email=%s", current_url, email)
        if "/account/login" in current_url:
            text = compact_text(page.text_content("body") or "", 600)
            raise RuntimeError(f"Jump Shop 登录失败: {text or current_url}")
        login_context = export_browser_login_context(context)
        LOGGER.info("[jump-shop-login] stage=context-exported cookieCount=%s email=%s", len(login_context.get("cookies") or []), email)
        if return_browser:
            return login_context, browser, playwright_manager
        browser.close()
        playwright_manager.__exit__(None, None, None)
        return login_context, None, None
    except Exception:
        if return_browser:
            return None, browser, playwright_manager
        try:
            browser.close()
        finally:
            playwright_manager.__exit__(None, None, None)
        raise


def fill_shipping_address(page: Any, profile: Dict[str, Any]) -> None:
    maybe_fill(page, ['input[name="lastName"]', '#TextField268'], profile.get("lastName"))
    maybe_fill(page, ['input[name="firstName"]', '#TextField269'], profile.get("firstName"))
    maybe_fill(page, ['input[name="postalCode"]', '#postalCode'], str(profile.get("postalCode") or "").replace("-", ""))
    maybe_select(page, ['select[name="countryCode"]', 'select[name="country"]'], profile.get("countryCode", "JP"), ["日本", "Japan", "JP"])
    maybe_select(
        page,
        ['select[name="zone"]', 'select[name="province"]', 'select[name="address-level1"]'],
        profile.get("province", ""),
        [profile.get("province", ""), profile.get("zone", "")],
    )
    maybe_fill(page, ['input[name="city"]', '#TextField271'], profile.get("city"))
    maybe_fill(page, ['input[name="address1"]', '#TextField272'], profile.get("address1"))
    maybe_fill(page, ['input[name="address2"]', '#TextField273'], profile.get("address2"))
    maybe_fill(page, ['input[name="phone"]', '#TextField274'], profile.get("phone"))
    maybe_click(page, ['input[name="save_shipping_information"]'])


def fill_billing_address(page: Any, profile: Dict[str, Any]) -> None:
    if profile.get("billingSameAsShipping", True):
        return
    checkbox = page.locator('#billingAddressCheckbox')
    if checkbox.count() and checkbox.first.is_checked():
        checkbox.first.uncheck()
        page.wait_for_timeout(500)
    container = page.locator('#billingAddressForm')
    if not container.count():
        return
    maybe_fill(page, ['#billingAddressForm input[name="lastName"]', '#billingAddressForm #TextField14'], profile.get("billingLastName"))
    maybe_fill(page, ['#billingAddressForm input[name="firstName"]'], profile.get("billingFirstName"))
    maybe_fill(
        page,
        ['#billingAddressForm input[name="postalCode"]'],
        str(profile.get("billingPostalCode") or "").replace("-", ""),
    )
    maybe_select(
        page,
        ['#billingAddressForm select[name="countryCode"]', '#billingAddressForm select[name="country"]'],
        profile.get("billingCountryCode", profile.get("countryCode", "JP")),
        ["日本", "Japan", "JP"],
    )
    maybe_select(
        page,
        ['#billingAddressForm select[name="zone"]', '#billingAddressForm select[name="province"]', '#billingAddressForm select[name="address-level1"]'],
        profile.get("billingProvince", ""),
        [profile.get("billingProvince", "")],
    )
    maybe_fill(page, ['#billingAddressForm input[name="city"]'], profile.get("billingCity"))
    maybe_fill(page, ['#billingAddressForm input[name="address1"]'], profile.get("billingAddress1"))
    maybe_fill(page, ['#billingAddressForm input[name="address2"]'], profile.get("billingAddress2"))
    maybe_fill(page, ['#billingAddressForm input[name="phone"]'], profile.get("billingPhone"))


def fill_card_fields(page: Any, profile: Dict[str, Any]) -> None:
    frame_specs = [
        ("iframe[id^='card-fields-number']", profile.get("cardNumber")),
        ("iframe[id^='card-fields-expiry']", build_expiry_value(profile.get("expMonth"), profile.get("expYear"))),
        ("iframe[id^='card-fields-verification_value']", profile.get("cvv")),
        ("iframe[id^='card-fields-name']", profile.get("cardHolderName")),
        ("iframe[id^='card-fields-issue_date']", build_expiry_value(profile.get("issueMonth"), profile.get("issueYear"))),
        ("iframe[id^='card-fields-issue_number']", profile.get("issueNumber")),
    ]
    for selector, value in frame_specs:
        if not value:
            continue
        iframe = page.frame_locator(selector)
        try:
            iframe.locator("input").first.wait_for(timeout=15000)
            iframe.locator("input").first.fill(str(value))
        except Exception as exc:
            if selector.endswith("issue_date']") or selector.endswith("issue_number']"):
                continue
            raise RuntimeError(f"填写信用卡字段失败: {selector}: {exc}") from exc


def build_expiry_value(month: Any, year: Any) -> str:
    month_text = str(month or "").strip()
    year_text = str(year or "").strip()
    if not month_text or not year_text:
        return ""
    year_text = year_text[-2:] if len(year_text) >= 2 else year_text
    return f"{int(month_text):02d}/{year_text}"


def extract_order_number(text: str, url: str) -> str:
    patterns = [
        r"注文番号\s*#?\s*([A-Z0-9-]+)",
        r"Order\s*#\s*([A-Z0-9-]+)",
        r"order[_-]?id[=:]\s*([A-Z0-9-]+)",
    ]
    for pattern in patterns:
        match = re.search(pattern, text or "", flags=re.I)
        if match:
            return match.group(1)
    parsed = urlparse(url or "")
    if parsed.path:
        return parsed.path.rstrip("/").split("/")[-1]
    return ""


def detect_jump_shop_success(url: str, text: str) -> bool:
    lowered = (text or "").lower()
    return (
        "thank_you" in (url or "").lower()
        or "ご注文ありがとうございます" in text
        or "注文番号" in text
        or "thank you" in lowered
    )


def detect_jump_shop_3ds(url: str, text: str) -> bool:
    lowered = (text or "").lower()
    url_lower = (url or "").lower()
    keywords = ["3d secure", "3dセキュア", "3ds", "本人認証", "authentication required"]
    return any(keyword in lowered for keyword in keywords) or any(keyword in url_lower for keyword in ["3ds", "secure", "authenticate"])


def execute_jump_shop_purchase(payload: Dict[str, Any]) -> Dict[str, Any]:
    task_options = parse_json_maybe(payload.get("taskOptions"))
    login_context = parse_json_maybe(payload.get("loginReqData"))
    account_info = parse_json_maybe(payload.get("accountInfo"))
    product_url = normalize_jump_shop_product_url(
        str(payload.get("productUrl") or task_options.get("productUrl") or task_options.get("eventUrl") or "")
    )
    if not product_url:
        return {"success": False, "status": "failed", "message": "缺少 Jump Shop 商品链接"}
    variant_id = int(payload.get("variantId") or task_options.get("variantId") or 0)
    product_id = int(payload.get("productId") or task_options.get("productId") or 0)
    quantity = int(payload.get("purchaseQuantity") or task_options.get("quantity") or 1)
    section_id = str(payload.get("sectionId") or task_options.get("sectionId") or "")
    profile = payload.get("jumpShopProfile")
    if not isinstance(profile, dict) or not profile:
        return {"success": False, "status": "failed", "message": "缺少 Jump Shop 结算资料"}

    email = str(payload.get("email") or "")
    password = str(
        payload.get("password")
        or payload.get("platformPassword")
        or account_info.get("platformPassword")
        or account_info.get("password")
        or ""
    )

    if not login_context:
        runtime_login_context, _, _ = login_jump_shop(email, password, return_browser=False)
        login_context = runtime_login_context or {}

    from playwright.sync_api import TimeoutError as PlaywrightTimeoutError

    playwright_manager = launch_browser_context()
    playwright = playwright_manager.__enter__()
    browser = create_browser(playwright)
    context = browser.new_context(user_agent=DEFAULT_USER_AGENT, locale="ja-JP")
    page = context.new_page()
    try:
        add_cookies_from_login_context(context, login_context)
        page.goto(product_url, wait_until="domcontentloaded", timeout=120000)
        add_result = page.evaluate(
            """
            async ({ variantId, quantity, productId, sectionId }) => {
              const formData = new FormData();
              formData.append('form_type', 'product');
              formData.append('utf8', '✓');
              formData.append('quantity', String(quantity));
              formData.append('id', String(variantId));
              formData.append('product-id', String(productId));
              formData.append('section-id', String(sectionId));
              const response = await fetch('/cart/add.js', {
                method: 'POST',
                credentials: 'include',
                headers: { 'X-Requested-With': 'XMLHttpRequest' },
                body: formData
              });
              const text = await response.text();
              return { ok: response.ok, status: response.status, text };
            }
            """,
            {
                "variantId": variant_id,
                "quantity": quantity,
                "productId": product_id,
                "sectionId": section_id,
            },
        )
        if isinstance(add_result, dict) and not add_result.get("ok"):
            return {
                "success": False,
                "status": "failed",
                "executionStatus": "failed",
                "paymentStatus": "failed",
                "message": f"Jump Shop 加购失败: HTTP {add_result.get('status')}",
                "rawResult": add_result,
            }

        page.goto(JUMP_SHOP_CHECKOUT_URL, wait_until="domcontentloaded", timeout=120000)
        page.wait_for_timeout(2500)
        if "/account/login" in page.url:
            return {
                "success": False,
                "status": "failed",
                "executionStatus": "failed",
                "paymentStatus": "failed",
                "message": "Jump Shop checkout 跳回登录页，账号登录态失效",
            }

        fill_shipping_address(page, profile)
        fill_billing_address(page, profile)
        fill_card_fields(page, profile)

        page.locator("#checkout-pay-button").first.click()
        try:
            page.wait_for_load_state("domcontentloaded", timeout=120000)
        except PlaywrightTimeoutError:
            pass
        page.wait_for_timeout(6000)
        final_text = compact_text(page.text_content("body") or "", 4000)
        final_url = page.url
        if detect_jump_shop_3ds(final_url, final_text):
            return {
                "success": False,
                "status": "failed",
                "executionStatus": "failed",
                "paymentStatus": "failed",
                "message": "3DS_REQUIRED: Jump Shop 命中外部验证页",
                "errorCode": "3DS_REQUIRED",
                "rawResult": {"checkoutUrl": final_url, "pageText": final_text[:2000]},
            }
        if detect_jump_shop_success(final_url, final_text):
            order_no = extract_order_number(final_text, final_url)
            return {
                "success": True,
                "status": "paid",
                "executionStatus": "paid",
                "paymentStatus": "paid",
                "message": "Jump Shop 下单成功",
                "orderId": order_no,
                "orderNo": order_no,
                "loginReqData": export_browser_login_context(context),
                "rawResult": {
                    "orderNo": order_no,
                    "checkoutUrl": final_url,
                    "pageText": final_text[:2000],
                },
            }
        return {
            "success": False,
            "status": "failed",
            "executionStatus": "failed",
            "paymentStatus": "failed",
            "message": f"Jump Shop 下单失败: {final_text[:200] or final_url}",
            "rawResult": {"checkoutUrl": final_url, "pageText": final_text[:2000]},
        }
    except Exception as exc:
        return {
            "success": False,
            "status": "failed",
            "executionStatus": "failed",
            "paymentStatus": "failed",
            "message": f"Jump Shop 执行异常: {exc}",
        }
    finally:
        try:
            browser.close()
        finally:
            playwright_manager.__exit__(None, None, None)


class JumpShopExecutor:
    def execute(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        return execute_jump_shop_purchase(payload)


def register_one_account(
    backend: TicketBackendApi,
    *,
    batch_id: Any = None,
    index: Any = None,
    total: Any = None,
    platform_code: str = "",
) -> Dict[str, Any]:
    with log_context(flow="jump-shop-register", stage="allocate", batchId=batch_id, index=index, total=total, platformCode=platform_code):
        register_data = backend.next_register()
        if not register_data:
            raise RuntimeError("获取 Jump Shop 注册信息失败")
    initial_phone = str(register_data.get("phoneNumber") or "")
    identity = create_jump_shop_identity(initial_phone)
    email = str(register_data.get("email") or "")
    password = str(register_data.get("password") or "")
    account_id = register_data.get("accountId")
    hcaptcha_solver = build_jump_shop_hcaptcha_verifier()
    hcaptcha_provider = getattr(hcaptcha_solver, "provider_name", "captcha")

    playwright_manager = launch_browser_context()
    playwright = playwright_manager.__enter__()
    browser = create_browser(playwright)
    context = browser.new_context(user_agent=DEFAULT_USER_AGENT, locale="ja-JP")
    page = context.new_page()
    customer_fields_network_events: List[Dict[str, Any]] = []
    attach_customer_fields_network_debug(page, customer_fields_network_events)
    try:
        page.goto(JUMP_SHOP_REGISTER_URL, wait_until="domcontentloaded", timeout=120000)
        for attempt in range(1, MAX_REGISTER_PHONE_RETRIES + 1):
            if attempt > 1:
                identity = create_jump_shop_identity()
                page.goto(JUMP_SHOP_REGISTER_URL, wait_until="domcontentloaded", timeout=120000)
            LOGGER.info(
                "[jump-shop-register] stage=page-ready attempt=%s/%s url=%s email=%s phone=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                page.url,
                email,
                identity.phone,
            )
            register_html, recaptcha_config = load_register_captcha_context(page)
            LOGGER.info(
                "[jump-shop-register] stage=captcha-context attempt=%s/%s recaptchaSiteKey=%s enterprise=%s invisible=%s action=%s state=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                bool(recaptcha_config.site_key),
                recaptcha_config.enterprise,
                recaptcha_config.invisible,
                recaptcha_config.page_action or "-",
                json.dumps(collect_register_debug_state(page), ensure_ascii=False),
            )
            LOGGER.info(
                "[jump-shop-register] stage=solve-hcaptcha attempt=%s/%s provider=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                hcaptcha_provider,
            )
            hcaptcha_token = hcaptcha_solver.solve_hcaptcha(HCAPTCHA_SITE_KEY, JUMP_SHOP_REGISTER_URL)
            LOGGER.info(
                "[jump-shop-register] stage=hcaptcha-ready attempt=%s/%s tokenLength=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                len(str(hcaptcha_token or "")),
            )
            recaptcha_token = ""
            if recaptcha_config.site_key:
                if not capsolver_api_key():
                    raise RuntimeError("Jump Shop 注册检测到 reCAPTCHA，但缺少可用的 CAPSOLVER_API_KEY")
                LOGGER.info(
                    "[jump-shop-register] detected %s reCAPTCHA sitekey, solving as additional gate invisible=%s action=%s",
                    "enterprise" if recaptcha_config.enterprise else "standard",
                    recaptcha_config.invisible,
                    recaptcha_config.page_action or "-",
                )
                recaptcha_solver = CaptchaSolver(api_key=capsolver_api_key())
                if recaptcha_config.enterprise:
                    recaptcha_token = recaptcha_solver.solve_recaptcha_v3_enterprise(
                        recaptcha_config.site_key,
                        JUMP_SHOP_REGISTER_URL,
                        page_action="SUBMIT",
                    )
                    apply_customer_fields_recaptcha_token(page, recaptcha_token)
                else:
                    recaptcha_token = recaptcha_solver.solve_recaptcha_v2(
                        recaptcha_config.site_key,
                        JUMP_SHOP_REGISTER_URL,
                        enterprise=False,
                        action=recaptcha_config.page_action or None,
                        is_invisible=recaptcha_config.invisible,
                        recaptcha_data_s_value=recaptcha_config.recaptcha_data_s_value or None,
                    )
                LOGGER.info(
                    "[jump-shop-register] stage=recaptcha-ready attempt=%s/%s tokenLength=%s",
                    attempt,
                    MAX_REGISTER_PHONE_RETRIES,
                    len(str(recaptcha_token or "")),
                )
            elif "g-recaptcha-response" in register_html:
                LOGGER.info("[jump-shop-register] found g-recaptcha-response field without explicit sitekey; continue with hCaptcha only")
            fill_results = {
                "lastName": maybe_fill(page, ['input[name="last_name"]'], identity.last_name),
                "firstName": maybe_fill(page, ['input[name="first_name"]'], identity.first_name),
                "emailVisible": maybe_fill(page, ['input[name="email"]'], email),
                "emailHidden": set_field_value(page, ['input[name="customer[email]"]', 'input[name="email"]'], email),
                "passwordHidden": set_field_value(page, ['input[name="customer[password]"]'], password),
                "zip": maybe_fill(page, ['input[name="default_address.zip"]'], identity.postal_code),
                "province": maybe_select(page, ['select[name="default_address.province"]'], identity.province, [identity.province]),
                "city": maybe_fill(page, ['input[name="default_address.city"]'], identity.city),
                "address1": maybe_fill(page, ['input[name="default_address.address1"]'], identity.address1),
                "address2": maybe_fill(page, ['input[name="default_address.address2"]'], identity.address2),
                "phone": maybe_fill(page, ['input[name="phone"]'], identity.phone),
                "gender": maybe_click_radio(page, 'input[name="gender"]', "男性" if identity.gender == "male" else "女性"),
                "terms": maybe_click(page, ['input[name="new_single_checkbox_field"]']),
            }
            LOGGER.info(
                "[jump-shop-register] stage=form-filled attempt=%s/%s results=%s state=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                json.dumps(fill_results, ensure_ascii=False),
                json.dumps(collect_register_debug_state(page), ensure_ascii=False),
            )
            set_captcha_response(page, hcaptcha_token=hcaptcha_token, recaptcha_token=recaptcha_token)
            page.wait_for_timeout(500)
            LOGGER.info(
                "[jump-shop-register] stage=captcha-applied attempt=%s/%s state=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                json.dumps(collect_register_debug_state(page), ensure_ascii=False),
            )
            customer_fields_network_events.clear()
            clicked = maybe_click(page, ['form[action="/account"] button[type="submit"]', '#create_customer button[type="submit"]'])
            if not clicked:
                raise RuntimeError("未找到 Jump Shop 注册提交按钮")
            LOGGER.info("[jump-shop-register] submitted register form: attempt=%s/%s, email=%s, phone=%s", attempt, MAX_REGISTER_PHONE_RETRIES, email, identity.phone)
            LOGGER.info(
                "[jump-shop-register] stage=after-click attempt=%s/%s state=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                json.dumps(collect_register_debug_state(page), ensure_ascii=False),
            )
            try:
                page.wait_for_load_state("domcontentloaded", timeout=120000)
            except Exception as exc:
                LOGGER.error(
                    "[jump-shop-register] stage=wait-load-timeout attempt=%s/%s url=%s error=%s state=%s",
                    attempt,
                    MAX_REGISTER_PHONE_RETRIES,
                    page.url,
                    exc,
                    json.dumps(collect_register_debug_state(page), ensure_ascii=False),
                )
                LOGGER.info(
                    "[jump-shop-register] stage=submit-network attempt=%s/%s events=%s",
                    attempt,
                    MAX_REGISTER_PHONE_RETRIES,
                    json.dumps(customer_fields_network_events, ensure_ascii=False),
                )
                raise
            page.wait_for_timeout(3000)
            LOGGER.info(
                "[jump-shop-register] stage=submit-network attempt=%s/%s events=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                json.dumps(customer_fields_network_events, ensure_ascii=False),
            )
            LOGGER.info(
                "[jump-shop-register] stage=post-wait attempt=%s/%s url=%s state=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                page.url,
                json.dumps(collect_register_debug_state(page), ensure_ascii=False),
            )
            if "/account/register" not in page.url:
                LOGGER.info("[jump-shop-register] register form left register page: url=%s", page.url)
                break
            text = compact_text(page.text_content("body") or "", 600)
            LOGGER.info(
                "[jump-shop-register] stage=still-on-register attempt=%s/%s body=%s",
                attempt,
                MAX_REGISTER_PHONE_RETRIES,
                text,
            )
            if attempt < MAX_REGISTER_PHONE_RETRIES and is_jump_shop_phone_conflict(text):
                LOGGER.warning(
                    f"[jump-shop-register] phone conflict, retrying with new phone: "
                    f"attempt={attempt}/{MAX_REGISTER_PHONE_RETRIES}, phone={identity.phone}, body={text}"
                )
                continue
            return {
                "accountId": account_id,
                "email": email,
                "success": False,
                "status": "failed",
                "message": f"Jump Shop 注册失败: {text or page.url}",
            }
        return {
            "accountId": account_id,
            "email": email,
            "success": True,
            "status": "success",
            "message": "Jump Shop 注册成功",
            "accountStatus": "registered",
            "loginStatus": "offline",
            "accountInfo": {
                "familyName": identity.last_name,
                "givenName": identity.first_name,
                "fullName": identity.last_name + identity.first_name,
                "platformPassword": password,
                "customerId": extract_order_number(compact_text(page.text_content("body") or "", 1200), page.url),
            },
            "reqData": identity.to_req_data(),
        }
    finally:
        try:
            browser.close()
        finally:
            playwright_manager.__exit__(None, None, None)


def run_register_batch(
    batch_id: Any,
    platform_code: str,
    count: int,
    backend_base_url: str,
    result_stream: str = REGISTER_RESULT_STREAM,
) -> None:
    LOGGER.info(
        "[jump-shop-register] stage=batch-start batchId=%s platformCode=%s count=%s backendBaseUrl=%s resultStream=%s",
        batch_id,
        platform_code,
        count,
        backend_base_url or "-",
        result_stream,
    )
    backend = TicketBackendApi(backend_base_url, platform_code)
    publisher = RedisEventPublisher()
    total = max(int(count or 0), 0)
    success_count = 0
    failed_count = 0
    for index in range(1, total + 1):
        try:
            LOGGER.info(
                "[jump-shop-register] stage=account-start batchId=%s index=%s/%s platformCode=%s",
                batch_id,
                index,
                total,
                platform_code,
            )
            result = register_one_account(backend, batch_id=batch_id, index=index, total=total, platform_code=platform_code)
        except Exception as exc:
            LOGGER.error(
                "[jump-shop-register] stage=account-error batchId=%s index=%s/%s platformCode=%s error=%s",
                batch_id,
                index,
                total,
                platform_code,
                exc,
            )
            result = {"success": False, "status": "failed", "message": f"Jump Shop 注册异常：{exc}"}
        if result.get("success"):
            success_count += 1
        else:
            failed_count += 1
        result.update(
            {
                "eventType": "account_result",
                "batchId": batch_id,
                "platformCode": platform_code,
                "index": index,
                "totalCount": total,
                "successCount": success_count,
                "failedCount": failed_count,
            }
        )
        publisher.publish(result_stream, result)
        LOGGER.info(
            "[jump-shop-register] stage=account-result batchId=%s index=%s/%s success=%s successCount=%s failedCount=%s",
            batch_id,
            index,
            total,
            bool(result.get("success")),
            success_count,
            failed_count,
        )
    publisher.publish(
        result_stream,
        {
            "eventType": "batch_completed",
            "batchId": batch_id,
            "platformCode": platform_code,
            "totalCount": total,
            "successCount": success_count,
            "failedCount": failed_count,
            "status": "partial" if failed_count else "completed",
            "message": "Jump Shop 注册批次完成",
        },
    )
    LOGGER.info(
        "[jump-shop-register] stage=batch-completed batchId=%s platformCode=%s total=%s successCount=%s failedCount=%s",
        batch_id,
        platform_code,
        total,
        success_count,
        failed_count,
    )


def execute_login_for_account(
    account: Dict[str, Any],
    *,
    batch_id: Any = None,
    index: Any = None,
    total: Any = None,
    platform_code: str = "",
) -> Dict[str, Any]:
    account_info = parse_json_maybe(account.get("accountInfo"))
    email = str(account.get("email") or "")
    password = str(
        account.get("platformPassword")
        or account_info.get("platformPassword")
        or account.get("password")
        or ""
    )
    if not email or not password:
        return {
            "accountId": account.get("accountId"),
            "email": email,
            "success": False,
            "status": "failed",
            "message": "Jump Shop 登录缺少邮箱或平台密码",
        }
    LOGGER.info(
        "[jump-shop-login] stage=account-start batchId=%s index=%s/%s platformCode=%s accountId=%s email=%s",
        batch_id,
        index,
        total,
        platform_code,
        account.get("accountId"),
        email,
    )
    login_context, _, _ = login_jump_shop(email, password, return_browser=False)
    if not login_context:
        return {
            "accountId": account.get("accountId"),
            "email": email,
            "success": False,
            "status": "failed",
            "message": "Jump Shop 登录失败",
        }
    return {
        "accountId": account.get("accountId"),
        "email": email,
        "success": True,
        "status": "success",
        "message": "Jump Shop 登录成功",
        "accountStatus": "registered",
        "loginStatus": "logged_in",
        "loginReqData": login_context,
    }


def run_login_batch(
    batch_id: Any,
    platform_code: str,
    accounts: List[Dict[str, Any]],
    backend_base_url: str,
    result_stream: str = LOGIN_RESULT_STREAM,
) -> None:
    total = len(accounts or [])
    LOGGER.info(
        "[jump-shop-login] stage=batch-start batchId=%s platformCode=%s count=%s backendBaseUrl=%s resultStream=%s",
        batch_id,
        platform_code,
        total,
        backend_base_url,
        result_stream,
    )
    publisher = RedisEventPublisher()
    success_count = 0
    failed_count = 0
    for index, account in enumerate(accounts or [], start=1):
        try:
            result = execute_login_for_account(account, batch_id=batch_id, index=index, total=total, platform_code=platform_code)
        except Exception as exc:
            result = {
                "accountId": account.get("accountId"),
                "email": account.get("email"),
                "success": False,
                "status": "failed",
                "message": f"Jump Shop 登录异常：{exc}",
            }
        if result.get("success"):
            success_count += 1
        else:
            failed_count += 1
        LOGGER.info(
            "[jump-shop-login] stage=account-result batchId=%s index=%s/%s success=%s successCount=%s failedCount=%s",
            batch_id,
            index,
            total,
            bool(result.get("success")),
            success_count,
            failed_count,
        )
        result.update(
            {
                "eventType": "account_result",
                "batchId": batch_id,
                "platformCode": platform_code,
                "index": index,
                "totalCount": total,
                "successCount": success_count,
                "failedCount": failed_count,
            }
        )
        publisher.publish(result_stream, result)
    LOGGER.info(
        "[jump-shop-login] stage=batch-completed batchId=%s platformCode=%s total=%s successCount=%s failedCount=%s",
        batch_id,
        platform_code,
        total,
        success_count,
        failed_count,
    )
    publisher.publish(
        result_stream,
        {
            "eventType": "batch_completed",
            "batchId": batch_id,
            "platformCode": platform_code,
            "totalCount": total,
            "successCount": success_count,
            "failedCount": failed_count,
            "status": "partial" if failed_count else "completed",
            "message": "Jump Shop 登录批次完成",
        },
    )
