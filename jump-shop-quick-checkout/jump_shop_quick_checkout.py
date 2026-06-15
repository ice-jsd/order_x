"""
Jump Shop quick checkout runner.

Reads one cookie string per CSV row, then runs cart-add -> checkout -> address/card
fill -> payment submit directly with Playwright. This script is intentionally
standalone and does not use the backend batch task flow.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import os
import random
import re
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple
from urllib.parse import urlparse

import requests


JUMP_SHOP_BASE_URL = "https://jumpshop-benelic.com"
JUMP_SHOP_CHECKOUT_URL = f"{JUMP_SHOP_BASE_URL}/checkout"
DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
    "AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/148.0.0.0 Safari/537.36"
)
BLOCKED_RESOURCE_TYPES = {"image", "media", "font"}
BLOCKED_URL_KEYWORDS = (
    "googletagmanager.com",
    "google-analytics.com",
    "doubleclick.net",
    "facebook.net",
    "facebook.com/tr",
    "clarity.ms",
    "hotjar.com",
)
RESULT_FIELDS = ["row", "email", "success", "status", "message", "orderNo", "finalUrl", "screenshot"]
PRINT_LOCK = threading.Lock()


@dataclass(frozen=True)
class AccountCookie:
    row: int
    cookie_header: str
    email: str = ""


@dataclass(frozen=True)
class ProductConfig:
    product_url: str
    variant_id: int
    product_id: int
    section_id: str
    quantity: int
    title: str = ""


def log(message: str) -> None:
    with PRINT_LOCK:
        print(f"[{datetime.now().strftime('%H:%M:%S')}] {message}", flush=True)


def compact_text(value: Any, limit: int = 240) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:limit]


def visible_text(page_text: str) -> str:
    text = re.sub(r"<(script|style)\b.*?</\1>", " ", page_text or "", flags=re.I | re.S)
    text = re.sub(r"<br\s*/?>", "\n", text, flags=re.I)
    text = re.sub(r"</(p|div|li|dt|dd|tr|h\d)>", "\n", text, flags=re.I)
    text = re.sub(r"<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    return re.sub(r"\n+", "\n", text).strip()


def rendered_page_text(page: Any, limit: int = 4000) -> str:
    try:
        return compact_text(page.locator("body").inner_text(timeout=1000), limit)
    except Exception:
        return compact_text(visible_text(page.content()), limit)


def extract_page_title(page_text: str) -> str:
    match = re.search(r"<title[^>]*>(.*?)</title>", page_text or "", flags=re.I | re.S)
    return compact_text(visible_text(match.group(1)), 300) if match else ""


def normalize_jump_shop_product_url(product_url: str) -> str:
    parsed = urlparse(str(product_url or "").strip())
    if not parsed.scheme or not parsed.netloc:
        raise ValueError("商品链接不合法")
    return f"{parsed.scheme}://{parsed.netloc}{parsed.path}"


def request_headers() -> Dict[str, str]:
    return {
        "User-Agent": DEFAULT_USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "ja,en-US;q=0.9,en;q=0.8,zh-CN;q=0.7",
    }


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
        r'"variantId"\s*:\s*"?(\d+)"?',
    ]
    for pattern in patterns:
        match = re.search(pattern, page_text or "", flags=re.I | re.S)
        if match:
            return int(match.group(1))
    return None


def fetch_jump_shop_product_info(product_url: str) -> Dict[str, Any]:
    normalized_url = normalize_jump_shop_product_url(product_url)
    response = requests.get(normalized_url, headers=request_headers(), timeout=60)
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
    parsed = {
        "productUrl": normalized_url,
        "title": compact_text(title, 300),
        "variantId": int(variant_id) if variant_id and variant_id.isdigit() else extract_variant_id_from_json(page_text),
        "productId": int(product_id) if product_id and product_id.isdigit() else None,
        "sectionId": section_id or "",
    }
    if not parsed["variantId"] or not parsed["productId"] or not parsed["sectionId"]:
        raise RuntimeError("商品页缺少必要的 Shopify 参数（variantId/productId/sectionId）")
    return parsed


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


def launch_browser_context():
    try:
        from playwright.sync_api import sync_playwright
    except Exception as exc:
        raise RuntimeError(f"Playwright 不可用: {exc}") from exc
    return sync_playwright()


def maybe_click(page: Any, selectors: Iterable[str], timeout_ms: int = 5000) -> bool:
    for selector in selectors:
        locator = page.locator(selector)
        if locator.count():
            locator.first.click(timeout=timeout_ms)
            return True
    return False


def maybe_fill(page: Any, selectors: Iterable[str], value: Any, frame: Any = None, timeout_ms: int = 5000) -> bool:
    if value is None:
        return False
    target = frame or page
    for selector in selectors:
        locator = target.locator(selector)
        if locator.count():
            locator.first.fill(str(value), timeout=timeout_ms)
            return True
    return False


def maybe_select(page: Any, selectors: Iterable[str], value: Any, labels: Optional[List[str]] = None, frame: Any = None) -> bool:
    target = frame or page
    labels = labels or []
    for selector in selectors:
        locator = target.locator(selector)
        if not locator.count():
            continue
        try:
            locator.first.select_option(value=str(value))
            return True
        except Exception:
            pass
        for label in labels:
            if not label:
                continue
            try:
                locator.first.select_option(label=str(label))
                return True
            except Exception:
                continue
    return False


def locator_count(locator: Any) -> int:
    try:
        return int(locator.count())
    except Exception:
        return 0


def locator_at(locator: Any, index: int) -> Any:
    try:
        return locator.nth(index)
    except Exception:
        return locator.first


def checkout_field_diagnostics(page: Any, limit: int = 50) -> str:
    try:
        fields = page.evaluate(
            """
            (limit) => Array.from(document.querySelectorAll('input, select, textarea'))
              .slice(0, limit)
              .map((el) => ({
                tag: el.tagName.toLowerCase(),
                type: el.getAttribute('type') || '',
                name: el.getAttribute('name') || '',
                id: el.id || '',
                autocomplete: el.getAttribute('autocomplete') || '',
                placeholder: el.getAttribute('placeholder') || '',
                aria: el.getAttribute('aria-label') || '',
                hidden: !!(el.offsetParent === null || el.hidden),
                disabled: !!el.disabled
              }))
            """,
            limit,
        )
    except Exception:
        return ""
    if not isinstance(fields, list):
        return ""
    parts = []
    for field in fields:
        if not isinstance(field, dict):
            continue
        bits = [
            str(field.get("tag") or ""),
            f"type={field.get('type') or '-'}",
            f"name={field.get('name') or '-'}",
            f"id={field.get('id') or '-'}",
            f"autocomplete={field.get('autocomplete') or '-'}",
            f"placeholder={field.get('placeholder') or '-'}",
            f"aria={field.get('aria') or '-'}",
            f"hidden={field.get('hidden')}",
            f"disabled={field.get('disabled')}",
        ]
        parts.append("{" + " ".join(bits) + "}")
    return compact_text("; ".join(parts), 1200)


def candidate_field_locators(target: Any, selectors: Iterable[str], labels: Iterable[str]) -> Iterable[Tuple[str, Any]]:
    for selector in selectors:
        yield f"selector={selector}", target.locator(selector)
    for label in labels:
        if not label:
            continue
        try:
            yield f"labelExact={label}", target.get_by_label(label, exact=True)
        except Exception:
            pass
        try:
            yield f"label={label}", target.get_by_label(label)
        except Exception:
            pass


def dispatch_field_events(field: Any) -> None:
    try:
        field.evaluate(
            """(el) => {
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
              el.blur();
            }"""
        )
    except Exception:
        pass


def fill_required_text_field(
    page: Any,
    field_name: str,
    selectors: Iterable[str],
    value: Any,
    labels: Optional[Iterable[str]] = None,
    *,
    required: bool = True,
    timeout_ms: int = 5000,
    normalize_value=None,
) -> bool:
    if value is None or (required and not string_value(value)):
        if required:
            raise RuntimeError(f"checkout 字段缺少配置值: {field_name}")
        return False

    text_value = str(value)
    labels = labels or []
    for locator_name, locator in candidate_field_locators(page, selectors, labels):
        count = locator_count(locator)
        for index in range(count):
            field = locator_at(locator, index)
            try:
                field.fill(text_value, timeout=timeout_ms)
                dispatch_field_events(field)
                try:
                    actual = str(field.input_value(timeout=300) or "")
                except Exception:
                    actual = text_value
                expected_cmp = normalize_value(text_value) if normalize_value else text_value
                actual_cmp = normalize_value(actual) if normalize_value else actual
                if not required or actual_cmp:
                    return True
            except Exception:
                continue

    if required:
        diagnostics = checkout_field_diagnostics(page)
        raise RuntimeError(
            f"未找到或无法填写 checkout 字段: {field_name}; "
            f"selectors={list(selectors)} labels={list(labels)} "
            f"diagnostics={diagnostics or '-'}"
        )
    return False


def select_or_fill_checkout_field(
    page: Any,
    field_name: str,
    selectors: Iterable[str],
    value: Any,
    option_labels: Optional[Iterable[str]] = None,
    labels: Optional[Iterable[str]] = None,
    *,
    required: bool = True,
    timeout_ms: int = 5000,
) -> bool:
    if value is None or (required and not string_value(value)):
        if required:
            raise RuntimeError(f"checkout 字段缺少配置值: {field_name}")
        return False

    labels = labels or []
    option_values = [str(value)]
    option_values.extend(str(label) for label in (option_labels or []) if string_value(label))
    for locator_name, locator in candidate_field_locators(page, selectors, labels):
        count = locator_count(locator)
        for index in range(count):
            field = locator_at(locator, index)
            for option in option_values:
                try:
                    field.select_option(value=option, timeout=timeout_ms)
                    dispatch_field_events(field)
                    return True
                except Exception:
                    pass
                try:
                    field.select_option(label=option, timeout=timeout_ms)
                    dispatch_field_events(field)
                    return True
                except Exception:
                    pass
            try:
                field.fill(str(value), timeout=timeout_ms)
                dispatch_field_events(field)
                return True
            except Exception:
                continue

    if required:
        diagnostics = checkout_field_diagnostics(page)
        raise RuntimeError(
            f"未找到或无法选择 checkout 字段: {field_name}; "
            f"selectors={list(selectors)} labels={list(labels)} "
            f"diagnostics={diagnostics or '-'}"
        )
    return False


def set_checkbox_checked(locator: Any, checked: bool, timeout_ms: int = 5000) -> bool:
    if not locator.count():
        return False
    target = locator.first
    try:
        if bool(target.is_checked()) != checked:
            if checked:
                target.check(timeout=timeout_ms, force=True)
            else:
                target.uncheck(timeout=timeout_ms, force=True)
        return True
    except Exception:
        try:
            if bool(target.is_checked()) != checked:
                target.click(timeout=timeout_ms, force=True)
            return bool(target.is_checked()) == checked
        except Exception:
            return False


def set_choice_by_label(page: Any, labels: Iterable[str], checked: bool = True, timeout_ms: int = 5000) -> bool:
    for label in labels:
        if not label:
            continue
        try:
            if set_checkbox_checked(page.get_by_label(label, exact=True), checked, timeout_ms=timeout_ms):
                return True
        except Exception:
            pass
        try:
            if set_checkbox_checked(page.get_by_label(label), checked, timeout_ms=timeout_ms):
                return True
        except Exception:
            pass
        try:
            locator = page.get_by_text(label, exact=True)
            if locator.count():
                locator.first.click(timeout=timeout_ms, force=True)
                return True
        except Exception:
            continue
    return False


def set_billing_same_as_shipping(page: Any, checked: bool) -> bool:
    selectors = [
        "#billingAddressCheckbox",
        "input[type='checkbox'][name='billingAddress']",
        "input[type='checkbox'][name='billing_address_selector']",
        "input[type='checkbox'][aria-label*='請求先住所']",
        "input[type='checkbox'][aria-label*='billing']",
    ]
    for selector in selectors:
        if set_checkbox_checked(page.locator(selector), checked):
            return True

    labels = [
        "お届け先住所を請求先住所として使用する",
        "配送先住所を請求先住所として使用する",
        "Use shipping address as billing address",
        "Same as shipping address",
    ]
    for label in labels:
        try:
            if set_checkbox_checked(page.get_by_label(label, exact=True), checked):
                return True
        except Exception:
            continue
    return False


def normalize_payment_method(value: Any) -> str:
    text = string_value(value).lower().replace("_", "-")
    if text in {"cod", "cash", "cash-on-delivery", "daibiki", "代引", "代金引換"}:
        return "cod"
    return "card"


def select_cod_payment(page: Any) -> None:
    if not set_choice_by_label(page, ["代金引換", "Cash on Delivery", "Cash on delivery", "代引き"]):
        raise RuntimeError("未找到或无法选择代金引換")
    if not set_choice_by_label(page, ["お届け先住所と同じ", "配送先住所と同じ", "Same as shipping address"]):
        raise RuntimeError("未找到或无法选择ご依頼主情報：お届け先住所と同じ")


def fill_shipping_address(page: Any, profile: Dict[str, Any]) -> None:
    fill_required_text_field(
        page,
        "shipping.lastName",
        [
            'input[name="lastName"]',
            'input[name$="[lastName]"]',
            'input[autocomplete="family-name"]',
            'input[aria-label*="姓"]',
            "#TextField268",
        ],
        profile.get("lastName"),
        labels=["姓", "苗字", "Last name", "Family name"],
    )
    fill_required_text_field(
        page,
        "shipping.firstName",
        [
            'input[name="firstName"]',
            'input[name$="[firstName]"]',
            'input[autocomplete="given-name"]',
            'input[aria-label*="名"]',
            "#TextField269",
        ],
        profile.get("firstName"),
        labels=["名", "名前", "First name", "Given name"],
    )
    fill_required_text_field(
        page,
        "shipping.postalCode",
        [
            'input[name="postalCode"]',
            'input[name$="[postalCode]"]',
            'input[name="zip"]',
            'input[name$="[zip]"]',
            'input[autocomplete="postal-code"]',
            "#postalCode",
            "#TextField270",
        ],
        str(profile.get("postalCode") or "").replace("-", ""),
        labels=["郵便番号", "Postal code", "ZIP"],
        normalize_value=digits_only,
    )
    select_or_fill_checkout_field(
        page,
        "shipping.countryCode",
        [
            'select[name="countryCode"]',
            'select[name$="[countryCode]"]',
            'select[name="country"]',
            'select[autocomplete="country"]',
            'input[name="countryCode"]',
            'input[autocomplete="country"]',
        ],
        profile.get("countryCode", "JP"),
        option_labels=["日本", "Japan", "JP"],
        labels=["国", "Country/region", "Country"],
        required=False,
    )
    select_or_fill_checkout_field(
        page,
        "shipping.province",
        [
            'select[name="zone"]',
            'select[name="province"]',
            'select[name$="[province]"]',
            'select[name="address-level1"]',
            'select[autocomplete="address-level1"]',
            'input[name="zone"]',
            'input[name="province"]',
            'input[autocomplete="address-level1"]',
        ],
        profile.get("province", ""),
        option_labels=[profile.get("province", ""), profile.get("zone", "")],
        labels=["都道府県", "県", "Province", "Prefecture"],
    )
    fill_required_text_field(
        page,
        "shipping.city",
        [
            'input[name="city"]',
            'input[name$="[city]"]',
            'input[autocomplete="address-level2"]',
            "#TextField271",
        ],
        profile.get("city"),
        labels=["市区町村", "City", "区市町村"],
    )
    fill_required_text_field(
        page,
        "shipping.address1",
        [
            'input[name="address1"]',
            'input[name$="[address1]"]',
            'input[autocomplete="address-line1"]',
            "#TextField272",
        ],
        profile.get("address1"),
        labels=["住所", "番地", "Address", "Address line 1"],
    )
    fill_required_text_field(
        page,
        "shipping.address2",
        [
            'input[name="address2"]',
            'input[name$="[address2]"]',
            'input[autocomplete="address-line2"]',
            "#TextField273",
        ],
        profile.get("address2"),
        labels=["建物名", "部屋番号", "Address line 2", "Apartment"],
        required=False,
    )
    fill_required_text_field(
        page,
        "shipping.phone",
        [
            "input[name='phone']",
            "input[name$='[phone]']",
            "input[type='tel']",
            "input[autocomplete='tel']",
            "#TextField274",
        ],
        digits_only(profile.get("phone")),
        labels=["電話番号", "Phone", "Phone number"],
        normalize_value=digits_only,
    )
    maybe_click(page, ['input[name="save_shipping_information"]'])


def fill_shipping_phone(page: Any, phone: Any) -> bool:
    phone_value = digits_only(phone)
    if not phone_value:
        return False
    filled = False
    selectors = ["input[name='phone']", "input[type='tel']", "input[autocomplete='tel']", "#TextField274"]
    for selector in selectors:
        locator = page.locator(selector)
        try:
            count = locator.count()
        except Exception:
            continue
        for index in range(count):
            field = locator.nth(index)
            try:
                field.fill(phone_value, timeout=1000)
                field.evaluate(
                    """(el) => {
                      el.dispatchEvent(new Event('input', { bubbles: true }));
                      el.dispatchEvent(new Event('change', { bubbles: true }));
                      el.blur();
                    }"""
                )
                filled = True
            except Exception:
                continue
    return filled


def fill_billing_address(page: Any, profile: Dict[str, Any]) -> None:
    if profile.get("billingSameAsShipping", True):
        if not set_billing_same_as_shipping(page, True):
            raise RuntimeError("未找到或无法勾选“お届け先住所を請求先住所として使用する”")
        return
    if set_billing_same_as_shipping(page, False):
        page.wait_for_timeout(500)
    container = page.locator("#billingAddressForm")
    if not container.count():
        raise RuntimeError(f"未找到 billing address 表单; diagnostics={checkout_field_diagnostics(page) or '-'}")
    fill_required_text_field(
        page,
        "billing.lastName",
        ['#billingAddressForm input[name="lastName"]', '#billingAddressForm input[autocomplete="family-name"]', "#billingAddressForm #TextField14"],
        profile.get("billingLastName"),
        labels=[],
    )
    fill_required_text_field(
        page,
        "billing.firstName",
        ['#billingAddressForm input[name="firstName"]', '#billingAddressForm input[autocomplete="given-name"]'],
        profile.get("billingFirstName"),
        labels=[],
    )
    fill_required_text_field(
        page,
        "billing.postalCode",
        ['#billingAddressForm input[name="postalCode"]', '#billingAddressForm input[autocomplete="postal-code"]'],
        str(profile.get("billingPostalCode") or "").replace("-", ""),
        labels=[],
        normalize_value=digits_only,
    )
    select_or_fill_checkout_field(
        page,
        "billing.countryCode",
        ['#billingAddressForm select[name="countryCode"]', '#billingAddressForm select[name="country"]'],
        profile.get("billingCountryCode", profile.get("countryCode", "JP")),
        option_labels=["日本", "Japan", "JP"],
        labels=[],
        required=False,
    )
    select_or_fill_checkout_field(
        page,
        "billing.province",
        [
            '#billingAddressForm select[name="zone"]',
            '#billingAddressForm select[name="province"]',
            '#billingAddressForm select[name="address-level1"]',
            '#billingAddressForm input[autocomplete="address-level1"]',
        ],
        profile.get("billingProvince", ""),
        option_labels=[profile.get("billingProvince", "")],
        labels=[],
    )
    fill_required_text_field(page, "billing.city", ['#billingAddressForm input[name="city"]', '#billingAddressForm input[autocomplete="address-level2"]'], profile.get("billingCity"), labels=[])
    fill_required_text_field(page, "billing.address1", ['#billingAddressForm input[name="address1"]', '#billingAddressForm input[autocomplete="address-line1"]'], profile.get("billingAddress1"), labels=[])
    fill_required_text_field(page, "billing.address2", ['#billingAddressForm input[name="address2"]', '#billingAddressForm input[autocomplete="address-line2"]'], profile.get("billingAddress2"), labels=[], required=False)
    fill_required_text_field(
        page,
        "billing.phone",
        ['#billingAddressForm input[name="phone"]', '#billingAddressForm input[type="tel"]', '#billingAddressForm input[autocomplete="tel"]'],
        digits_only(profile.get("billingPhone")),
        labels=[],
        normalize_value=digits_only,
    )


def build_expiry_value(month: Any, year: Any) -> str:
    month_text = str(month or "").strip()
    year_text = str(year or "").strip()
    if not month_text or not year_text:
        return ""
    year_text = year_text[-2:] if len(year_text) >= 2 else year_text
    return f"{int(month_text):02d}{year_text}"


def digits_only(value: Any) -> str:
    return re.sub(r"\D+", "", str(value or ""))


CARD_FRAME_POSITION = {"number": 0, "expiry": 1, "cvv": 2, "name": 3}


def card_iframe_locator(page: Any, iframe_ref: Any) -> Any:
    if isinstance(iframe_ref, str):
        return page.locator(iframe_ref).first
    return iframe_ref


def maybe_card_inner_input(page: Any, iframe_ref: Any) -> Any:
    try:
        iframe = card_iframe_locator(page, iframe_ref)
        handle = iframe.element_handle(timeout=700)
        if handle is None:
            return None
        frame = handle.content_frame()
        if frame is None:
            return None
        for input_selector in ["input:not([type='hidden'])", "input"]:
            field = frame.locator(input_selector).first
            if field.count():
                return field
        return None
    except Exception:
        return None


def card_inner_value(page: Any, iframe_ref: Any) -> str:
    field = maybe_card_inner_input(page, iframe_ref)
    if field is None:
        return ""
    try:
        return str(field.input_value(timeout=150) or "")
    except Exception:
        return ""


def type_card_inner(page: Any, iframe_ref: Any, value: str) -> str:
    inner = maybe_card_inner_input(page, iframe_ref)
    if inner is None:
        return ""
    try:
        try:
            inner.press("Control+A", timeout=700)
            inner.press("Backspace", timeout=700)
        except Exception:
            pass
        inner.type(value, timeout=2000, delay=0)
    except Exception:
        try:
            inner.click(timeout=700, force=True)
            page.keyboard.type(value, delay=0)
        except Exception:
            return ""
    return card_inner_value(page, iframe_ref)


def card_input_ready(page: Any, iframe_ref: Any) -> bool:
    field = maybe_card_inner_input(page, iframe_ref)
    if field is None:
        return False
    try:
        return bool(field.count())
    except Exception:
        return False


def card_frame_diagnostics(page: Any, limit: int = 20) -> str:
    try:
        frames = page.evaluate(
            """
            (limit) => Array.from(document.querySelectorAll('iframe'))
              .filter((el) => {
                const text = [
                  el.id || '',
                  el.name || '',
                  el.title || '',
                  el.getAttribute('aria-label') || '',
                  el.src || ''
                ].join(' ').toLowerCase();
                return text.includes('card')
                  || text.includes('checkout.pci.shopifyinc.com')
                  || text.includes('カード')
                  || text.includes('セキュリティ');
              })
              .slice(0, limit)
              .map((el) => {
                const rect = el.getBoundingClientRect();
                return {
                  id: el.id || '',
                  name: el.name || '',
                  title: el.title || '',
                  aria: el.getAttribute('aria-label') || '',
                  src: el.src || '',
                  hidden: !!(el.offsetParent === null || el.hidden),
                  width: Math.round(rect.width),
                  height: Math.round(rect.height)
                };
              })
            """,
            limit,
        )
    except Exception:
        return ""
    if not isinstance(frames, list):
        return ""
    parts = []
    for frame in frames:
        if not isinstance(frame, dict):
            continue
        parts.append(
            "{"
            + " ".join(
                [
                    f"id={frame.get('id') or '-'}",
                    f"name={frame.get('name') or '-'}",
                    f"title={frame.get('title') or '-'}",
                    f"aria={frame.get('aria') or '-'}",
                    f"src={frame.get('src') or '-'}",
                    f"hidden={frame.get('hidden')}",
                    f"size={frame.get('width')}x{frame.get('height')}",
                ]
            )
            + "}"
        )
    return compact_text("; ".join(parts), 1600)


def visible_card_iframes(page: Any) -> List[Tuple[float, float, Any]]:
    frames: List[Tuple[float, float, Any]] = []
    locator = page.locator(
        "iframe[id^='card-fields-'], "
        "iframe[name^='card-fields-'], "
        "iframe[src*='checkout.pci.shopifyinc.com'], "
        "iframe[title*='Card' i], "
        "iframe[title*='カード']"
    )
    try:
        count = locator.count()
    except Exception:
        return frames
    for index in range(count):
        iframe = locator.nth(index)
        try:
            box = iframe.bounding_box(timeout=200)
        except Exception:
            box = None
        if not box or box.get("width", 0) < 20 or box.get("height", 0) < 20:
            continue
        frames.append((float(box.get("y") or 0), float(box.get("x") or 0), iframe))
    frames.sort(key=lambda item: (item[0], item[1]))
    return frames


def wait_for_card_ref(page: Any, selectors: Iterable[str], field_name: str, timeout_ms: int = 0, interval_ms: int = 50) -> Any:
    deadline = time.monotonic() + (max(timeout_ms, 0) / 1000)
    position_index = CARD_FRAME_POSITION.get(field_name)
    while True:
        for selector in selectors:
            try:
                if page.locator(selector).count() and card_input_ready(page, selector):
                    return selector
            except Exception:
                pass
        if position_index is not None:
            frames = visible_card_iframes(page)
            if len(frames) > position_index and card_input_ready(page, frames[position_index][2]):
                return frames[position_index][2]
        if timeout_ms <= 0 or time.monotonic() >= deadline:
            return None
        page.wait_for_timeout(interval_ms)


def fill_card_frame(page: Any, selectors: Iterable[str], value: Any, field_name: str, required: bool = True) -> bool:
    if not value:
        if required:
            raise RuntimeError(f"缺少信用卡字段: {field_name}")
        return False

    iframe_ref = wait_for_card_ref(page, selectors, field_name, timeout_ms=8000 if required else 0)
    if iframe_ref is None:
        if required:
            raise RuntimeError(f"未找到信用卡 iframe: {field_name}; availableCardIframes={card_frame_diagnostics(page) or '-'}")
        return False

    frame_locator = card_iframe_locator(page, iframe_ref)
    try:
        frame_locator.wait_for(state="visible", timeout=5000)
        try:
            frame_locator.scroll_into_view_if_needed(timeout=1000)
        except Exception:
            pass
        text_value = str(value)
        filled_value = type_card_inner(page, iframe_ref, text_value)
        if required and not filled_value:
            raise RuntimeError(f"{field_name} iframe 没有写入值; availableCardIframes={card_frame_diagnostics(page) or '-'}")
        return True
    except Exception as exc:
        if not required:
            return False
        raise RuntimeError(f"填写信用卡字段失败: {field_name}: {exc}; availableCardIframes={card_frame_diagnostics(page) or '-'}") from exc


def fill_card_holder_name(page: Any, profile: Dict[str, Any]) -> bool:
    value = profile.get("cardHolderName")
    if fill_card_frame(
        page,
        [
            "iframe[id^='card-fields-name']",
            "iframe[name^='card-fields-name']",
            "iframe[id*='name']",
            "iframe[name*='name']",
            "iframe[title*='name' i]",
            "iframe[title*='名義']",
        ],
        value,
        "name",
        required=False,
    ):
        return True
    return fill_required_text_field(
        page,
        "card.name",
        [
            "input[autocomplete='cc-name']",
            "input[name='name']",
            "input[name='cardName']",
            "input[name='cardholderName']",
            "input[aria-label*='カード名義']",
            "input[aria-label*='Cardholder']",
        ],
        value,
        labels=["カード名義", "カードに記載されている名前", "Name on card", "Cardholder name"],
    )


def fill_card_fields(page: Any, profile: Dict[str, Any]) -> None:
    frame_specs = [
        (
            [
                "iframe[id^='card-fields-number']",
                "iframe[name^='card-fields-number']",
                "iframe[id*='number']",
                "iframe[name*='number']",
                "iframe[title*='Card number' i]",
                "iframe[title*='カード番号']",
            ],
            digits_only(profile.get("cardNumber")),
            "number",
            True,
        ),
        (
            [
                "iframe[id^='card-fields-expiry']",
                "iframe[name^='card-fields-expiry']",
                "iframe[id*='expiry']",
                "iframe[name*='expiry']",
                "iframe[title*='Expiration' i]",
                "iframe[title*='有効期限']",
            ],
            build_expiry_value(profile.get("expMonth"), profile.get("expYear")),
            "expiry",
            True,
        ),
        (
            [
                "iframe[id^='card-fields-verification_value']",
                "iframe[name^='card-fields-verification_value']",
                "iframe[id*='verification']",
                "iframe[name*='verification']",
                "iframe[id*='cvv']",
                "iframe[name*='cvv']",
                "iframe[title*='security' i]",
                "iframe[title*='セキュリティ']",
            ],
            digits_only(profile.get("cvv")),
            "cvv",
            True,
        ),
        (["iframe[id^='card-fields-issue_date']"], build_expiry_value(profile.get("issueMonth"), profile.get("issueYear")), "issue_date", False),
        (["iframe[id^='card-fields-issue_number']"], profile.get("issueNumber"), "issue_number", False),
    ]
    for selectors, value, field_name, required in frame_specs:
        fill_card_frame(page, selectors, value, field_name, required)
    fill_card_holder_name(page, profile)


def extract_order_number(text: str, url: str) -> str:
    patterns = [
        r"确认号码\s*[:：]?\s*([A-Z0-9-]+)",
        r"確認番号\s*[:：]?\s*([A-Z0-9-]+)",
        r"注文番号\s*#?\s*([A-Z0-9-]+)",
        r"Confirmation\s*(?:number|#)\s*[:：#]?\s*([A-Z0-9-]+)",
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
        or "您的订单已确认" in text
        or "确认号码" in text
        or "谢谢你" in text
        or "ご注文ありがとうございます" in text
        or "注文番号" in text
        or "your order is confirmed" in lowered
        or "confirmation number" in lowered
        or "thank you" in lowered
    )


def detect_jump_shop_3ds(url: str, text: str) -> bool:
    lowered = (text or "").lower()
    url_lower = (url or "").lower()
    keywords = ["3d secure", "3dセキュア", "3ds", "本人認証", "authentication required"]
    return any(keyword in lowered for keyword in keywords) or any(keyword in url_lower for keyword in ["3ds", "secure", "authenticate"])


def detect_account_blocked(text: str) -> bool:
    lowered = (text or "").lower()
    return (
        "アカウントはブロックされました" in text
        or "account has been blocked" in lowered
        or "account is blocked" in lowered
    )


def detect_address_validation_required(text: str) -> bool:
    return (
        "配送先のご確認をお願いします" in text
        or "ではありませんか" in text
        or "今のままで問題ありません" in text
        or "住所を再確認" in text
    )


def detect_payment_validation_error(text: str) -> bool:
    return (
        "有効なカード番号を入力してください" in text
        or "正しい有効期限を入力してください" in text
        or "カードのCVVまたはセキュリティコードを入力してください" in text
        or "カードに記載されているとおりの名前を入力してください" in text
        or "この配信方法を使用するには電話番号を入力してください" in text
        or "カード情報を確認してください" in text
        or "支払い方法を確認してください" in text
    )


def wait_for_payment_result(page: Any, timeout_ms: int, interval_ms: int = 250) -> Dict[str, str]:
    deadline = time.monotonic() + (max(timeout_ms, 1000) / 1000)
    last_text = ""
    last_url = page.url
    while True:
        try:
            page.wait_for_load_state("domcontentloaded", timeout=500)
        except Exception:
            pass
        last_text = rendered_page_text(page, 4000)
        last_url = page.url

        if detect_jump_shop_success(last_url, last_text):
            return {"status": "paid", "text": last_text, "url": last_url}
        if detect_jump_shop_3ds(last_url, last_text):
            return {"status": "3ds_required", "text": last_text, "url": last_url}
        verification_keyword = verification_challenge_keyword(last_url, last_text)
        if verification_keyword:
            return {"status": "verification_required", "text": last_text, "url": last_url, "keyword": verification_keyword}
        if detect_payment_validation_error(last_text):
            return {"status": "payment_validation_failed", "text": last_text, "url": last_url}
        if time.monotonic() >= deadline:
            return {"status": "payment_result_timeout", "text": last_text, "url": last_url}
        page.wait_for_timeout(interval_ms)


def load_json(path: Path) -> Dict[str, Any]:
    with path.open("r", encoding="utf-8-sig") as file:
        data = json.load(file)
    if not isinstance(data, dict):
        raise ValueError("config 必须是 JSON object")
    return data


def resolve_path(base_dir: Path, value: Any) -> Path:
    text = str(value or "").strip()
    if not text:
        raise ValueError("路径不能为空")
    path = Path(text)
    return path if path.is_absolute() else base_dir / path


def normalize_header(value: Any) -> str:
    return re.sub(r"[^a-z0-9]+", "", str(value or "").strip().lower())


def string_value(value: Any) -> str:
    if value is None:
        return ""
    return str(value).strip()


def parse_start_at(value: Any) -> Optional[datetime]:
    text = string_value(value)
    if not text:
        return None
    normalized = text.replace("/", "-").replace("T", " ")
    formats = [
        ("%Y-%m-%d %H:%M:%S", True),
        ("%Y-%m-%d %H:%M", True),
        ("%H:%M:%S", False),
        ("%H:%M", False),
    ]
    for fmt, has_date in formats:
        try:
            parsed = datetime.strptime(normalized, fmt)
        except ValueError:
            continue
        if has_date:
            return parsed
        now = datetime.now()
        return datetime.combine(now.date(), parsed.time())
    raise ValueError("startAt 格式不支持，请使用 HH:MM:SS 或 YYYY-MM-DD HH:MM:SS")


def format_seconds(seconds: float) -> str:
    seconds_int = max(int(round(seconds)), 0)
    hours, remainder = divmod(seconds_int, 3600)
    minutes, secs = divmod(remainder, 60)
    if hours:
        return f"{hours}h{minutes:02d}m{secs:02d}s"
    if minutes:
        return f"{minutes}m{secs:02d}s"
    return f"{secs}s"


def wait_until_start(start_at: Optional[datetime]) -> None:
    if start_at is None:
        return
    if start_at <= datetime.now():
        log(f"[schedule] startAt={start_at.strftime('%Y-%m-%d %H:%M:%S')} 已过，立即执行")
        return
    log(f"[schedule] 等待到 {start_at.strftime('%Y-%m-%d %H:%M:%S')} 再开始执行")
    while True:
        remaining = (start_at - datetime.now()).total_seconds()
        if remaining <= 0:
            break
        if remaining <= 10:
            log(f"[schedule] 倒计时 {format_seconds(remaining)}")
            time.sleep(min(remaining, 1))
        elif remaining <= 60:
            log(f"[schedule] 距离开始 {format_seconds(remaining)}")
            time.sleep(min(remaining, 5))
        else:
            log(f"[schedule] 距离开始 {format_seconds(remaining)}")
            time.sleep(min(remaining, 30))
    log("[schedule] 时间到，开始执行")


def load_accounts_from_csv(path: Path) -> List[AccountCookie]:
    with path.open("r", encoding="utf-8-sig", newline="") as file:
        rows = list(csv.reader(file))
    rows = [row for row in rows if any(string_value(value) for value in row)]
    if not rows:
        return []

    first_row = [normalize_header(value) for value in rows[0]]
    has_header = any(name in first_row for name in ["cookie", "cookieheader", "cookies"])
    accounts: List[AccountCookie] = []

    if has_header:
        header = {name: index for index, name in enumerate(first_row) if name}
        cookie_index = next(
            (header[name] for name in ["cookie", "cookieheader", "cookies"] if name in header),
            None,
        )
        if cookie_index is None:
            raise ValueError("CSV 表头存在但缺少 cookie 列")
        email_index = next((header[name] for name in ["email", "mail", "account"] if name in header), None)
        data_rows = rows[1:]
        row_offset = 2
    else:
        cookie_index = 0
        email_index = 1 if len(rows[0]) > 1 else None
        data_rows = rows
        row_offset = 1

    for offset, row in enumerate(data_rows, row_offset):
        cookie_header = string_value(row[cookie_index] if cookie_index < len(row) else "")
        if not cookie_header:
            continue
        email = string_value(row[email_index] if email_index is not None and email_index < len(row) else "")
        accounts.append(AccountCookie(row=offset, cookie_header=cookie_header, email=email))
    return accounts


def parse_cookie_header(cookie_header: str) -> List[Tuple[str, str]]:
    cookies: List[Tuple[str, str]] = []
    seen = set()
    for part in str(cookie_header or "").split(";"):
        if "=" not in part:
            continue
        name, value = part.split("=", 1)
        name = name.strip()
        value = value.strip()
        if not name or name.lower() in {"path", "domain", "expires", "max-age", "samesite", "secure", "httponly"}:
            continue
        if name in seen:
            continue
        seen.add(name)
        cookies.append((name, value))
    return cookies


def add_cookie_header_to_context(context: Any, cookie_header: str) -> int:
    cookie_items = []
    for name, value in parse_cookie_header(cookie_header):
        item: Dict[str, Any] = {
            "name": name,
            "value": value,
            "path": "/",
            "secure": True,
            "sameSite": "Lax",
        }
        if name.startswith("__Host-"):
            item["url"] = JUMP_SHOP_BASE_URL
        else:
            item["domain"] = ".jumpshop-benelic.com"
        cookie_items.append(item)
    if cookie_items:
        context.add_cookies(cookie_items)
    return len(cookie_items)


def require_int(value: Any, name: str) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"{name} 必须是数字") from exc
    if number <= 0:
        raise ValueError(f"{name} 必须大于 0")
    return number


def resolve_product(config: Dict[str, Any]) -> ProductConfig:
    product_url = normalize_jump_shop_product_url(str(config.get("productUrl") or config.get("eventUrl") or ""))
    if not product_url:
        raise ValueError("config 缺少 productUrl")

    parsed: Dict[str, Any] = {}
    configured_variant_id = config.get("variantId")
    configured_product_id = config.get("productId")
    configured_section_id = str(config.get("sectionId") or "").strip()
    has_manual_product_params = bool(configured_variant_id and configured_product_id and configured_section_id)
    if has_manual_product_params:
        log(
            "[product] 使用 config 商品参数: "
            f"variantId={configured_variant_id} productId={configured_product_id} sectionId={configured_section_id}"
        )
    else:
        try:
            log(f"[product] 解析商品页: {product_url}")
            parsed = fetch_jump_shop_product_info(product_url)
            log(
                "[product] 解析成功: "
                f"title={parsed.get('title') or '-'} variantId={parsed.get('variantId')} "
                f"productId={parsed.get('productId')} sectionId={parsed.get('sectionId')}"
            )
        except Exception as exc:
            log(f"[product] 自动解析失败，将尝试使用 config 手动参数: {exc}")

    variant_id = configured_variant_id or parsed.get("variantId")
    product_id = configured_product_id or parsed.get("productId")
    section_id = str(configured_section_id or parsed.get("sectionId") or "").strip()
    if not variant_id or not product_id or not section_id:
        raise ValueError("商品缺少 variantId/productId/sectionId，请在 config 中手动补齐")
    parsed_title = str(parsed.get("title") or "").strip()

    return ProductConfig(
        product_url=product_url,
        variant_id=require_int(variant_id, "variantId"),
        product_id=require_int(product_id, "productId"),
        section_id=section_id,
        quantity=require_int(config.get("quantity") or 1, "quantity"),
        title=parsed_title,
    )


def validate_profile(profile: Dict[str, Any], payment_method: str = "card") -> None:
    required = [
        "lastName",
        "firstName",
        "phone",
        "postalCode",
        "province",
        "city",
        "address1",
    ]
    if payment_method == "card":
        required.extend(["cardHolderName", "cardNumber", "expMonth", "expYear", "cvv"])
    missing = [key for key in required if not string_value(profile.get(key))]
    if missing:
        raise ValueError("profile 缺少字段: " + ", ".join(missing))
    if payment_method == "card":
        build_expiry_value(profile.get("expMonth"), profile.get("expYear"))
        card_number = digits_only(profile.get("cardNumber"))
        cvv = digits_only(profile.get("cvv"))
        if not 12 <= len(card_number) <= 19:
            raise ValueError("profile.cardNumber 必须是真实卡号数字，长度 12-19 位")
        if not 3 <= len(cvv) <= 4:
            raise ValueError("profile.cvv 必须是 3-4 位数字")
    if not profile.get("billingSameAsShipping", True):
        billing_required = ["billingLastName", "billingFirstName", "billingPhone", "billingPostalCode", "billingProvince", "billingCity", "billingAddress1"]
        billing_missing = [key for key in billing_required if not string_value(profile.get(key))]
        if billing_missing:
            raise ValueError("profile 缺少账单地址字段: " + ", ".join(billing_missing))


def create_browser(playwright: Any, headless: bool) -> Any:
    launch_options: Dict[str, Any] = {"headless": bool(headless)}
    browser_path = browser_executable_path()
    if browser_path:
        launch_options["executable_path"] = browser_path
    return playwright.chromium.launch(**launch_options)


def install_fast_routes(context: Any) -> None:
    def route_handler(route: Any, request: Any) -> None:
        url = (request.url or "").lower()
        try:
            if request.resource_type in BLOCKED_RESOURCE_TYPES or any(keyword in url for keyword in BLOCKED_URL_KEYWORDS):
                route.abort()
            else:
                route.continue_()
        except Exception:
            try:
                route.continue_()
            except Exception:
                pass

    context.route("**/*", route_handler)


def add_to_cart(context: Any, product: ProductConfig) -> Dict[str, Any]:
    response = context.request.post(
        f"{JUMP_SHOP_BASE_URL}/cart/add.js",
        form={
            "form_type": "product",
            "utf8": "✓",
            "quantity": str(product.quantity),
            "id": str(product.variant_id),
            "product-id": str(product.product_id),
            "section-id": product.section_id,
        },
        headers={
            "Origin": JUMP_SHOP_BASE_URL,
            "Referer": product.product_url,
            "X-Requested-With": "XMLHttpRequest",
        },
        timeout=120000,
    )
    return {"ok": response.ok, "status": response.status, "text": response.text()}


def add_to_cart_from_page(page: Any, product: ProductConfig) -> Dict[str, Any]:
    return page.evaluate(
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
            "variantId": product.variant_id,
            "quantity": product.quantity,
            "productId": product.product_id,
            "sectionId": product.section_id,
        },
    )


def summarize_add_to_cart_result(result: Dict[str, Any]) -> str:
    try:
        data = json.loads(str(result.get("text") or "{}"))
    except json.JSONDecodeError:
        return "body=non-json"
    if not isinstance(data, dict):
        return "body=unexpected"
    return (
        f"variantId={data.get('variant_id') or data.get('id') or '-'} "
        f"productId={data.get('product_id') or '-'} "
        f"quantity={data.get('quantity') or '-'} "
        f"title={compact_text(data.get('product_title') or data.get('title') or data.get('name') or '-', 120)}"
    )


def summarize_add_to_cart_error(result: Any) -> str:
    if not isinstance(result, dict):
        return compact_text(result, 120)
    status = result.get("status") or "-"
    body = str(result.get("text") or "")
    lower_body = body.lower()

    reason = ""
    if is_cloudflare_rate_limited_result(result):
        reason = "cloudflare_rate_limited"
    elif "verifying your connection" in lower_body:
        reason = "verifying_connection"
    elif "captcha" in lower_body or "hcaptcha" in lower_body or "recaptcha" in lower_body:
        reason = "captcha_required"
    elif "販売期間外" in body or "販売期間" in body:
        reason = "outside_sales_period"
    elif "sold out" in lower_body or "売り切れ" in body or "在庫" in body:
        reason = "sold_out_or_stock"
    elif "/account/login" in lower_body or "login" in lower_body:
        reason = "login_required"
    else:
        try:
            data = json.loads(body)
            if isinstance(data, dict):
                reason = compact_text(
                    data.get("description")
                    or data.get("message")
                    or data.get("error")
                    or data.get("status")
                    or data,
                    120,
                )
        except json.JSONDecodeError:
            reason = compact_text(visible_text(body), 120)

    return f"HTTP {status} {reason or 'failed'}"


def should_continue_after_add_to_cart_422(result: Any) -> bool:
    if not isinstance(result, dict) or int(result.get("status") or 0) != 422:
        return False
    body = str(result.get("text") or "")
    lower_body = body.lower()
    return (
        "カートに追加されました" in body
        or "最大数がすでにカートに入っています" in body
        or "already in your cart" in lower_body
        or "only" in lower_body and "added" in lower_body and "cart" in lower_body
        or "maximum" in lower_body and "cart" in lower_body
    )


def is_verifying_connection_result(result: Any) -> bool:
    return isinstance(result, dict) and int(result.get("status") or 0) == 403 and "verifying your connection" in str(result.get("text") or "").lower()


def is_cloudflare_rate_limited_result(result: Any) -> bool:
    if not isinstance(result, dict):
        return False
    status = int(result.get("status") or 0)
    body = str(result.get("text") or "").lower()
    return status == 429 and (
        "cloudflare" in body
        or "access denied" in body
        or "restrict access" in body
        or "too many requests" in body
    )


def detect_verification_challenge(url: str, text: str) -> bool:
    lowered = f"{url or ''}\n{text or ''}".lower()
    keywords = [
        "captcha",
        "hcaptcha",
        "recaptcha",
        "verify you are human",
        "are you a robot",
        "not a robot",
        "bot protection",
        "人間であること",
        "本人確認",
    ]
    return any(keyword in lowered for keyword in keywords)


def verification_challenge_keyword(url: str, text: str) -> str:
    lowered = f"{url or ''}\n{text or ''}".lower()
    keywords = [
        "hcaptcha",
        "recaptcha",
        "captcha",
        "verify you are human",
        "are you a robot",
        "not a robot",
        "bot protection",
        "人間であること",
        "本人確認",
    ]
    return next((keyword for keyword in keywords if keyword in lowered), "")


def click_pay_button(page: Any) -> None:
    selectors = [
        "#checkout-pay-button",
        "button:has-text('ご注文完了')",
        "button[type='submit'][name='button']",
        "button:has-text('今すぐ支払う')",
        "button:has-text('支払う')",
        "button:has-text('Pay now')",
    ]
    last_error: Optional[Exception] = None
    for selector in selectors:
        locator = page.locator(selector)
        try:
            if locator.count():
                target = locator.first
                try:
                    target.scroll_into_view_if_needed(timeout=1000)
                except Exception:
                    pass
                target.click(timeout=30000)
                return
        except Exception as exc:
            last_error = exc
    if last_error:
        raise RuntimeError(f"点击支付按钮失败: {last_error}") from last_error
    raise RuntimeError("未找到支付按钮")


def wait_best_effort(page: Any, timeout_ms: int = 15000) -> None:
    try:
        page.wait_for_load_state("networkidle", timeout=timeout_ms)
    except Exception:
        try:
            page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
        except Exception:
            pass


def wait_for_checkout_ready(page: Any, timeout_ms: int = 30000) -> None:
    selectors = [
        "iframe[id^='card-fields-number']",
        "input[name='lastName']",
        "#TextField268",
        "input[type='email']",
    ]
    deadline = time.monotonic() + (timeout_ms / 1000)
    while time.monotonic() < deadline:
        for selector in selectors:
            try:
                if page.locator(selector).count():
                    return
            except Exception:
                pass
        page.wait_for_timeout(100)


def take_failure_screenshot(page: Any, base_dir: Path, row: AccountCookie, status: str) -> str:
    try:
        screenshots_dir = base_dir / "screenshots"
        screenshots_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        safe_status = re.sub(r"[^a-zA-Z0-9_-]+", "_", status).strip("_") or "failure"
        path = screenshots_dir / f"row_{row.row}_{safe_status}_{timestamp}.png"
        page.screenshot(path=str(path), full_page=True)
        return str(path)
    except Exception as exc:
        log(f"[screenshot] failed row={row.row} status={status}: {compact_text(exc, 120)}")
        return ""


def failure(row: AccountCookie, status: str, message: str, final_url: str = "", order_no: str = "", screenshot: str = "") -> Dict[str, Any]:
    return {
        "row": row.row,
        "email": row.email,
        "success": False,
        "status": status,
        "message": compact_text(message, 800),
        "orderNo": order_no,
        "finalUrl": final_url,
        "screenshot": screenshot,
    }


def success(row: AccountCookie, status: str, message: str, final_url: str = "", order_no: str = "") -> Dict[str, Any]:
    return {
        "row": row.row,
        "email": row.email,
        "success": True,
        "status": status,
        "message": compact_text(message, 800),
        "orderNo": order_no,
        "finalUrl": final_url,
        "screenshot": "",
    }


def run_account(row: AccountCookie, product: ProductConfig, config: Dict[str, Any], base_dir: Path) -> Dict[str, Any]:
    profile = config["profile"]
    headless = bool(config.get("headless", True))
    submit_payment = bool(config.get("submitPayment", True))
    payment_method = normalize_payment_method(config.get("paymentMethod", "card"))
    retry_count = max(int(config.get("addToCartRetries", 3) or 0), 0)
    total_add_attempts = retry_count + 1
    payment_result_timeout_ms = int(config.get("paymentResultTimeoutMs", 60000) or 60000)
    label = f"row={row.row} email={row.email or '-'}"
    playwright_manager = None
    browser = None
    page = None

    try:
        playwright_manager = launch_browser_context()
        playwright = playwright_manager.__enter__()
        browser = create_browser(playwright, headless=headless)
        context = browser.new_context(user_agent=DEFAULT_USER_AGENT, locale="ja-JP")
        install_fast_routes(context)
        cookie_count = add_cookie_header_to_context(context, row.cookie_header)
        if cookie_count <= 0:
            return failure(row, "failed", "Cookie 为空或无法解析")
        log(f"[{label}] cookieCount={cookie_count} direct add-to-cart")

        last_add_message = ""
        use_page_add_to_cart = False
        for attempt in range(1, total_add_attempts + 1):
            try:
                if use_page_add_to_cart and page is None:
                    page = context.new_page()
                    log(f"[{label}] open product for browser-context add-to-cart")
                    page.goto(product.product_url, wait_until="domcontentloaded", timeout=120000)
                add_started = time.perf_counter()
                add_result = add_to_cart_from_page(page, product) if use_page_add_to_cart and page is not None else add_to_cart(context, product)
                add_ms = int((time.perf_counter() - add_started) * 1000)
                if isinstance(add_result, dict) and add_result.get("ok"):
                    log(
                        f"[{label}] add-to-cart ok attempt={attempt}/{total_add_attempts} "
                        f"mode={'page' if use_page_add_to_cart else 'direct'} apiMs={add_ms} {summarize_add_to_cart_result(add_result)}"
                    )
                    break
                if should_continue_after_add_to_cart_422(add_result):
                    last_add_message = summarize_add_to_cart_error(add_result)
                    log(
                        f"[{label}] add-to-cart partial/limit ok attempt={attempt}/{total_add_attempts} "
                        f"mode={'page' if use_page_add_to_cart else 'direct'} apiMs={add_ms}: {last_add_message}; continue checkout"
                    )
                    break
                last_add_message = summarize_add_to_cart_error(add_result)
                if is_cloudflare_rate_limited_result(add_result):
                    log(
                        f"[{label}] add-to-cart blocked attempt={attempt}/{total_add_attempts} "
                        f"mode={'page' if use_page_add_to_cart else 'direct'} apiMs={add_ms}: {last_add_message}; stop retries"
                    )
                    return failure(
                        row,
                        "cloudflare_rate_limited",
                        "Cloudflare 返回 429/Access denied，已停止本账号重试；请降低并发/重试次数，等待限流恢复后再运行",
                        product.product_url,
                    )
                if is_verifying_connection_result(add_result) and not use_page_add_to_cart:
                    use_page_add_to_cart = True
                    log(
                        f"[{label}] add-to-cart failed attempt={attempt}/{total_add_attempts} "
                        f"mode=direct apiMs={add_ms}: {last_add_message}; switch to page mode"
                    )
                    continue
                log(
                    f"[{label}] add-to-cart failed attempt={attempt}/{total_add_attempts} "
                    f"mode={'page' if use_page_add_to_cart else 'direct'} apiMs={add_ms}: {last_add_message}"
                )
            except Exception as exc:
                last_add_message = compact_text(exc, 120)
                log(f"[{label}] add-to-cart exception attempt={attempt}/{total_add_attempts}: {last_add_message}")
        else:
            return failure(row, "add_to_cart_failed", f"Jump Shop 加购失败: {last_add_message}", product.product_url)

        if page is None:
            page = context.new_page()
        log(f"[{label}] open checkout")
        checkout_started = time.perf_counter()
        page.goto(JUMP_SHOP_CHECKOUT_URL, wait_until="domcontentloaded", timeout=120000)
        wait_for_checkout_ready(page, timeout_ms=30000)
        log(f"[{label}] checkout ready loadMs={int((time.perf_counter() - checkout_started) * 1000)}")
        if "/account/login" in page.url:
            screenshot = take_failure_screenshot(page, base_dir, row, "login_required")
            return failure(row, "login_required", "Jump Shop checkout 跳回登录页，Cookie 登录态失效", page.url, screenshot=screenshot)

        current_text = rendered_page_text(page, 2000)
        verification_keyword = verification_challenge_keyword(page.url, current_text)
        if verification_keyword:
            screenshot = take_failure_screenshot(page, base_dir, row, "checkout_verification")
            return failure(
                row,
                "verification_required",
                f"Jump Shop checkout 命中人机验证: keyword={verification_keyword}; screenshot={screenshot}",
                page.url,
                screenshot=screenshot,
            )
        try:
            if row.email:
                maybe_fill(page, ["input[type='email']", "input[name='email']", "#email"], row.email)
            shipping_started = time.perf_counter()
            fill_shipping_address(page, profile)
            shipping_ms = int((time.perf_counter() - shipping_started) * 1000)
            shipping_text = rendered_page_text(page, 2500)
            if detect_address_validation_required(shipping_text):
                screenshot = take_failure_screenshot(page, base_dir, row, "address_validation_required")
                return failure(
                    row,
                    "address_validation_required",
                    f"Jump Shop 配送地址需要人工确认/邮编不匹配，请修正 config 邮编地址; screenshot={screenshot}",
                    page.url,
                    screenshot=screenshot,
                )
            payment_started = time.perf_counter()
            if payment_method == "cod":
                select_cod_payment(page)
                payment_ms = int((time.perf_counter() - payment_started) * 1000)
                log(f"[{label}] checkout fields filled shippingMs={shipping_ms} paymentMethod=cod paymentMs={payment_ms}")
            else:
                fill_billing_address(page, profile)
                billing_ms = int((time.perf_counter() - payment_started) * 1000)
                card_started = time.perf_counter()
                fill_card_fields(page, profile)
                card_ms = int((time.perf_counter() - card_started) * 1000)
                log(f"[{label}] checkout fields filled shippingMs={shipping_ms} billingMs={billing_ms} cardMs={card_ms}")
        except Exception as exc:
            screenshot = take_failure_screenshot(page, base_dir, row, "checkout_fill_failed")
            return failure(
                row,
                "checkout_fill_failed",
                f"填写 checkout 表单失败: {compact_text(exc, 200)}; screenshot={screenshot}",
                page.url,
                screenshot=screenshot,
            )

        if not submit_payment:
            return success(row, "ready_to_pay", "dry-run: 已加购并填完 checkout，未点击支付", page.url)

        click_pay_button(page)
        payment_result = wait_for_payment_result(page, payment_result_timeout_ms)
        final_text = payment_result.get("text", "")
        final_url = payment_result.get("url", page.url)
        result_status = payment_result.get("status", "")
        if result_status == "paid":
            order_no = extract_order_number(final_text, final_url)
            return success(row, "paid", "Jump Shop 下单成功", final_url, order_no)
        if result_status == "3ds_required":
            screenshot = take_failure_screenshot(page, base_dir, row, "3ds_required")
            return failure(row, "3ds_required", f"3DS_REQUIRED: Jump Shop 命中外部验证页; screenshot={screenshot}", final_url, screenshot=screenshot)
        if result_status == "verification_required":
            verification_keyword = payment_result.get("keyword", "-")
            screenshot = take_failure_screenshot(page, base_dir, row, "payment_verification")
            return failure(
                row,
                "verification_required",
                f"Jump Shop 支付后命中人机验证: keyword={verification_keyword}; screenshot={screenshot}",
                final_url,
                screenshot=screenshot,
            )
        if result_status == "payment_validation_failed":
            screenshot = take_failure_screenshot(page, base_dir, row, "payment_validation_failed")
            return failure(row, "payment_validation_failed", f"Jump Shop 支付表单校验失败: {final_text[:300]}; screenshot={screenshot}", final_url, screenshot=screenshot)
        if result_status == "payment_result_timeout":
            screenshot = take_failure_screenshot(page, base_dir, row, "payment_result_timeout")
            return failure(
                row,
                "payment_result_timeout",
                f"Jump Shop 支付结果等待超时({payment_result_timeout_ms}ms)，可能仍在处理中: {final_text[:240] or final_url}; screenshot={screenshot}",
                final_url,
                screenshot=screenshot,
            )
        screenshot = take_failure_screenshot(page, base_dir, row, "payment_failed")
        return failure(row, "payment_failed", f"Jump Shop 下单失败: {final_text[:300] or final_url}; screenshot={screenshot}", final_url, screenshot=screenshot)
    except Exception as exc:
        return failure(row, "exception", f"Jump Shop 执行异常: {exc}", "")
    finally:
        if browser is not None:
            try:
                browser.close()
            except Exception:
                pass
        if playwright_manager is not None:
            try:
                playwright_manager.__exit__(None, None, None)
            except Exception:
                pass


def write_results(path: Path, results: Iterable[Dict[str, Any]]) -> None:
    with path.open("w", encoding="utf-8-sig", newline="") as file:
        writer = csv.DictWriter(file, fieldnames=RESULT_FIELDS)
        writer.writeheader()
        for result in sorted(results, key=lambda item: int(item.get("row") or 0)):
            writer.writerow({field: result.get(field, "") for field in RESULT_FIELDS})


def run(config_path: Path) -> int:
    config_path = config_path.resolve()
    config = load_json(config_path)
    base_dir = config_path.parent
    csv_path = resolve_path(base_dir, config.get("csvPath") or config.get("cookiesPath") or "cookies.csv")
    if not csv_path.exists():
        raise FileNotFoundError(f"CSV 文件不存在: {csv_path}")

    if not isinstance(config.get("profile"), dict):
        raise ValueError("config 缺少 profile")
    payment_method = normalize_payment_method(config.get("paymentMethod", "card"))
    config["paymentMethod"] = payment_method
    validate_profile(config["profile"], payment_method=payment_method)
    product = resolve_product(config)
    accounts = load_accounts_from_csv(csv_path)
    if not accounts:
        raise ValueError("CSV 中没有可用 cookie 行")

    concurrency = max(int(config.get("concurrency") or 1), 1)
    concurrency = min(concurrency, len(accounts))
    start_at = parse_start_at(config.get("startAt"))

    log(
        f"[ready] accounts={len(accounts)} concurrency={concurrency} quantity={product.quantity} "
        f"submitPayment={bool(config.get('submitPayment', True))} "
        f"startAt={start_at.strftime('%Y-%m-%d %H:%M:%S') if start_at else 'now'}"
    )
    wait_until_start(start_at)

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    result_path = base_dir / f"quick_checkout_results_{timestamp}.csv"

    log(
        f"[start] accounts={len(accounts)} concurrency={concurrency} quantity={product.quantity} "
        f"submitPayment={bool(config.get('submitPayment', True))} results={result_path}"
    )

    results: List[Dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        futures = {executor.submit(run_account, account, product, config, base_dir): account for account in accounts}
        for future in as_completed(futures):
            account = futures[future]
            try:
                result = future.result()
            except Exception as exc:
                result = failure(account, "exception", f"线程异常: {exc}")
            results.append(result)
            log(
                f"[result] row={result.get('row')} email={result.get('email') or '-'} "
                f"success={result.get('success')} status={result.get('status')} message={result.get('message')}"
            )
            write_results(result_path, results)

    success_count = sum(1 for item in results if item.get("success"))
    log(f"[completed] success={success_count} failed={len(results) - success_count} results={result_path}")
    return 0 if success_count > 0 else 2


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Jump Shop CSV Cookie quick checkout runner")
    parser.add_argument("--config", required=True, help="Path to quick_checkout.config.json")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    try:
        return run(Path(args.config))
    except KeyboardInterrupt:
        log("[aborted] interrupted by user")
        return 130
    except Exception as exc:
        log(f"[fatal] {exc}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
