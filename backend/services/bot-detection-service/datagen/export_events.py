"""Xuất bản tin cart.item_added của một vòng từ outbox ``orders_service_e2e`` ra JSONL.

Outbox giữ đúng payload đã gửi Kafka nên đây là log đầy đủ, không phụ thuộc consumer có chạy hay không.
Chỉ giữ bản tin của tài khoản datagen của vòng đó (lọc theo accounts_<round>.csv).

    python -I datagen/export_events.py --round A
"""
from __future__ import annotations

import argparse
import csv
import json
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "training" / "data"
DATABASE = "orders_service_e2e"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--round", required=True, choices=["A", "B", "C"])
    args = parser.parse_args()
    rnd = args.round.lower()
    with (DATA / f"accounts_{rnd}.csv").open(encoding="utf-8") as f:
        buyers = {row["buyer_id"] for row in csv.DictReader(f)}

    result = subprocess.run(
        ["docker", "exec", "-i", "postgres", "sh", "-c",
         f'psql -v ON_ERROR_STOP=1 -q -t -A -U "$POSTGRES_USER" -d {DATABASE}'],
        input="SELECT payload FROM order_event_outbox WHERE topic = 'cart.item_added' AND payload IS NOT NULL "
              "ORDER BY occurred_at, created_at;",
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql lỗi: {result.stderr.strip()[:300]}")

    kept = []
    for line in result.stdout.splitlines():
        if not line.strip():
            continue
        payload = json.loads(line)
        if payload.get("buyerId") in buyers:
            kept.append(payload)
    out = DATA / f"cart_events_{rnd}.jsonl"
    out.write_text("".join(json.dumps(p, separators=(",", ":")) + "\n" for p in kept), encoding="utf-8")
    print(f"Vòng {args.round}: {len(kept)} bản tin → {out.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
