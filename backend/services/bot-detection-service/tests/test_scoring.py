from __future__ import annotations

import asyncio
import uuid

import numpy as np
import pytest

from app.features import FEATURE_NAMES, InvalidCartEvent, MemoryFeatureStore
from app.model import ModelBundle, load_model
from app.scoring import evaluate
from tests.conftest import BOT_FEATURES, cart_payload, human_features


async def known_account(_: str) -> int:
    return 1_700_000_000_000


def test_without_model_scores_are_unscored_but_features_are_published(tmp_path) -> None:
    assert load_model(tmp_path) is None
    payload = cart_payload()
    result = asyncio.run(evaluate(payload, MemoryFeatureStore(), known_account, None, now_ms=lambda: 0))
    assert result["riskLevel"] == "UNSCORED" and result["anomalyScore"] is None
    assert set(result["features"]) == set(FEATURE_NAMES)
    assert result["orderId"] == payload["orderId"] and result["deviceHash"] == payload["deviceHash"]
    assert result["occurredAt"] == "2026-11-15T10:00:00.000Z"


def test_replayed_message_keeps_its_evaluation_id(tmp_path) -> None:
    store = MemoryFeatureStore()
    payload = cart_payload()
    first = asyncio.run(evaluate(payload, store, known_account, None))
    again = asyncio.run(evaluate(payload, store, known_account, None))
    assert first["evaluationId"] == again["evaluationId"]
    assert first["features"] == again["features"]


def test_account_lookup_failure_propagates_without_touching_windows() -> None:
    store = MemoryFeatureStore()

    async def broken(_: str) -> int:
        raise RuntimeError("user-service down")

    payload = cart_payload()
    with pytest.raises(RuntimeError):
        asyncio.run(evaluate(payload, store, broken, None))
    ok = asyncio.run(evaluate(payload, store, known_account, None))
    assert ok["features"]["buyer_adds_1m"] == 1.0


def test_invalid_payload_raises_invalid_event() -> None:
    with pytest.raises(InvalidCartEvent):
        asyncio.run(evaluate({"orderId": "nope"}, MemoryFeatureStore(), known_account, None))


def test_model_separates_obvious_bot_from_typical_human(model_dir) -> None:
    model = load_model(model_dir)
    assert model is not None
    rng = np.random.default_rng(99)
    human_levels = [model.score(human_features(rng)).risk_level for _ in range(200)]
    assert human_levels.count("HIGH") <= 4
    bot = model.score(BOT_FEATURES)
    assert bot.risk_level == "HIGH"
    # account_age_hours không biết → dùng giá trị thay thế trong meta
    assert model.score({**BOT_FEATURES, "account_age_hours": None}).risk_level == "HIGH"


def test_model_meta_must_match_service_features(model_dir) -> None:
    import joblib
    import json

    meta = json.loads((model_dir / "model_meta.json").read_text(encoding="utf-8"))
    estimator = joblib.load(model_dir / "isolation_forest.joblib")
    with pytest.raises(ValueError):
        ModelBundle(estimator, {**meta, "features": list(FEATURE_NAMES)[:-1]})
    with pytest.raises(ValueError):
        ModelBundle(estimator, {**meta, "thresholds": {"medium": 0.9, "high": 0.1}})


def test_unknown_account_uses_imputation_in_end_to_end_evaluation(model_dir) -> None:
    async def missing(_: str) -> None:
        return None

    model = load_model(model_dir)
    store = MemoryFeatureStore()
    buyer, device, ev, order = (str(uuid.uuid4()) for _ in range(4))
    results = []
    for i in range(12):
        payload = cart_payload(orderId=order, buyerId=buyer, eventId=ev, deviceHash=device, eventViewedAt=None,
                               occurredAt=f"2026-11-15T10:00:{i:02d}Z", quantity=8, itemCount=3)
        results.append(asyncio.run(evaluate(payload, store, missing, model)))
    assert results[-1]["features"]["account_age_hours"] is None
    assert results[-1]["riskLevel"] == "HIGH"
    assert results[-1]["modelVersion"] == "iforest-test"
