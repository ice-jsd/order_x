import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
PROJECT_DIR = HERE.parent
if str(PROJECT_DIR) not in sys.path:
    sys.path.insert(0, str(PROJECT_DIR))

import jump_shop_quick_checkout as quick  # noqa: E402


class EmptyLocator:
    def count(self):
        return 0


class MissingFieldPage:
    def locator(self, selector):
        return EmptyLocator()

    def get_by_label(self, label, exact=False):
        return EmptyLocator()


class FakeLocator:
    def __init__(self, count=1, value=""):
        self._count = count
        self.value = value
        self.filled = []
        self.selected = []

    @property
    def first(self):
        return self

    def count(self):
        return self._count

    def fill(self, value, timeout=0):
        self.value = str(value)
        self.filled.append(self.value)

    def input_value(self, timeout=0):
        return self.value

    def evaluate(self, script):
        return None

    def select_option(self, value=None, label=None):
        chosen = value if value is not None else label
        self.value = str(chosen)
        self.selected.append(self.value)


class SelectorPage:
    def __init__(self):
        self.last_name = FakeLocator()

    def locator(self, selector):
        if selector == "input[autocomplete='family-name']":
            return self.last_name
        return EmptyLocator()

    def get_by_label(self, label, exact=False):
        return EmptyLocator()


class CardNamePage:
    def __init__(self):
        self.card_name = FakeLocator()

    def locator(self, selector):
        if selector == "input[autocomplete='cc-name']":
            return self.card_name
        return EmptyLocator()

    def get_by_label(self, label, exact=False):
        return EmptyLocator()


class IframeDiagnosticsPage:
    def evaluate(self, script, limit=0):
        return [
            {
                "id": "card-fields-expiry-abc",
                "name": "",
                "title": "Expiration date",
                "src": "https://checkout.pci.shopifyinc.com/...",
                "hidden": False,
                "width": 300,
                "height": 42,
            }
        ]


class CheckoutFillHelpersTest(unittest.TestCase):
    def test_cloudflare_rate_limit_result_is_detected(self):
        result = {
            "ok": False,
            "status": 429,
            "text": "Access denied | jumpshop-benelic.com used Cloudflare to restrict access",
        }

        self.assertTrue(quick.is_cloudflare_rate_limited_result(result))
        self.assertIn("cloudflare_rate_limited", quick.summarize_add_to_cart_error(result))

    def test_required_checkout_text_field_raises_with_diagnostics_when_missing(self):
        with self.assertRaisesRegex(RuntimeError, "shipping.lastName"):
            quick.fill_required_text_field(
                MissingFieldPage(),
                "shipping.lastName",
                ["input[name='lastName']"],
                "Yamada",
                labels=["姓"],
            )

    def test_required_checkout_text_field_uses_autocomplete_selector(self):
        page = SelectorPage()

        filled = quick.fill_required_text_field(
            page,
            "shipping.lastName",
            ["input[autocomplete='family-name']"],
            "Yamada",
            labels=["姓"],
        )

        self.assertTrue(filled)
        self.assertEqual(page.last_name.input_value(), "Yamada")

    def test_fill_shipping_address_fails_fast_when_required_fields_are_missing(self):
        profile = {
            "lastName": "Yamada",
            "firstName": "Taro",
            "phone": "09012345678",
            "postalCode": "1000001",
            "province": "東京都",
            "city": "千代田区",
            "address1": "千代田1-1",
        }

        with self.assertRaisesRegex(RuntimeError, "shipping.lastName"):
            quick.fill_shipping_address(MissingFieldPage(), profile)

    def test_card_frame_diagnostics_reports_available_iframes_without_values(self):
        diagnostics = quick.card_frame_diagnostics(IframeDiagnosticsPage())

        self.assertIn("card-fields-expiry-abc", diagnostics)
        self.assertIn("Expiration date", diagnostics)
        self.assertNotIn("cardNumber", diagnostics)

    def test_card_holder_name_falls_back_to_regular_cc_name_input(self):
        page = CardNamePage()

        filled = quick.fill_card_holder_name(page, {"cardHolderName": "TARO YAMADA"})

        self.assertTrue(filled)
        self.assertEqual(page.card_name.input_value(), "TARO YAMADA")


if __name__ == "__main__":
    unittest.main()
