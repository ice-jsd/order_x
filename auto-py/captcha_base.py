import time
from abc import ABC, abstractmethod
from typing import Any, Dict, Sequence

import requests

from ticket_runtime import get_logger


LOGGER = get_logger("ticket.captcha")
print = LOGGER.print


class HCaptchaVerifier(ABC):
    provider_name = "captcha"

    @abstractmethod
    def solve_hcaptcha(self, site_key: str, page_url: str) -> str:
        raise NotImplementedError


class TaskPollingCaptchaVerifier(HCaptchaVerifier):
    provider_name = "captcha"

    def __init__(
        self,
        api_key: str,
        *,
        create_url: str,
        result_url: str,
        poll_attempts: int = 60,
        poll_interval_seconds: int = 3,
        request_timeout_seconds: int = 30,
    ) -> None:
        self.api_key = str(api_key or "").strip()
        self.create_url = create_url
        self.result_url = result_url
        self.poll_attempts = max(int(poll_attempts or 0), 1)
        self.poll_interval_seconds = max(float(poll_interval_seconds or 0), 0)
        self.request_timeout_seconds = max(int(request_timeout_seconds or 0), 1)

    def _post_json(self, url: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        response = requests.post(url, json=payload, timeout=self.request_timeout_seconds)
        return response.json()

    def _submit_task(self, task_config: Dict[str, Any], *, submit_error_label: str) -> str:
        result = self._post_json(
            self.create_url,
            {
                "clientKey": self.api_key,
                "task": task_config,
            },
        )
        if result.get("errorId") != 0:
            raise Exception(f"{submit_error_label}: {result.get('errorDescription') or result.get('errorCode') or result}")
        task_id = str(result.get("taskId") or "").strip()
        if not task_id:
            raise Exception(f"{submit_error_label}: 响应缺少 taskId")
        return task_id

    def _poll_task_result(
        self,
        task_id: str,
        *,
        ready_log_label: str,
        poll_error_label: str,
        timeout_label: str,
        solution_keys: Sequence[str] = ("gRecaptchaResponse",),
    ) -> str:
        print(f"[{self.provider_name}] {ready_log_label} 任务ID: {task_id}, 等待识别...")
        for _ in range(self.poll_attempts):
            if self.poll_interval_seconds > 0:
                time.sleep(self.poll_interval_seconds)
            result = self._post_json(
                self.result_url,
                {
                    "clientKey": self.api_key,
                    "taskId": task_id,
                },
            )
            if result.get("errorId") != 0:
                raise Exception(f"{poll_error_label}: {result.get('errorDescription') or result.get('errorCode') or result}")
            status = str(result.get("status") or "").strip().lower()
            if status == "ready":
                solution = result.get("solution") or {}
                token = next(
                    (
                        str(solution.get(key) or "").strip()
                        for key in solution_keys
                        if str(solution.get(key) or "").strip()
                    ),
                    "",
                )
                if not token:
                    raise Exception(f"{poll_error_label}: 响应缺少 {'/'.join(solution_keys)}")
                print(f"[{self.provider_name}] {ready_log_label} 识别成功!")
                return token
            if status != "processing":
                raise Exception(f"{poll_error_label}: {result}")
        raise Exception(timeout_label)
