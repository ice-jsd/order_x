import json
import os
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


AUTO_PY_DIR = Path(__file__).resolve().parents[1]
if str(AUTO_PY_DIR) not in sys.path:
    sys.path.insert(0, str(AUTO_PY_DIR))

from hands_form_lottery import (  # noqa: E402
    HandsFormLotteryExecutor,
    _capture_hands_submit_request,
    classify_hands_page,
    extract_hands_order_number,
    extract_hands_recaptcha_config,
    resolve_hands_execution_input,
)


class HandsFormLotteryTest(unittest.TestCase):
    def test_submit_diagnostic_keeps_only_field_names_and_token_lengths(self):
        request = SimpleNamespace(
            method="POST",
            url="https://event.hands.net/validate",
            post_data="mail=person%40example.com&g-recaptcha-response=secret-token",
        )
        diagnostics = []

        _capture_hands_submit_request(request, diagnostics)

        self.assertEqual(diagnostics[0]["path"], "/validate")
        self.assertEqual(
            diagnostics[0]["recaptchaTokenLengths"],
            {"g-recaptcha-response": [12]},
        )
        self.assertNotIn("person@example.com", json.dumps(diagnostics))
        self.assertNotIn("secret-token", json.dumps(diagnostics))

    def test_resolve_execution_input_from_java_payload(self):
        payload = {
            "email": "person@example.com",
            "purchaseQuantity": 2,
            "accountInfo": json.dumps(
                {
                    "familyName": "山田",
                    "givenName": "太郎",
                    "furigana": "ヤマダタロウ",
                },
                ensure_ascii=False,
            ),
            "taskOptions": json.dumps({"eventUrl": "https://event.hands.net/segment/123"}),
        }

        result = resolve_hands_execution_input(payload)

        self.assertEqual("山田太郎", result["fullName"])
        self.assertEqual("ヤマダタロウ", result["furigana"])
        self.assertEqual("person@example.com", result["email"])
        self.assertEqual(2, result["quantity"])
        self.assertTrue(result["dryRun"])

    def test_dry_run_can_be_enabled_by_environment(self):
        payload = {
            "email": "person@example.com",
            "accountInfo": json.dumps({"fullName": "山田太郎", "furigana": "ヤマダタロウ"}, ensure_ascii=False),
            "taskOptions": json.dumps({"eventUrl": "https://event.hands.net/segment/123"}),
        }
        with patch.dict(os.environ, {"HANDS_FORM_DRY_RUN": "true"}):
            result = resolve_hands_execution_input(payload)
        self.assertTrue(result["dryRun"])

    def test_global_dry_run_cannot_be_disabled_by_payload(self):
        payload = {
            "email": "person@example.com",
            "handsFormDryRun": False,
            "accountInfo": json.dumps({"fullName": "山田太郎", "furigana": "ヤマダタロウ"}, ensure_ascii=False),
            "taskOptions": json.dumps({"eventUrl": "https://event.hands.net/segment/123"}),
        }
        with patch.dict(os.environ, {"HANDS_FORM_DRY_RUN": "true"}):
            result = resolve_hands_execution_input(payload)
        self.assertTrue(result["dryRun"])

    def test_classify_confirm_page(self):
        state = {
            "bodyText": "以下の内容で送信します。ご確認のうえ、「この内容で送信する」ボタンを押してください。",
            "hasForm": True,
            "formAction": "https://event.hands.net/process",
            "hasEditableFields": False,
            "hasConfirmButton": True,
        }
        self.assertEqual("confirm", classify_hands_page(state))

    def test_classify_entry_page_even_when_submit_button_has_name(self):
        state = {
            "bodyText": "お客様情報を入力してください。",
            "hasForm": True,
            "formAction": "https://event.hands.net/segment/123",
            "hasEditableFields": True,
            "hasConfirmButton": True,
        }
        self.assertEqual("entry", classify_hands_page(state))

    def test_classify_captcha_failure_before_generic_challenge(self):
        state = {
            "bodyText": "Google reCAPTCHAの認証に失敗しました。",
            "captchaChallengeVisible": True,
        }
        self.assertEqual("failed", classify_hands_page(state))

    def test_extract_order_number(self):
        self.assertEqual("12345678", extract_hands_order_number("受付番号：12345678"))

    def test_extract_enterprise_recaptcha_config(self):
        page_text = """
        <script src="https://www.google.com/recaptcha/enterprise.js?render=site-key-123"></script>
        <script>
          grecaptcha.enterprise.execute("site-key-123", {action: 'submit'}).then(() => {});
        </script>
        """

        self.assertEqual(
            {"siteKey": "site-key-123", "action": "submit"},
            extract_hands_recaptcha_config(page_text),
        )

    def test_dry_run_result_is_blocked_without_final_submission(self):
        executor = HandsFormLotteryExecutor()
        state = {"url": "https://event.hands.net/process", "bodyText": "確認内容"}
        with patch("hands_form_lottery._save_failure_artifacts", return_value={"screenshot": "confirm.png"}):
            result = executor._dry_run_result(state, SimpleNamespace(url=state["url"]), "execution-1")

        self.assertFalse(result["success"])
        self.assertEqual("blocked", result["executionStatus"])
        self.assertEqual("HANDS_DRY_RUN_CONFIRM_READY", result["errorCode"])
        self.assertFalse(result["rawResult"]["finalSubmit"])

    def test_recaptcha_rejection_has_specific_error_code(self):
        executor = HandsFormLotteryExecutor()
        state = {
            "url": "https://event.hands.net/segment/123",
            "bodyText": "Google reCAPTCHAの認証に失敗しました。",
        }
        with patch("hands_form_lottery._save_failure_artifacts", return_value={}):
            result = executor._failure_from_state(
                "Hands 页面校验失败",
                "failed",
                state,
                SimpleNamespace(url=state["url"]),
                "execution-1",
            )

        self.assertEqual("failed", result["executionStatus"])
        self.assertEqual("HANDS_RECAPTCHA_REJECTED", result["errorCode"])

    def test_field_validation_error_is_reported_without_captcha_code(self):
        executor = HandsFormLotteryExecutor()
        state = {
            "url": "https://event.hands.net/segment/123",
            "bodyText": "入力内容を確認してください。",
            "formErrors": ["フリガナはカタカナで入力してください。"],
        }
        with patch("hands_form_lottery._save_failure_artifacts", return_value={}):
            result = executor._failure_from_state(
                "Hands 页面校验失败",
                "entry",
                state,
                SimpleNamespace(url=state["url"]),
                "execution-1",
            )

        self.assertIn("フリガナ", result["message"])
        self.assertNotIn("errorCode", result)


if __name__ == "__main__":
    unittest.main()
