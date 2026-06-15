from __future__ import annotations

import sys
from pathlib import Path


HERE = Path(__file__).resolve().parent

if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from jump_shop_quick_checkout import main  # noqa: E402


if __name__ == "__main__":
    args = sys.argv[1:]
    if "--config" not in args:
        args = ["--config", str(HERE / "quick_checkout.config.json"), *args]
    raise SystemExit(main(args))
