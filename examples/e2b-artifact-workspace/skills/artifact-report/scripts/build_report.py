#!/usr/bin/env python3
"""Build a small deterministic report using only the Python standard library."""

import argparse
import json
from decimal import Decimal
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    source = Path(args.input)
    destination = Path(args.output)
    orders = json.loads(source.read_text(encoding="utf-8"))
    total = sum((Decimal(order["amount"]) for order in orders), Decimal("0"))
    average = total / len(orders) if orders else Decimal("0")
    report = {
        "averageAmount": format(average, ".2f"),
        "orderCount": len(orders),
        "totalAmount": format(total, ".2f"),
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(
        json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
