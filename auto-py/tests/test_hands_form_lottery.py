import json
import sys
import unittest
from pathlib import Path


AUTO_PY_DIR = Path(__file__).resolve().parents[1]
if str(AUTO_PY_DIR) not in sys.path:
    sys.path.insert(0, str(AUTO_PY_DIR))

from hands_form_lottery import (  # noqa: E402
    classify_hands_page,
    extract_hands_order_number,
    resolve_hands_execution_input,
)


class HandsFormLotteryTest(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
