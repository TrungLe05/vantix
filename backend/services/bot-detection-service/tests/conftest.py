from __future__ import annotations

import json
import sys
import uuid
from pathlib import Path

import joblib
import numpy as np
import pytest
from sklearn.ensemble import IsolationForest

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.features import FEATURE_NAMES, to_vector  # noqa: E402
from app.model import META_FILE, MODEL_FILE  # noqa: E402


def human_features(rng: np.random.Generator) -> dict[str, float]:
    return {
        "buyer_adds_1m": 1.0,
        "buyer_adds_10m": float(rng.integers(1, 3)),
        "device_adds_1m": 1.0,
        "secs_since_prev_add": float(rng.choice([3600.0, rng.uniform(40, 900)])),
        "device_accounts_24h": 1.0,
        "account_age_hours": float(rng.uniform(500, 8000)),
        "viewed_event": 1.0,
        "secs_since_view": float(rng.uniform(8, 300)),
        "quantity": float(rng.integers(1, 4)),
        "item_count": 1.0,
        "buyer_events_1h": 1.0,
        "has_device": 1.0,
    }


BOT_FEATURES = {
    "buyer_adds_1m": 12.0,
    "buyer_adds_10m": 40.0,
    "device_adds_1m": 30.0,
    "secs_since_prev_add": 0.3,
    "device_accounts_24h": 15.0,
    "account_age_hours": 0.5,
    "viewed_event": 0.0,
    "secs_since_view": 0.0,
    "quantity": 10.0,
    "item_count": 4.0,
    "buyer_events_1h": 6.0,
    "has_device": 1.0,
}


@pytest.fixture(scope="session")
def model_dir(tmp_path_factory: pytest.TempPathFactory) -> Path:
    """Model nhỏ huấn luyện trên traffic "người dùng" giả để test đường chấm điểm (không phải model thật)."""
    rng = np.random.default_rng(7)
    rows = [to_vector(human_features(rng), {}) for _ in range(400)]
    estimator = IsolationForest(n_estimators=100, random_state=7).fit(rows)
    scores = -estimator.score_samples(rows)
    directory = tmp_path_factory.mktemp("model")
    joblib.dump(estimator, directory / MODEL_FILE)
    (directory / META_FILE).write_text(json.dumps({
        "version": "iforest-test",
        "features": list(FEATURE_NAMES),
        "thresholds": {"medium": float(np.quantile(scores, 0.95)), "high": float(np.quantile(scores, 0.995))},
        "imputations": {"account_age_hours": 2000.0},
    }), encoding="utf-8")
    return directory


def cart_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "orderId": str(uuid.uuid4()),
        "buyerId": str(uuid.uuid4()),
        "eventId": str(uuid.uuid4()),
        "itemCount": 1,
        "quantity": 2,
        "occurredAt": "2026-11-15T10:00:00Z",
        "deviceHash": "a" * 64,
        "eventViewedAt": "2026-11-15T09:58:00Z",
    }
    payload.update(overrides)
    return payload
