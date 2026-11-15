"""Một bản tin cart.item_added → một bản tin bot_score.evaluated."""
from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any, Awaitable, Callable, Mapping

from app.features import FeatureStore, compute_features, parse_cart_event
from app.model import ModelBundle

# Trả epoch ms lúc tạo tài khoản, hoặc None khi user không tồn tại. Lỗi tạm thời phải ném ra để retry.
AccountLookup = Callable[[str], Awaitable[int | None]]


def _iso(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=timezone.utc).isoformat(timespec="milliseconds").replace(
        "+00:00", "Z")


async def evaluate(
        payload: Mapping[str, Any],
        store: FeatureStore,
        account_lookup: AccountLookup,
        model: ModelBundle | None,
        now_ms: Callable[[], int] | None = None,
) -> dict[str, Any]:
    event = parse_cart_event(payload)
    # Hỏi user-service trước khi ghi cửa sổ: lỗi tạm thời → retry mà không để lại dấu vết dở dang
    created_at = await account_lookup(event.buyer_id)
    counts = await store.observe(event)
    features = compute_features(event, counts, created_at)

    if model is None:
        anomaly, level, version = None, "UNSCORED", None
    else:
        result = model.score(features)
        anomaly, level, version = result.anomaly_score, result.risk_level, model.version

    clock = now_ms or (lambda: int(datetime.now(tz=timezone.utc).timestamp() * 1000))
    return {
        # Định danh xác định theo bản tin nguồn: phát lại cho ra cùng evaluationId
        "evaluationId": str(uuid.uuid5(uuid.NAMESPACE_URL, "vantix-cart:" + event.dedup_key)),
        "orderId": event.order_id,
        "buyerId": event.buyer_id,
        "eventId": event.event_id,
        "deviceHash": event.device_hash,
        "anomalyScore": anomaly,
        "riskLevel": level,
        "modelVersion": version,
        "features": features,
        "occurredAt": _iso(event.occurred_at_ms),
        "evaluatedAt": _iso(clock()),
    }
