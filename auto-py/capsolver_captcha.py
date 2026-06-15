from ticket_runtime import env_text

from captcha_base import TaskPollingCaptchaVerifier


class CapSolverCaptchaVerifier(TaskPollingCaptchaVerifier):
    """CapSolver 打码平台接口"""

    provider_name = "CapSolver"

    def __init__(
        self,
        api_key: str,
        *,
        poll_attempts: int = 60,
        poll_interval_seconds: int = 3,
        request_timeout_seconds: int = 30,
    ) -> None:
        super().__init__(
            api_key,
            create_url="https://api.capsolver.com/createTask",
            result_url="https://api.capsolver.com/getTaskResult",
            poll_attempts=poll_attempts,
            poll_interval_seconds=poll_interval_seconds,
            request_timeout_seconds=request_timeout_seconds,
        )

    def solve_recaptcha_v2(
        self,
        site_key,
        page_url,
        enterprise=False,
        action=None,
        is_invisible=False,
        enterprise_payload_s=None,
        recaptcha_data_s_value=None,
    ):
        task_config = {
            "type": "ReCaptchaV2EnterpriseTaskProxyLess" if enterprise else "ReCaptchaV2TaskProxyLess",
            "websiteURL": page_url,
            "websiteKey": site_key,
        }
        if is_invisible:
            task_config["isInvisible"] = True
        if action:
            task_config["pageAction"] = action
        if enterprise:
            task_config["isEnterprise"] = True
            if enterprise_payload_s:
                task_config["enterprisePayload"] = {"s": enterprise_payload_s}
        elif recaptcha_data_s_value:
            task_config["recaptchaDataSValue"] = recaptcha_data_s_value

        task_id = self._submit_task(task_config, submit_error_label="提交验证码失败")
        return self._poll_task_result(
            task_id,
            ready_log_label="任务",
            poll_error_label="获取结果失败",
            timeout_label="识别超时",
        )

    def solve_recaptcha_v3_enterprise(self, site_key, page_url, page_action=None):
        task_config = {
            "type": "ReCaptchaV3EnterpriseTaskProxyLess",
            "websiteURL": page_url,
            "websiteKey": site_key,
        }
        if page_action:
            task_config["pageAction"] = page_action

        task_id = self._submit_task(task_config, submit_error_label="提交验证码失败")
        return self._poll_task_result(
            task_id,
            ready_log_label="V3 Enterprise",
            poll_error_label="获取结果失败",
            timeout_label="识别超时",
        )

    def solve_hcaptcha(self, site_key, page_url):
        task_id = self._submit_task(
            {
                "type": "HCaptchaTaskProxyLess",
                "websiteURL": page_url,
                "websiteKey": site_key,
            },
            submit_error_label="提交 hCaptcha 失败",
        )
        return self._poll_task_result(
            task_id,
            ready_log_label="hCaptcha",
            poll_error_label="获取 hCaptcha 结果失败",
            timeout_label="hCaptcha 识别超时",
        )


class CaptchaSolver(CapSolverCaptchaVerifier):
    """兼容旧调用面的 CapSolver 验证器别名。"""


def capsolver_api_key() -> str:
    return env_text("CAPSOLVER_API_KEY")
