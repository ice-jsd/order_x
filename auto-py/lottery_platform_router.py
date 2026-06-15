from functools import lru_cache
from typing import Any, Callable


def normalize_platform_code(platform_code: Any) -> str:
    text = str(platform_code or "livepocket").strip().lower()
    return text or "livepocket"


def resolve_event_parser(platform_code: Any) -> Callable[..., dict]:
    code = normalize_platform_code(platform_code)
    if code == "livepocket":
        from livepocket_lottery import fetch_lottery_event_info

        return fetch_lottery_event_info
    if code == "hands-form":
        from hands_form_lottery import fetch_hands_event_info

        return fetch_hands_event_info
    raise ValueError(f"unsupported lottery platform: {platform_code}")


@lru_cache(maxsize=None)
def resolve_executor(platform_code: Any):
    code = normalize_platform_code(platform_code)
    if code == "livepocket":
        from livepocket_lottery import LivePocketLotteryExecutor

        return LivePocketLotteryExecutor()
    if code in {"jump-shop", "jump-shop-online"}:
        from jump_shop import JumpShopExecutor

        return JumpShopExecutor()
    raise ValueError(f"unsupported lottery platform: {platform_code}")
