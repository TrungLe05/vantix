from __future__ import annotations

import asyncio
import math
import os
import uuid

import pytest

from app.features import (
    FEATURE_NAMES,
    InvalidCartEvent,
    MemoryFeatureStore,
    RedisFeatureStore,
    compute_features,
    parse_cart_event,
    parse_instant_ms,
    to_vector,
)
from tests.conftest import cart_payload

T0 = parse_instant_ms("2026-11-15T10:00:00Z")


def at(seconds: float) -> str:
    from datetime import datetime, timezone
    return datetime.fromtimestamp((T0 + seconds * 1000) / 1000, tz=timezone.utc).isoformat().replace("+00:00", "Z")


def bot_burst(buyer: str, device: str | None, event: str, order: str, count: int, step: float) -> list:
    return [parse_cart_event(cart_payload(orderId=order, buyerId=buyer, eventId=event, deviceHash=device,
                                          occurredAt=at(i * step), eventViewedAt=None, quantity=1))
            for i in range(count)]


async def observe_all(store, events):
    return [await store.observe(e) for e in events]


def test_parse_rejects_bad_schema_and_accepts_java_instant_formats() -> None:
    event = parse_cart_event(cart_payload(occurredAt="2026-11-15T10:00:00.123456Z"))
    assert event.occurred_at_ms == T0 + 123
    assert parse_instant_ms(1_763_200_800.5) == 1_763_200_800_500
    for bad in ({"orderId": "x"}, {"quantity": 0}, {"quantity": True}, {"occurredAt": None},
                {"occurredAt": "hôm qua"}, {"deviceHash": ""}, {"itemCount": "1"}):
        with pytest.raises(InvalidCartEvent):
            parse_cart_event(cart_payload(**bad))
    with pytest.raises(InvalidCartEvent):
        parse_cart_event(["not", "an", "object"])  # type: ignore[arg-type]


def test_windows_count_by_message_time_and_replays_do_not_double_count() -> None:
    store = MemoryFeatureStore()
    buyer, device, ev, order = (str(uuid.uuid4()) for _ in range(4))
    events = bot_burst(buyer, device, ev, order, 5, step=2)
    counts = asyncio.run(observe_all(store, events))
    assert [c.buyer_adds_1m for c in counts] == [1, 2, 3, 4, 5]
    assert counts[0].prev_add_ms is None and counts[4].prev_add_ms == events[3].occurred_at_ms
    # Kafka phát lại bản tin thứ 3: số liệu không tăng
    replay = asyncio.run(store.observe(events[2]))
    assert replay.buyer_adds_1m == 3 and replay.buyer_adds_10m == 3
    # 2 phút sau: cửa sổ 1 phút rỗng ngoài chính nó, 10 phút vẫn còn
    later = parse_cart_event(cart_payload(orderId=order, buyerId=buyer, eventId=ev, deviceHash=device,
                                          occurredAt=at(130), quantity=3))
    c = asyncio.run(store.observe(later))
    assert (c.buyer_adds_1m, c.buyer_adds_10m, c.device_adds_1m) == (1, 6, 1)


def test_device_accounts_and_event_spread() -> None:
    store = MemoryFeatureStore()
    device = "d" * 64
    for i in range(4):
        asyncio.run(store.observe(parse_cart_event(cart_payload(deviceHash=device, occurredAt=at(i)))))
    buyer = str(uuid.uuid4())
    for i in range(3):
        c = asyncio.run(store.observe(parse_cart_event(cart_payload(buyerId=buyer, deviceHash=device,
                                                                    occurredAt=at(10 + i)))))
    assert c.device_accounts_24h == 5
    assert c.buyer_events_1h == 3
    # Không có thiết bị: không gộp các request "không thiết bị" thành một thiết bị chung
    lone = asyncio.run(store.observe(parse_cart_event(cart_payload(deviceHash=None, occurredAt=at(20)))))
    assert lone.device_accounts_24h == 1 and lone.device_adds_1m == 0


def test_compute_features_caps_and_view_signal() -> None:
    event = parse_cart_event(cart_payload(occurredAt=at(0), eventViewedAt=at(-45)))
    store = MemoryFeatureStore()
    counts = asyncio.run(store.observe(event))
    features = compute_features(event, counts, T0 - 10 * 365 * 24 * 3_600_000)
    assert set(features) == set(FEATURE_NAMES)
    assert features["viewed_event"] == 1.0 and features["secs_since_view"] == pytest.approx(45.0)
    assert features["account_age_hours"] == 24 * 365  # trần 1 năm
    assert features["secs_since_prev_add"] == 3600.0  # lần đầu
    # Lượt xem "sau" lần thêm (đồng hồ lệch) không được tính là đã xem
    skew = parse_cart_event(cart_payload(occurredAt=at(0), eventViewedAt=at(5)))
    f = compute_features(skew, asyncio.run(MemoryFeatureStore().observe(skew)), None)
    assert f["viewed_event"] == 0.0 and f["secs_since_view"] == 0.0 and f["account_age_hours"] is None


def test_vector_uses_log1p_and_imputation() -> None:
    event = parse_cart_event(cart_payload())
    features = compute_features(event, asyncio.run(MemoryFeatureStore().observe(event)), None)
    vector = to_vector(features, {"account_age_hours": 99.0})
    assert len(vector) == len(FEATURE_NAMES)
    assert vector[FEATURE_NAMES.index("account_age_hours")] == pytest.approx(math.log1p(99.0))
    assert vector[FEATURE_NAMES.index("has_device")] == 1.0


def _redis_client():
    redis = pytest.importorskip("redis.asyncio")
    url = os.getenv("BOT_TEST_REDIS_URL", "redis://localhost:6379/14")
    client = redis.from_url(url)
    try:
        asyncio.run(client.ping())
    except Exception:
        pytest.skip("Redis không chạy")
    return url


def test_redis_store_matches_memory_store() -> None:
    import redis.asyncio as redis

    url = _redis_client()
    buyer, device, ev, order = (str(uuid.uuid4()) for _ in range(4))
    events = bot_burst(buyer, device, ev, order, 6, step=7)
    events += [parse_cart_event(cart_payload(buyerId=str(uuid.uuid4()), deviceHash=device, occurredAt=at(20)))]
    events += [events[3]]  # phát lại
    events += bot_burst(buyer, None, str(uuid.uuid4()), order, 2, step=300)

    async def run():
        client = redis.from_url(url)
        try:
            store = RedisFeatureStore(client, prefix=f"bot-test:{uuid.uuid4()}:")
            return await observe_all(store, events)
        finally:
            await client.aclose()

    assert asyncio.run(run()) == asyncio.run(observe_all(MemoryFeatureStore(), events))
