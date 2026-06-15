from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Optional


HERE = Path(__file__).resolve().parent
if str(HERE) not in sys.path:
    sys.path.insert(0, str(HERE))

from jump_shop_quick_checkout import fetch_jump_shop_product_info  # noqa: E402


DEFAULT_PRODUCT_URL = "https://jumpshop-benelic.com/products/00048632"


def parse_args(argv: Optional[list[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Parse Jump Shop product add-to-cart parameters")
    parser.add_argument(
        "url",
        nargs="?",
        default=DEFAULT_PRODUCT_URL,
        help="Jump Shop product URL. If omitted, DEFAULT_PRODUCT_URL in this file is used.",
    )
    parser.add_argument("--json-only", action="store_true", help="Only print JSON config snippet")
    return parser.parse_args(argv)


def main(argv: Optional[list[str]] = None) -> int:
    args = parse_args(argv)
    product_url = str(args.url or "").strip()
    if not product_url:
        print("Product URL is empty", file=sys.stderr)
        return 1

    info = fetch_jump_shop_product_info(product_url)
    snippet = {
        "productUrl": info["productUrl"],
        "variantId": info["variantId"],
        "productId": info["productId"],
        "sectionId": info["sectionId"],
    }

    if args.json_only:
        print(json.dumps(snippet, ensure_ascii=False, indent=2))
        return 0

    print("Product info")
    print(f"productUrl: {info['productUrl']}")
    if info.get("title") and "\ufffd" not in str(info.get("title")):
        print(f"title: {info['title']}")
    print(f"variantId / id: {info['variantId']}")
    print(f"productId / product-id: {info['productId']}")
    print(f"sectionId / section-id: {info['sectionId']}")
    print()
    print("Config snippet")
    print(json.dumps(snippet, ensure_ascii=False, indent=2))
    print()
    print("Add-to-cart payload")
    print("form_type = product")
    print("utf8 = ✓")
    print("quantity = your config quantity")
    print(f"id = {info['variantId']}")
    print(f"product-id = {info['productId']}")
    print(f"section-id = {info['sectionId']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
