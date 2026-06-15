from captcha_base import HCaptchaVerifier
from capsolver_captcha import CapSolverCaptchaVerifier, CaptchaSolver, capsolver_api_key


def build_jump_shop_hcaptcha_verifier() -> HCaptchaVerifier:
    api_key = capsolver_api_key()
    if not api_key:
        raise RuntimeError("缺少 CAPSOLVER_API_KEY，Jump Shop hCaptcha 使用 CapSolver")
    return CapSolverCaptchaVerifier(api_key)


__all__ = [
    "HCaptchaVerifier",
    "CapSolverCaptchaVerifier",
    "CaptchaSolver",
    "capsolver_api_key",
    "build_jump_shop_hcaptcha_verifier",
]
