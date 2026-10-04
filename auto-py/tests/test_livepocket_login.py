import sys
import unittest
from pathlib import Path
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from capsolver_captcha import CapSolverCaptchaVerifier  # noqa: E402
from livepocket_login import LivePocketLogin, extract_turnstile_config  # noqa: E402


TURNSTILE_HTML = """
<form id="login-form" action="/login" method="post">
  <input type="hidden" name="authenticity_token" value="csrf-token" />
  <div
    class="turnstile-widget"
    data-controller="turnstile"
    data-turnstile-sitekey-value="0x4AAAAAACo6pHzHzXIg0NZK"
    data-turnstile-action-value="login"
    data-turnstile-cdata-value="account-123"
  ></div>
</form>
<script src="https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit"></script>
"""


class CapSolverTurnstileTests(unittest.TestCase):
    def test_solve_enterprise_v3_session_returns_full_solution(self):
        solver = CapSolverCaptchaVerifier("test-key", poll_interval_seconds=0)
        solver._submit_task = Mock(return_value="task-session")
        solver._poll_task_solution = Mock(
            return_value={
                "gRecaptchaResponse": "enterprise-token",
                "recaptcha-ca-t": "session-cookie",
            }
        )

        solution = solver.solve_recaptcha_v3_enterprise(
            "site-key",
            "https://event.hands.net/segment/123",
            page_action="submit",
            user_agent="test-user-agent",
            session_mode=True,
            return_solution=True,
        )

        self.assertEqual(solution["gRecaptchaResponse"], "enterprise-token")
        self.assertEqual(solution["recaptcha-ca-t"], "session-cookie")
        self.assertTrue(solver._submit_task.call_args.args[0]["isSession"])

    def test_solve_enterprise_v3_high_score_uses_m1_task(self):
        solver = CapSolverCaptchaVerifier("test-key", poll_interval_seconds=0)
        solver._submit_task = Mock(return_value="task-m1")
        solver._poll_task_result = Mock(return_value="enterprise-token")

        token = solver.solve_recaptcha_v3_enterprise(
            "site-key",
            "https://event.hands.net/segment/123",
            page_action="submit",
            high_score=True,
            user_agent="test-user-agent",
        )

        self.assertEqual(token, "enterprise-token")
        solver._submit_task.assert_called_once_with(
            {
                "type": "ReCaptchaV3EnterpriseM1TaskProxyLess",
                "websiteURL": "https://event.hands.net/segment/123",
                "websiteKey": "site-key",
                "pageAction": "submit",
                "userAgent": "test-user-agent",
            },
            submit_error_label="提交验证码失败",
        )

    def test_solve_turnstile_uses_expected_task_and_token_field(self):
        solver = CapSolverCaptchaVerifier("test-key", poll_interval_seconds=0)
        solver._submit_task = Mock(return_value="task-1")
        solver._poll_task_result = Mock(return_value="turnstile-token")

        token = solver.solve_turnstile(
            "site-key",
            "https://livepocket.jp/login",
            action="login",
            cdata="account-123",
        )

        self.assertEqual(token, "turnstile-token")
        solver._submit_task.assert_called_once_with(
            {
                "type": "AntiTurnstileTaskProxyLess",
                "websiteURL": "https://livepocket.jp/login",
                "websiteKey": "site-key",
                "metadata": {"action": "login", "cdata": "account-123"},
            },
            submit_error_label="提交 Turnstile 失败",
        )
        self.assertEqual(
            solver._poll_task_result.call_args.kwargs["solution_keys"],
            ("token",),
        )

    def test_poll_turnstile_result_reads_solution_token(self):
        solver = CapSolverCaptchaVerifier("test-key", poll_interval_seconds=0)
        solver._post_json = Mock(
            return_value={
                "errorId": 0,
                "status": "ready",
                "solution": {"token": "turnstile-token"},
            }
        )

        token = solver._poll_task_result(
            "task-1",
            ready_log_label="Turnstile",
            poll_error_label="获取 Turnstile 结果失败",
            timeout_label="Turnstile 识别超时",
            solution_keys=("token",),
        )

        self.assertEqual(token, "turnstile-token")


class LivePocketTurnstileTests(unittest.TestCase):
    def test_extract_turnstile_config_from_current_login_markup(self):
        config = extract_turnstile_config(TURNSTILE_HTML)

        self.assertTrue(config["detected"])
        self.assertEqual(config["siteKey"], "0x4AAAAAACo6pHzHzXIg0NZK")
        self.assertEqual(config["action"], "login")
        self.assertEqual(config["cdata"], "account-123")

    @patch("livepocket_login.time.sleep", return_value=None)
    def test_login_submits_turnstile_token_once(self, _sleep):
        captcha_solver = Mock()
        captcha_solver.solve_turnstile.return_value = "turnstile-token"
        client = LivePocketLogin(captcha_solver)
        client.turnstile_config = {
            "detected": True,
            "siteKey": "site-key",
            "action": "login",
            "cdata": "",
        }
        client.get_authenticity_token = Mock(return_value=("auth-token", "csrf-token"))
        response = Mock(status_code=303, headers={"Location": "/my_top"}, text="", url=client.login_url)
        client.session.post = Mock(return_value=response)

        success = client.login("user@example.com", "password")

        self.assertTrue(success)
        captcha_solver.solve_turnstile.assert_called_once_with(
            site_key="site-key",
            page_url="https://livepocket.jp/login",
            action="login",
            cdata=None,
        )
        client.session.post.assert_called_once()
        form_data = client.session.post.call_args.kwargs["data"]
        self.assertEqual(form_data["cf-turnstile-response"], "turnstile-token")
        self.assertNotIn("g-recaptcha-response", form_data)
        self.assertEqual(client.session.post.call_args.kwargs["headers"]["Referer"], client.login_url)

    @patch("livepocket_login.time.sleep", return_value=None)
    def test_login_preserves_legacy_recaptcha_flow(self, _sleep):
        captcha_solver = Mock()
        captcha_solver.solve_recaptcha_v2.return_value = "recaptcha-token"
        client = LivePocketLogin(captcha_solver)
        client.get_authenticity_token = Mock(return_value=("auth-token", "csrf-token"))
        first_response = Mock(
            status_code=200,
            headers={},
            text='<input name="authenticity_token" value="next-auth-token">',
            url=client.login_url,
        )
        success_response = Mock(
            status_code=303,
            headers={"Location": "/my_top"},
            text="",
            url=client.login_url,
        )
        responses = iter([first_response, success_response])
        posted_forms = []

        def post_with_snapshot(*_args, **kwargs):
            posted_forms.append(dict(kwargs["data"]))
            return next(responses)

        client.session.post = Mock(side_effect=post_with_snapshot)

        success = client.login("user@example.com", "password")

        self.assertTrue(success)
        self.assertEqual(client.session.post.call_count, 2)
        first_form, second_form = posted_forms
        self.assertEqual(first_form["g-recaptcha-response-data[login]"], "recaptcha-token")
        self.assertEqual(first_form["g-recaptcha-response"], "")
        self.assertEqual(second_form["authenticity_token"], "next-auth-token")
        self.assertEqual(second_form["g-recaptcha-response"], "recaptcha-token")
        self.assertNotIn("g-recaptcha-response-data[login]", second_form)


if __name__ == "__main__":
    unittest.main()
