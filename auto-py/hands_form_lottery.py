import html
import json
import os
import re
import time
from pathlib import Path
from typing import Any, Dict, Optional, Sequence, Tuple
from urllib.parse import urlsplit, urlunsplit

import requests

from ticket_runtime import bool_value, env_text, get_logger
from livepocket_proxy import apply_proxy_to_session, get_proxy, mark_proxy_failed, proxy_max_attempts


LOGGER = get_logger("hands-form.parser")

SUCCESS_MARKERS = (
    "お客様のお申し込みを受け付けました。",
    "ご入力いただいたメールアドレス宛に自動でメールをお送りしていますのでご確認ください。",
    "ご入力の内容は正常に送信されました。",
    "受付済み",
)
FAILURE_MARKERS = (
    "Google reCAPTCHAの認証に失敗しました。",
    "reCAPTCHAの認証に失敗しました。",
    "認証に失敗しました。再度送信してください。",
)
CONFIRM_COPY = "以下の内容で送信します。ご確認のうえ、「この内容で送信する」ボタンを押してください。"
ORDER_NUMBER_PATTERNS = (
    re.compile(r"登録番号[:：]?\s*([0-9]{4,})"),
    re.compile(r"受付番号[:：]?\s*([0-9]{4,})"),
    re.compile(r"申込番号[:：]?\s*([0-9]{4,})"),
)
DEFAULT_BROWSER_TIMEOUT_MS = 90000

DEFAULT_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/147.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language": "ja,en-US;q=0.9,en;q=0.8",
    "Cache-Control": "no-cache",
    "Pragma": "no-cache",
}


def _log(message: str) -> None:
    LOGGER.info(message)


def _parse_json_object(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    if not isinstance(value, str) or not value.strip():
        return {}
    try:
        parsed = json.loads(value)
        return parsed if isinstance(parsed, dict) else {}
    except (TypeError, ValueError, json.JSONDecodeError):
        return {}


def _env_int(name: str, default: int) -> int:
    try:
        return int(env_text(name, str(default)))
    except ValueError:
        return default


def _compact_text(value: Any, max_length: int = 2000) -> str:
    text = re.sub(r"\s+", " ", str(value or "")).strip()
    return text[:max(max_length, 0)]


def extract_hands_order_number(page_text: str) -> str:
    for pattern in ORDER_NUMBER_PATTERNS:
        match = pattern.search(page_text or "")
        if match:
            return match.group(1)
    return ""


def classify_hands_page(state: Dict[str, Any]) -> str:
    body_text = str(state.get("bodyText") or "")
    if extract_hands_order_number(body_text) or any(marker in body_text for marker in SUCCESS_MARKERS):
        return "success"
    if any(marker in body_text for marker in FAILURE_MARKERS):
        return "failed"
    if bool(state.get("captchaChallengeVisible")):
        return "captcha_required"
    action = str(state.get("formAction") or "")
    if bool(state.get("hasForm")) and bool(state.get("hasEditableFields")) and "/process" not in action:
        return "entry"
    if bool(state.get("hasForm")) and (
        bool(state.get("hasConfirmButton"))
        or CONFIRM_COPY in body_text
        or ("/process" in action and not bool(state.get("hasEditableFields")))
    ):
        return "confirm"
    return "unknown"


def resolve_hands_execution_input(payload: Dict[str, Any]) -> Dict[str, Any]:
    task_options = _parse_json_object(payload.get("taskOptions"))
    account_info = _parse_json_object(payload.get("accountInfo"))
    full_name = str(account_info.get("fullName") or "").strip()
    if not full_name:
        full_name = f"{account_info.get('familyName') or ''}{account_info.get('givenName') or ''}".strip()
    quantity = payload.get("purchaseQuantity") or task_options.get("purchaseQuantity") or task_options.get("quantity") or 1
    try:
        quantity = max(int(quantity), 1)
    except (TypeError, ValueError):
        quantity = 1
    dry_run = (
        bool_value(env_text("HANDS_FORM_DRY_RUN", "false"))
        or bool_value(task_options.get("handsFormDryRun"))
        or bool_value(payload.get("handsFormDryRun"))
    )
    return {
        "eventUrl": str(
            task_options.get("eventUrl")
            or task_options.get("lotteryEntryUrl")
            or task_options.get("ticketEntryUrl")
            or ""
        ).strip(),
        "email": str(payload.get("email") or task_options.get("email") or "").strip(),
        "fullName": full_name,
        "furigana": str(account_info.get("furigana") or "").strip(),
        "quantity": quantity,
        "dryRun": dry_run,
    }


def _browser_executable_path() -> Optional[str]:
    candidates = (
        env_text("HANDS_FORM_BROWSER_PATH"),
        env_text("LIVEPOCKET_BROWSER_PATH"),
        "/usr/bin/google-chrome",
        "/usr/bin/google-chrome-stable",
        "/usr/bin/chromium",
        "/usr/bin/chromium-browser",
        "/snap/bin/chromium",
        r"C:\Program Files\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
        r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
        r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    )
    return next((path for path in candidates if path and os.path.exists(path)), None)


def _first_visible_locator(page: Any, selectors: Sequence[str]) -> Optional[Any]:
    for selector in selectors:
        locator = page.locator(selector)
        for index in range(min(locator.count(), 10)):
            candidate = locator.nth(index)
            try:
                if candidate.is_visible() and candidate.is_enabled():
                    return candidate
            except Exception:
                continue
    return None


def _read_browser_state(page: Any) -> Dict[str, Any]:
    state = page.evaluate(
        """
        () => {
          const form = document.querySelector('form#entryForm')
            || document.querySelector('form[action*="/process"]');
          const visibleEditable = form
            ? Array.from(form.querySelectorAll('input, textarea, select')).some((el) => {
                const type = String(el.getAttribute('type') || '').toLowerCase();
                if (type === 'hidden' || el.disabled) return false;
                const rect = el.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              })
            : false;
          const controls = form
            ? Array.from(form.querySelectorAll('button, input[type="submit"], input[type="button"], a'))
            : [];
          const hasConfirmButton = controls.some((el) =>
            String(el.innerText || el.value || '').includes('この内容で送信する')
            || String(el.getAttribute('name') || '') === 'Submit'
          );
          const captchaChallengeVisible = Array.from(document.querySelectorAll('iframe[src*="recaptcha/api2/bframe"]'))
            .some((el) => {
              const rect = el.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0;
            });
          return {
            bodyText: document.body ? document.body.innerText : '',
            hasForm: !!form,
            formAction: form ? String(form.action || '') : '',
            hasEditableFields: visibleEditable,
            hasConfirmButton,
            captchaChallengeVisible,
          };
        }
        """
    )
    result = state if isinstance(state, dict) else {}
    result["url"] = str(getattr(page, "url", "") or "")
    return result


def _wait_for_page_stage(page: Any, terminal_stages: Sequence[str], timeout_ms: int) -> Tuple[str, Dict[str, Any]]:
    deadline = time.monotonic() + max(timeout_ms, 1000) / 1000
    last_state: Dict[str, Any] = {}
    last_stage = "unknown"
    while time.monotonic() < deadline:
        try:
            last_state = _read_browser_state(page)
            last_stage = classify_hands_page(last_state)
            if last_stage in terminal_stages:
                return last_stage, last_state
        except Exception:
            pass
        page.wait_for_timeout(500)
    return last_stage, last_state


def _fill_hands_entry_form(page: Any, values: Dict[str, Any]) -> None:
    required_fields = {
        "cname": values["fullName"],
        "cname2": values["furigana"],
        "mail": values["email"],
        "mail_confirmation": values["email"],
    }
    missing = []
    for name, value in required_fields.items():
        locator = page.locator(f'form#entryForm input[name="{name}"]').first
        if locator.count() == 0:
            missing.append(name)
            continue
        locator.fill(str(value))
    if missing:
        raise RuntimeError(f"Hands 表单缺少字段: {', '.join(missing)}")

    quantity = page.locator('form#entryForm input[name="number"]').first
    if quantity.count() > 0:
        quantity.evaluate(
            """(el, value) => {
              el.value = String(value);
              el.dispatchEvent(new Event('input', { bubbles: true }));
              el.dispatchEvent(new Event('change', { bubbles: true }));
            }""",
            str(values["quantity"]),
        )


def _submit_hands_form(page: Any, *, confirm: bool) -> None:
    selectors = (
        (
            'form#entryForm button.-green[name="Submit"]',
            'form#entryForm button[name="Submit"]',
            'form#entryForm button:has-text("この内容で送信する")',
            'form#entryForm input[type="submit"]',
        )
        if confirm
        else (
            'form#entryForm button:has-text("確認画面へ")',
            'form#entryForm input[type="submit"]',
            'form#entryForm button[type="submit"]',
        )
    )
    button = _first_visible_locator(page, selectors)
    if button is not None:
        button.scroll_into_view_if_needed()
        button.click()
        return
    form = page.locator('form#entryForm').first
    if form.count() == 0:
        raise RuntimeError("Hands 页面未找到 entryForm")
    form.evaluate(
        """(el) => {
          if (typeof el.requestSubmit === 'function') el.requestSubmit();
          else el.submit();
        }"""
    )


def _save_failure_artifacts(page: Any, execution_id: str) -> Dict[str, str]:
    if not bool_value(env_text("HANDS_FORM_FAILURE_ARTIFACTS_ENABLED", "true")):
        return {}
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "-", execution_id or "unknown")
    directory = Path(__file__).resolve().parent / ".runtime" / "hands-form"
    directory.mkdir(parents=True, exist_ok=True)
    prefix = directory / f"{safe_id}-{int(time.time())}"
    result: Dict[str, str] = {}
    try:
        screenshot_path = prefix.with_suffix(".png")
        page.screenshot(path=str(screenshot_path), full_page=True)
        result["screenshot"] = str(screenshot_path)
    except Exception as exc:
        result["screenshotError"] = str(exc)
    try:
        html_path = prefix.with_suffix(".html")
        html_path.write_text(page.content(), encoding="utf-8")
        result["html"] = str(html_path)
    except Exception as exc:
        result["htmlError"] = str(exc)
    return result


def normalize_hands_event_url(event_url: str) -> str:
    parts = urlsplit(str(event_url or "").strip())
    if not parts.scheme or not parts.netloc:
        raise ValueError("Hands 活动链接无效")
    return urlunsplit((parts.scheme, parts.netloc, parts.path, "", ""))


def _clean_text(value: str) -> str:
    text = html.unescape(re.sub(r"<[^>]+>", " ", value or ""))
    return re.sub(r"\s+", " ", text).strip()


def _match_group(pattern: str, text: str) -> str:
    match = re.search(pattern, text, flags=re.I | re.S)
    return _clean_text(match.group(1)) if match else ""


def _extract_table_value(text: str, header_text: str) -> str:
    patterns = [
        rf"<th[^>]*>\s*{re.escape(header_text)}\s*</th>\s*<td[^>]*>(.*?)</td>",
        rf"<dt[^>]*>\s*{re.escape(header_text)}\s*</dt>\s*<dd[^>]*>(.*?)</dd>",
    ]
    for pattern in patterns:
        value = _match_group(pattern, text)
        if value:
            return value
    return ""


def _extract_event_title(text: str) -> str:
    for pattern in (
        r"<h2[^>]*>(.*?)</h2>",
        r"<h1[^>]*>(.*?)</h1>",
        r"<title[^>]*>(.*?)</title>",
    ):
        value = _match_group(pattern, text)
        if value:
            return value
    return "Hands 抽票活动"


def _extract_store_name(text: str) -> str:
    for pattern in (
        r"<h1[^>]*>.*?<[^>]+>(.*?)</[^>]+>\s*</h1>",
        r"<h3[^>]*>(.*?)</h3>",
    ):
        value = _match_group(pattern, text)
        if value:
            return value
    return ""


def _segment_id_from_url(event_url: str) -> str:
    match = re.search(r"/segment/(\d+)", event_url)
    return match.group(1) if match else "unknown"


def fetch_hands_event_info(event_url: str, session: Optional[requests.Session] = None) -> Dict[str, Any]:
    normalized_url = normalize_hands_event_url(event_url)
    client = session or requests.Session()
    client.headers.update(DEFAULT_HEADERS)

    proxy_key = f"hands-event-info:{normalized_url}"
    response: Optional[requests.Response] = None
    last_error: Optional[Exception] = None
    max_attempts = proxy_max_attempts()

    for attempt in range(1, max_attempts + 1):
        proxy = get_proxy(proxy_key, refresh=attempt > 1, platform_code="hands-form")
        apply_proxy_to_session(client, proxy)
        try:
            response = client.get(normalized_url, timeout=30)
            response.raise_for_status()
            break
        except Exception as exc:
            last_error = exc
            if proxy:
                mark_proxy_failed(proxy, reason=f"Hands 活动解析请求异常: {exc}", key=proxy_key, platform_code="hands-form")
            if attempt >= max_attempts:
                raise
            _log(f"[hands-form] 活动解析代理重试 {attempt}/{max_attempts}: {exc}")

    if response is None:
        raise RuntimeError(f"Hands 活动解析失败: {last_error}")

    page_text = response.text or ""
    event_title = _extract_event_title(page_text)
    store_name = _extract_store_name(page_text)
    winner_visit_time = _extract_table_value(page_text, "当選者来店日時")
    sales_location = _extract_table_value(page_text, "販売場所")
    segment_id = _segment_id_from_url(normalized_url)
    session_id = f"segment:{segment_id}"

    if not winner_visit_time:
        winner_visit_time = "待页面确认"

    raw_summary_parts = []
    if winner_visit_time:
        raw_summary_parts.append(f"当選者来店日時: {winner_visit_time}")
    if sales_location:
        raw_summary_parts.append(f"販売場所: {sales_location}")
    raw_summary = " / ".join(raw_summary_parts)

    reception_title = sales_location or store_name or "Hands 事前Web抽選"

    return {
        "eventUrl": normalized_url,
        "ticketEntryUrl": normalized_url,
        "eventTitle": event_title,
        "entryStartTime": "",
        "entryEndTime": "",
        "rawSummary": raw_summary,
        "sessions": [
            {
                "sessionId": session_id,
                "sessionLabel": winner_visit_time,
                "eventUrl": normalized_url,
                "receptionId": session_id,
                "ticketId": "",
                "ticketField": "",
                "receptionTitle": reception_title,
                "salesType": "抽選",
                "notes": "hands-form synthetic session",
            }
        ],
    }


class HandsFormLotteryExecutor:
    """使用服务器端 Playwright 提交 Hands 公开抽选表单。"""

    def execute(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        execution_id = str(payload.get("executionId") or payload.get("scheduleId") or "unknown")
        values = resolve_hands_execution_input(payload)
        missing = [key for key in ("eventUrl", "email", "fullName", "furigana") if not values.get(key)]
        if missing:
            return self.fail(f"Hands 抽票缺少必要字段: {', '.join(missing)}")

        try:
            values["eventUrl"] = normalize_hands_event_url(values["eventUrl"])
        except ValueError as exc:
            return self.fail(str(exc))

        try:
            from playwright.sync_api import sync_playwright
        except Exception as exc:
            return self.fail(f"Playwright 不可用: {exc}")

        timeout_ms = max(_env_int("HANDS_FORM_BROWSER_TIMEOUT_MS", DEFAULT_BROWSER_TIMEOUT_MS), 10000)
        headless = bool_value(env_text("HANDS_FORM_BROWSER_HEADLESS", "true"))
        proxy_key = f"hands-form:{payload.get('accountId') or values['email']}"
        proxy = get_proxy(proxy_key, platform_code="hands-form")
        browser = None
        context = None
        page = None
        submitted = False
        _log(
            f"[hands-form] START executionId={execution_id}, accountId={payload.get('accountId')}, "
            f"proxy={proxy.masked() if proxy else '-'}, headless={headless}, dryRun={values['dryRun']}"
        )
        try:
            launch_options: Dict[str, Any] = {"headless": headless}
            browser_path = _browser_executable_path()
            if browser_path:
                launch_options["executable_path"] = browser_path
            if proxy:
                launch_options["proxy"] = proxy.playwright_proxy()

            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(**launch_options)
                context = browser.new_context(
                    user_agent=DEFAULT_HEADERS["User-Agent"],
                    locale="ja-JP",
                    timezone_id="Asia/Tokyo",
                    viewport={"width": 1365, "height": 900},
                    extra_http_headers={"Accept-Language": DEFAULT_HEADERS["Accept-Language"]},
                )
                page = context.new_page()
                page.set_default_timeout(timeout_ms)
                response = page.goto(values["eventUrl"], wait_until="domcontentloaded", timeout=timeout_ms)
                page.wait_for_timeout(1000)
                initial_state = _read_browser_state(page)
                initial_stage = classify_hands_page(initial_state)
                if initial_stage != "entry":
                    return self._failure_from_state(
                        f"Hands 页面未进入填写页: {initial_stage}",
                        initial_stage,
                        initial_state,
                        page,
                        execution_id,
                        http_status=response.status if response else None,
                    )

                _fill_hands_entry_form(page, values)
                _submit_hands_form(page, confirm=False)
                submitted = True
                stage, state = _wait_for_page_stage(
                    page,
                    ("confirm", "success", "failed", "captcha_required"),
                    timeout_ms,
                )
                if stage == "confirm":
                    if values["dryRun"]:
                        return self._dry_run_result(state, page, execution_id)
                    _submit_hands_form(page, confirm=True)
                    stage, state = _wait_for_page_stage(
                        page,
                        ("success", "failed", "captcha_required", "entry"),
                        timeout_ms,
                    )

                if stage == "success":
                    body_text = str(state.get("bodyText") or "")
                    order_id = extract_hands_order_number(body_text)
                    _log(f"[hands-form] DONE executionId={execution_id}, orderId={order_id or '-'}")
                    return {
                        "success": True,
                        "status": "submitted",
                        "executionStatus": "submitted",
                        "paymentStatus": "not_required",
                        "message": f"Hands 表单提交成功，受付番号 {order_id}" if order_id else "Hands 表单提交成功",
                        "orderId": order_id,
                        "requestUrl": values["eventUrl"],
                        "resultUrl": state.get("url") or page.url,
                        "rawResult": {
                            "source": "hands-form-playwright",
                            "stage": stage,
                            "url": state.get("url") or page.url,
                            "bodyText": _compact_text(body_text, 1200),
                        },
                    }

                message = {
                    "failed": "Hands 页面校验失败，未完成提交",
                    "captcha_required": "Hands 出现交互式 reCAPTCHA，需要人工处理或验证码服务",
                    "entry": "Hands 确认提交后返回填写页，可能存在字段校验错误",
                }.get(stage, f"Hands 表单提交后页面状态未识别: {stage}")
                return self._failure_from_state(message, stage, state, page, execution_id)
        except Exception as exc:
            if proxy and not submitted:
                mark_proxy_failed(proxy, reason=f"Hands 浏览器执行异常: {exc}", key=proxy_key, platform_code="hands-form")
            artifacts = _save_failure_artifacts(page, execution_id) if page is not None else {}
            return self.fail(
                f"Hands 浏览器执行异常: {exc}",
                request_url=values["eventUrl"],
                result_url=str(getattr(page, "url", "") or ""),
                raw_result={"source": "hands-form-playwright", "artifacts": artifacts},
            )
        finally:
            if context is not None:
                try:
                    context.close()
                except Exception:
                    pass
            if browser is not None:
                try:
                    browser.close()
                except Exception:
                    pass

    def _dry_run_result(self, state: Dict[str, Any], page: Any, execution_id: str) -> Dict[str, Any]:
        artifacts = _save_failure_artifacts(page, execution_id)
        _log(f"[hands-form] DRY-RUN executionId={execution_id}, stage=confirm, finalSubmit=false")
        return {
            "success": False,
            "status": "blocked",
            "executionStatus": "blocked",
            "paymentStatus": "not_required",
            "errorCode": "HANDS_DRY_RUN_CONFIRM_READY",
            "message": "Hands Dry-run 已到达最终确认页，未执行最终提交",
            "requestUrl": state.get("url") or page.url,
            "resultUrl": state.get("url") or page.url,
            "rawResult": {
                "source": "hands-form-playwright",
                "stage": "confirm",
                "dryRun": True,
                "finalSubmit": False,
                "url": state.get("url") or page.url,
                "bodyText": _compact_text(state.get("bodyText"), 2000),
                "artifacts": artifacts,
            },
        }

    def _failure_from_state(
        self,
        message: str,
        stage: str,
        state: Dict[str, Any],
        page: Any,
        execution_id: str,
        http_status: Optional[int] = None,
    ) -> Dict[str, Any]:
        artifacts = _save_failure_artifacts(page, execution_id)
        return self.fail(
            message,
            http_status=http_status,
            request_url=str(state.get("url") or ""),
            result_url=str(state.get("url") or ""),
            raw_result={
                "source": "hands-form-playwright",
                "stage": stage,
                "url": state.get("url") or "",
                "bodyText": _compact_text(state.get("bodyText"), 2000),
                "artifacts": artifacts,
            },
        )

    @staticmethod
    def fail(
        message: str,
        *,
        http_status: Optional[int] = None,
        request_url: str = "",
        result_url: str = "",
        raw_result: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        return {
            "success": False,
            "status": "failed",
            "executionStatus": "failed",
            "paymentStatus": "not_required",
            "message": message,
            "httpStatus": http_status,
            "requestUrl": request_url,
            "resultUrl": result_url,
            "rawResult": raw_result or {"source": "hands-form-playwright"},
        }
