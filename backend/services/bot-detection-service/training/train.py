"""Huấn luyện Isolation Forest, chọn ngưỡng, đánh giá; ghi model/ và training/reports/metrics.json.

    python -I training/train.py

- Train/validation: vòng A, chia **theo tài khoản** (75/25, phân tầng theo kind).
- Test: vòng B — tài khoản khác, lần chạy k6 khác (cùng kịch bản, seed khác).
- Model học trên tập train **không nhãn** (trộn bot và người). Nhãn chỉ dùng để (1) lấy mẫu tập train sao
  cho bot chiếm ~10% lần thêm giỏ — kịch bản k6 cho bot sinh nhiều bản tin hơn người dùng, trong khi Isolation
  Forest giả định bất thường là thiểu số; (2) chọn ngưỡng trên validation (≤ 1% lần thêm giỏ của người dùng bị
  HIGH); (3) đo trên test. Model học trên tỉ lệ tự nhiên cũng được đánh giá để so sánh (metrics.json).
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from sklearn.ensemble import IsolationForest
from sklearn.metrics import average_precision_score, roc_auc_score

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.features import FEATURE_NAMES, to_vector  # noqa: E402
from app.model import META_FILE, MODEL_FILE  # noqa: E402

DATA = ROOT / "training" / "data"
REPORTS = ROOT / "training" / "reports"
MODEL_DIR = ROOT / "model"
SEED = 42
HUMAN_HIGH_RATE = 0.01
HUMAN_MEDIUM_RATE = 0.05
TRAIN_BOT_SHARE = 0.10
ESTIMATOR = {"n_estimators": 300, "max_samples": "auto", "contamination": "auto", "random_state": SEED}


def load(round_name: str) -> pd.DataFrame:
    return pd.read_csv(DATA / f"features_{round_name}.csv")


def vectors(frame: pd.DataFrame, imputations: dict[str, float]) -> np.ndarray:
    records = frame[list(FEATURE_NAMES)].astype(object).where(frame[list(FEATURE_NAMES)].notna(), None)
    return np.array([to_vector(row, imputations) for row in records.to_dict("records")])


def split_accounts(frame: pd.DataFrame, validation_share: float) -> tuple[pd.DataFrame, pd.DataFrame]:
    rng = np.random.default_rng(SEED)
    validation_ids: set[str] = set()
    accounts = frame.groupby("buyer_id")["kind"].first()
    for _, ids in accounts.groupby(accounts):
        shuffled = rng.permutation(ids.index.to_numpy())
        validation_ids.update(shuffled[: max(1, round(len(shuffled) * validation_share))])
    mask = frame["buyer_id"].isin(validation_ids)
    return frame[~mask].copy(), frame[mask].copy()


def sample_bot_share(frame: pd.DataFrame, share: float) -> pd.DataFrame:
    """Giữ mọi lần thêm giỏ của người dùng, lấy mẫu bot (phân tầng theo kind) để bot ≈ ``share``."""
    humans, bots = frame[frame["label"] == 0], frame[frame["label"] == 1]
    target = int(round(len(humans) * share / (1 - share)))
    if target >= len(bots):
        return frame.copy()
    fraction = target / len(bots)
    sampled = bots.groupby("kind", group_keys=False).apply(
        lambda g: g.sample(n=max(1, round(len(g) * fraction)), random_state=SEED))
    return pd.concat([humans, sampled]).sort_values("occurred_at_ms").copy()


def fit(frame: pd.DataFrame, imputations: dict[str, float]) -> IsolationForest:
    return IsolationForest(**ESTIMATOR).fit(vectors(frame, imputations))


def evaluate_variant(model: IsolationForest, validation: pd.DataFrame, test: pd.DataFrame,
                     imputations: dict[str, float]) -> tuple[dict[str, object], float, float]:
    validation = validation.copy()
    test = test.copy()
    validation["anomaly_score"] = -model.score_samples(vectors(validation, imputations))
    test["anomaly_score"] = -model.score_samples(vectors(test, imputations))
    human_val = validation.loc[validation["label"] == 0, "anomaly_score"]
    high = float(np.quantile(human_val, 1 - HUMAN_HIGH_RATE))
    medium = float(np.quantile(human_val, 1 - HUMAN_MEDIUM_RATE))
    test["level"] = levels(test["anomaly_score"].to_numpy(), medium, high)
    return {"thresholds": {"medium": round(medium, 6), "high": round(high, 6)},
            "test": event_metrics(test, "level"), "test_accounts": account_metrics(test, "level"),
            "roc_auc": round(float(roc_auc_score(test["label"], test["anomaly_score"])), 4)}, medium, high


def event_metrics(frame: pd.DataFrame, level_column: str) -> dict[str, object]:
    flagged = frame[level_column] == "HIGH"
    bots = frame["label"] == 1
    tp = int((flagged & bots).sum())
    fp = int((flagged & ~bots).sum())
    fn = int((~flagged & bots).sum())
    precision = tp / (tp + fp) if tp + fp else 0.0
    recall = tp / (tp + fn) if tp + fn else 0.0
    f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
    by_kind = {kind: round(float(group[level_column].eq("HIGH").mean()), 4)
               for kind, group in frame.groupby("kind")}
    return {"events": int(len(frame)), "bot_events": int(bots.sum()), "tp": tp, "fp": fp, "fn": fn,
            "precision": round(precision, 4), "recall": round(recall, 4), "f1": round(f1, 4),
            "high_rate_by_kind": by_kind}


def account_metrics(frame: pd.DataFrame, level_column: str) -> dict[str, object]:
    """Tài khoản bị chặn nếu có ít nhất một lần HIGH; đếm số lần thêm giỏ thành công trước lần HIGH đầu."""
    rows = []
    for buyer, group in frame.sort_values("occurred_at_ms").groupby("buyer_id"):
        high = group[level_column].eq("HIGH").to_numpy()
        rows.append({"buyer_id": buyer, "kind": group["kind"].iloc[0], "label": int(group["label"].iloc[0]),
                     "adds": len(group), "flagged": bool(high.any()),
                     "adds_before_flag": int(np.argmax(high)) if high.any() else None})
    accounts = pd.DataFrame(rows)
    by_kind = {}
    for kind, group in accounts.groupby("kind"):
        before = group["adds_before_flag"].dropna()
        by_kind[kind] = {"accounts": int(len(group)), "flagged_share": round(float(group["flagged"].mean()), 4),
                         "median_adds_before_flag": None if before.empty else float(before.median())}
    humans = accounts[accounts["label"] == 0]
    return {"humans_flagged": int(humans["flagged"].sum()), "humans": int(len(humans)), "by_kind": by_kind}


def levels(scores: np.ndarray, medium: float, high: float) -> np.ndarray:
    return np.where(scores >= high, "HIGH", np.where(scores >= medium, "MEDIUM", "LOW"))


def baseline_levels(frame: pd.DataFrame) -> np.ndarray:
    # Luật tay để so sánh: dồn dập theo tài khoản hoặc nhiều tài khoản trên một thiết bị
    rule = (frame["buyer_adds_1m"] >= 4) | (frame["device_accounts_24h"] >= 4)
    return np.where(rule, "HIGH", "LOW")


def main() -> None:
    pool, test = load("a"), load("b")
    train_full, validation = split_accounts(pool, validation_share=0.25)
    train = sample_bot_share(train_full, TRAIN_BOT_SHARE)
    imputations = {"account_age_hours": float(train["account_age_hours"].median())}

    model = fit(train, imputations)
    natural, _, _ = evaluate_variant(fit(train_full, imputations), validation, test, imputations)

    for frame in (train, validation, test):
        frame["anomaly_score"] = -model.score_samples(vectors(frame, imputations))

    human_val = validation.loc[validation["label"] == 0, "anomaly_score"]
    high = float(np.quantile(human_val, 1 - HUMAN_HIGH_RATE))
    medium = float(np.quantile(human_val, 1 - HUMAN_MEDIUM_RATE))
    for frame in (train, validation, test):
        frame["level"] = levels(frame["anomaly_score"].to_numpy(), medium, high)
        frame["baseline_level"] = baseline_levels(frame)

    metrics = {
        "natural_mix_variant": natural,
        "thresholds": {"medium": round(medium, 6), "high": round(high, 6)},
        "validation": {"model": event_metrics(validation, "level"),
                       "roc_auc": round(float(roc_auc_score(validation["label"], validation["anomaly_score"])), 4)},
        "test": {
            "model": event_metrics(test, "level"),
            "model_accounts": account_metrics(test, "level"),
            "baseline_rule": event_metrics(test, "baseline_level"),
            "baseline_accounts": account_metrics(test, "baseline_level"),
            "roc_auc": round(float(roc_auc_score(test["label"], test["anomaly_score"])), 4),
            "average_precision": round(float(average_precision_score(test["label"], test["anomaly_score"])), 4),
        },
    }
    training = {
        "train_events_before_sampling": int(len(train_full)),
        "train_bot_share_before_sampling": round(float(train_full["label"].mean()), 4),
        "train_events": int(len(train)), "validation_events": int(len(validation)), "test_events": int(len(test)),
        "train_bot_share": round(float(train["label"].mean()), 4),
        "train_accounts": int(train["buyer_id"].nunique()), "test_accounts": int(test["buyer_id"].nunique()),
        "estimator": ESTIMATOR,
        "train_bot_share_target": TRAIN_BOT_SHARE,
        "threshold_rule": f"high = quantile {1 - HUMAN_HIGH_RATE} / medium = quantile {1 - HUMAN_MEDIUM_RATE} "
                          "of human anomaly scores on validation",
    }

    trained_at = datetime.now(tz=timezone.utc)
    MODEL_DIR.mkdir(exist_ok=True)
    joblib.dump(model, MODEL_DIR / MODEL_FILE, compress=3)
    (MODEL_DIR / META_FILE).write_text(json.dumps({
        "version": f"iforest-{trained_at:%Y%m%d}",
        "features": list(FEATURE_NAMES),
        "thresholds": metrics["thresholds"],
        "imputations": imputations,
        "trained_at": trained_at.isoformat(timespec="seconds"),
        "training": training,
        "metrics": {"test": {k: metrics["test"][k] for k in ("roc_auc", "average_precision")} |
                    {"precision": metrics["test"]["model"]["precision"],
                     "recall": metrics["test"]["model"]["recall"]}},
    }, indent=2), encoding="utf-8")

    REPORTS.mkdir(parents=True, exist_ok=True)
    (REPORTS / "metrics.json").write_text(json.dumps({"training": training, **metrics}, indent=2), encoding="utf-8")
    pd.concat([train.assign(split="train"), validation.assign(split="validation"), test.assign(split="test")]) \
        .to_csv(REPORTS / "scored.csv", index=False)
    print(json.dumps(metrics["test"]["model"], indent=2))
    print("roc_auc", metrics["test"]["roc_auc"])


if __name__ == "__main__":
    main()
