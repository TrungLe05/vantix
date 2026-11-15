"""Tái hiện log cart.item_added của một vòng qua đúng code feature của service → CSV.

    python -I training/build_dataset.py A B

Dùng MemoryFeatureStore (cùng ngữ nghĩa RedisFeatureStore, có test so khớp) và tuổi tài khoản từ
accounts_<round>.csv thay cho user-service. Nhãn (kind/label) chỉ được gắn vào CSV để đánh giá.
"""
from __future__ import annotations

import asyncio
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.features import FEATURE_NAMES, MemoryFeatureStore, compute_features, parse_cart_event, parse_instant_ms  # noqa: E402

DATA = ROOT / "training" / "data"


async def build(round_name: str) -> int:
    rnd = round_name.lower()
    with (DATA / f"accounts_{rnd}.csv").open(encoding="utf-8") as f:
        accounts = {row["buyer_id"]: row for row in csv.DictReader(f)}
    events = [parse_cart_event(json.loads(line))
              for line in (DATA / f"cart_events_{rnd}.jsonl").read_text(encoding="utf-8").splitlines() if line]
    # Thứ tự thời gian như Kafka giao (một partition); tie-break ổn định theo khóa bản tin
    events.sort(key=lambda e: (e.occurred_at_ms, e.dedup_key))

    store = MemoryFeatureStore()
    out = DATA / f"features_{rnd}.csv"
    with out.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["round", "buyer_id", "kind", "label", "order_id", "occurred_at_ms",
                                               *FEATURE_NAMES])
        writer.writeheader()
        for event in events:
            account = accounts[event.buyer_id]
            counts = await store.observe(event)
            features = compute_features(event, counts, parse_instant_ms(account["created_at"]))
            writer.writerow({"round": round_name.upper(), "buyer_id": event.buyer_id, "kind": account["kind"],
                             "label": account["label"], "order_id": event.order_id,
                             "occurred_at_ms": event.occurred_at_ms, **features})
    return len(events)


def main() -> None:
    for round_name in sys.argv[1:] or ["A", "B"]:
        count = asyncio.run(build(round_name))
        print(f"round {round_name.upper()}: {count} rows -> training/data/features_{round_name.lower()}.csv")


if __name__ == "__main__":
    main()
