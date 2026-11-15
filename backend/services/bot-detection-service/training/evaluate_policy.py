"""So sánh chính sách chặn theo tài khoản (D46) trên dữ liệu đã chấm điểm; ghi training/reports/policy.json.

    python -I training/evaluate_policy.py

- current (D41): chặn ngay ở lần HIGH đầu.
- d46: chặn khi HIGH kèm luật tay (``rule_matched``) hoặc khi tài khoản có HIGH lần thứ hai.

Vòng A/B được thu khi chưa chặn ai nên phản ánh đúng cả hai chính sách. Vòng C thu khi đang chặn theo D41: bot bị
dừng sau lần HIGH đầu nên không có lần HIGH thứ hai — số bot bắt được của d46 trên C bị đánh giá thấp.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.features import FEATURE_NAMES, rule_matched  # noqa: E402
from app.model import load_model  # noqa: E402

REPORTS = ROOT / "training" / "reports"


def accounts(frame: pd.DataFrame, high: float, policy: str) -> dict[str, dict[str, object]]:
    out: dict[str, dict[str, object]] = {}
    rows = []
    for _, group in frame.sort_values("occurred_at_ms").groupby("buyer_id"):
        hi = (group["anomaly_score"] >= high).to_numpy()
        rule = group.apply(lambda r: rule_matched(r.to_dict()), axis=1).to_numpy()
        blocked = hi if policy == "current" else (hi & rule) | (hi.cumsum() >= 2)
        rows.append({"kind": group["kind"].iloc[0], "blocked": bool(blocked.any()),
                     "adds_before_block": int(blocked.argmax()) if blocked.any() else None})
    table = pd.DataFrame(rows)
    for kind, group in table.groupby("kind"):
        before = group["adds_before_block"].dropna()
        out[kind] = {"blocked": int(group["blocked"].sum()), "accounts": int(len(group)),
                     "median_adds_before_block": None if before.empty else float(before.median())}
    return out


def main() -> None:
    model = load_model(ROOT / "model")
    if model is None:
        raise SystemExit("Thiếu model/")
    scored = pd.read_csv(REPORTS / "scored.csv")
    frames = {
        "validation_A": scored[scored["split"] == "validation"].copy(),
        "test_B": scored[scored["split"] == "test"].copy(),
    }
    live_c = ROOT / "training" / "data" / "features_c.csv"
    if live_c.is_file():
        c = pd.read_csv(live_c)
        c["anomaly_score"] = [
            model.score({name: (None if pd.isna(row[name]) else row[name]) for name in FEATURE_NAMES}).anomaly_score
            for _, row in c.iterrows()
        ]
        frames["replay_C_collected_under_current_policy"] = c
    result = {name: {policy: accounts(frame, model.high_threshold, policy) for policy in ("current", "d46")}
              for name, frame in frames.items()}
    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "policy.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    for name, policies in result.items():
        for policy, kinds in policies.items():
            print(name, policy, {k: f"{v['blocked']}/{v['accounts']}" for k, v in kinds.items()})


if __name__ == "__main__":
    main()
