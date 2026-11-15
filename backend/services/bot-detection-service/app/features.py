"""Tính feature cho một lần thêm giỏ (D39).

Mọi cửa sổ thời gian tính theo ``occurredAt`` của bản tin, không theo đồng hồ máy: cùng một code chạy
online (Redis) và khi tái hiện log offline (bộ nhớ) cho ra cùng feature, và Kafka giao trễ không làm
sai số liệu. Mỗi lần thêm giỏ có một khóa riêng nên bản tin phát lại không bị đếm hai lần.
"""
from __future__ import annotations

import math
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any, Mapping, Protocol

MINUTE_MS = 60_000
TEN_MINUTES_MS = 10 * MINUTE_MS
HOUR_MS = 60 * MINUTE_MS
DAY_MS = 24 * HOUR_MS

PREV_ADD_CAP_SECONDS = 3600.0
VIEW_GAP_CAP_SECONDS = 1800.0
ACCOUNT_AGE_CAP_HOURS = 24.0 * 365

# Thứ tự cố định: model được huấn luyện với đúng thứ tự này (lưu trong model_meta.json)
FEATURE_NAMES: tuple[str, ...] = (
    "buyer_adds_1m",
    "buyer_adds_10m",
    "device_adds_1m",
    "secs_since_prev_add",
    "device_accounts_24h",
    "account_age_hours",
    "viewed_event",
    "secs_since_view",
    "quantity",
    "item_count",
    "buyer_events_1h",
    "has_device",
)


class InvalidCartEvent(ValueError):
    """Bản tin không đúng schema cart.item_added — bỏ qua, không retry."""


@dataclass(frozen=True)
class CartEvent:
    order_id: str
    buyer_id: str
    event_id: str
    item_count: int
    quantity: int
    occurred_at_ms: int
    device_hash: str | None
    event_viewed_at_ms: int | None

    @property
    def dedup_key(self) -> str:
        # Order không gửi id riêng cho từng lần thêm giỏ; bộ này đủ phân biệt hai lần thêm trong cùng Order
        return f"{self.order_id}:{self.occurred_at_ms}:{self.item_count}:{self.quantity}"


@dataclass(frozen=True)
class WindowCounts:
    buyer_adds_1m: int
    buyer_adds_10m: int
    device_adds_1m: int
    prev_add_ms: int | None
    device_accounts_24h: int
    buyer_events_1h: int


class FeatureStore(Protocol):
    async def observe(self, event: CartEvent) -> WindowCounts:
        """Ghi lần thêm giỏ rồi trả số liệu các cửa sổ tính tới thời điểm của nó (gồm chính nó)."""


def parse_instant_ms(value: Any) -> int | None:
    if value is None:
        return None
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        # Jackson có thể ghi Instant dạng số giây (có phần thập phân) nếu cấu hình khác mặc định
        return int(float(value) * 1000)
    if isinstance(value, str) and value.strip():
        text = value.strip().replace("Z", "+00:00")
        try:
            parsed = datetime.fromisoformat(text)
        except ValueError as exc:
            raise InvalidCartEvent("thời điểm không đúng ISO-8601") from exc
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=timezone.utc)
        return int(parsed.timestamp() * 1000)
    raise InvalidCartEvent("thời điểm không hợp lệ")


def _uuid(payload: Mapping[str, Any], name: str) -> str:
    value = payload.get(name)
    try:
        return str(uuid.UUID(str(value)))
    except (ValueError, TypeError) as exc:
        raise InvalidCartEvent(f"{name} không phải UUID") from exc


def _positive_int(payload: Mapping[str, Any], name: str) -> int:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise InvalidCartEvent(f"{name} phải là số nguyên dương")
    return value


def parse_cart_event(payload: Mapping[str, Any]) -> CartEvent:
    if not isinstance(payload, Mapping):
        raise InvalidCartEvent("payload phải là JSON object")
    occurred = parse_instant_ms(payload.get("occurredAt"))
    if occurred is None:
        raise InvalidCartEvent("thiếu occurredAt")
    device = payload.get("deviceHash")
    if device is not None and (not isinstance(device, str) or not device or len(device) > 128):
        raise InvalidCartEvent("deviceHash không hợp lệ")
    return CartEvent(
        order_id=_uuid(payload, "orderId"),
        buyer_id=_uuid(payload, "buyerId"),
        event_id=_uuid(payload, "eventId"),
        item_count=_positive_int(payload, "itemCount"),
        quantity=_positive_int(payload, "quantity"),
        occurred_at_ms=occurred,
        device_hash=device,
        event_viewed_at_ms=parse_instant_ms(payload.get("eventViewedAt")),
    )


def compute_features(
        event: CartEvent, counts: WindowCounts, account_created_at_ms: int | None
) -> dict[str, float | None]:
    """``account_age_hours`` là None khi không biết tuổi tài khoản; model tự điền giá trị thay thế."""
    if counts.prev_add_ms is None:
        since_prev = PREV_ADD_CAP_SECONDS
    else:
        since_prev = min(PREV_ADD_CAP_SECONDS, max(0.0, (event.occurred_at_ms - counts.prev_add_ms) / 1000))

    viewed = event.event_viewed_at_ms is not None and event.event_viewed_at_ms <= event.occurred_at_ms
    since_view = (
        min(VIEW_GAP_CAP_SECONDS, (event.occurred_at_ms - event.event_viewed_at_ms) / 1000) if viewed else 0.0
    )

    if account_created_at_ms is None:
        age_hours: float | None = None
    else:
        age_hours = min(ACCOUNT_AGE_CAP_HOURS, max(0.0, (event.occurred_at_ms - account_created_at_ms) / HOUR_MS))

    has_device = event.device_hash is not None
    return {
        "buyer_adds_1m": float(counts.buyer_adds_1m),
        "buyer_adds_10m": float(counts.buyer_adds_10m),
        "device_adds_1m": float(counts.device_adds_1m if has_device else 0),
        "secs_since_prev_add": round(since_prev, 3),
        "device_accounts_24h": float(counts.device_accounts_24h if has_device else 1),
        "account_age_hours": None if age_hours is None else round(age_hours, 3),
        "viewed_event": 1.0 if viewed else 0.0,
        "secs_since_view": round(since_view, 3),
        "quantity": float(event.quantity),
        "item_count": float(event.item_count),
        "buyer_events_1h": float(counts.buyer_events_1h),
        "has_device": 1.0 if has_device else 0.0,
    }


class MemoryFeatureStore:
    """Cài đặt trong bộ nhớ, cùng ngữ nghĩa với RedisFeatureStore — dùng cho test và tái hiện dữ liệu."""

    def __init__(self) -> None:
        self._buyer_adds: dict[str, dict[str, int]] = {}
        self._device_adds: dict[str, dict[str, int]] = {}
        self._device_buyers: dict[str, dict[str, int]] = {}
        self._buyer_events: dict[str, dict[str, int]] = {}

    @staticmethod
    def _count(series: dict[str, int], low: int, high: int) -> int:
        return sum(1 for ts in series.values() if low <= ts <= high)

    @staticmethod
    def _touch_max(series: dict[str, int], member: str, ts: int) -> None:
        series[member] = max(ts, series.get(member, ts))

    async def observe(self, event: CartEvent) -> WindowCounts:
        t = event.occurred_at_ms
        buyer = self._buyer_adds.setdefault(event.buyer_id, {})
        buyer.setdefault(event.dedup_key, t)
        earlier = [ts for key, ts in buyer.items() if ts < t and key != event.dedup_key]
        self._touch_max(self._buyer_events.setdefault(event.buyer_id, {}), event.event_id, t)

        device_adds_1m = 0
        device_accounts = 1
        if event.device_hash is not None:
            device = self._device_adds.setdefault(event.device_hash, {})
            device.setdefault(event.dedup_key, t)
            device_adds_1m = self._count(device, t - MINUTE_MS, t)
            accounts = self._device_buyers.setdefault(event.device_hash, {})
            self._touch_max(accounts, event.buyer_id, t)
            device_accounts = self._count(accounts, t - DAY_MS, t)

        return WindowCounts(
            buyer_adds_1m=self._count(buyer, t - MINUTE_MS, t),
            buyer_adds_10m=self._count(buyer, t - TEN_MINUTES_MS, t),
            device_adds_1m=device_adds_1m,
            prev_add_ms=max(earlier) if earlier else None,
            device_accounts_24h=device_accounts,
            buyer_events_1h=self._count(self._buyer_events[event.buyer_id], t - HOUR_MS, t),
        )


class RedisFeatureStore:
    """Cửa sổ trượt bằng sorted set (score = epoch ms). Key hết hạn sau 25 giờ không hoạt động."""

    KEY_TTL_SECONDS = 25 * 3600

    def __init__(self, redis_client: Any, prefix: str = "bot:") -> None:
        self._redis = redis_client
        self._prefix = prefix

    def _key(self, *parts: str) -> str:
        return self._prefix + ":".join(parts)

    async def observe(self, event: CartEvent) -> WindowCounts:
        t = event.occurred_at_ms
        buyer_adds = self._key("buyer", event.buyer_id, "adds")
        buyer_events = self._key("buyer", event.buyer_id, "events")
        pipe = self._redis.pipeline(transaction=True)
        # NX: phát lại giữ mốc cũ; GT: event xuất hiện lại thì cập nhật lần gần nhất
        pipe.zadd(buyer_adds, {event.dedup_key: t}, nx=True)
        pipe.zadd(buyer_events, {event.event_id: t}, gt=True)
        pipe.zadd(buyer_events, {event.event_id: t}, nx=True)
        pipe.zremrangebyscore(buyer_adds, "-inf", t - DAY_MS)
        pipe.zremrangebyscore(buyer_events, "-inf", t - DAY_MS)
        pipe.zcount(buyer_adds, t - MINUTE_MS, t)
        pipe.zcount(buyer_adds, t - TEN_MINUTES_MS, t)
        pipe.zrevrangebyscore(buyer_adds, f"({t}", "-inf", start=0, num=1, withscores=True)
        pipe.zcount(buyer_events, t - HOUR_MS, t)
        pipe.expire(buyer_adds, self.KEY_TTL_SECONDS)
        pipe.expire(buyer_events, self.KEY_TTL_SECONDS)
        if event.device_hash is not None:
            device_adds = self._key("device", event.device_hash, "adds")
            device_buyers = self._key("device", event.device_hash, "buyers")
            pipe.zadd(device_adds, {event.dedup_key: t}, nx=True)
            pipe.zadd(device_buyers, {event.buyer_id: t}, gt=True)
            pipe.zadd(device_buyers, {event.buyer_id: t}, nx=True)
            pipe.zremrangebyscore(device_adds, "-inf", t - DAY_MS)
            pipe.zremrangebyscore(device_buyers, "-inf", t - 2 * DAY_MS)
            pipe.zcount(device_adds, t - MINUTE_MS, t)
            pipe.zcount(device_buyers, t - DAY_MS, t)
            pipe.expire(device_adds, self.KEY_TTL_SECONDS)
            pipe.expire(device_buyers, self.KEY_TTL_SECONDS)
        results = await pipe.execute()

        previous = results[7]
        prev_ms = int(previous[0][1]) if previous else None
        device_adds_1m, device_accounts = 0, 1
        if event.device_hash is not None:
            device_adds_1m, device_accounts = int(results[16]), int(results[17])
        return WindowCounts(
            buyer_adds_1m=int(results[5]),
            buyer_adds_10m=int(results[6]),
            device_adds_1m=device_adds_1m,
            prev_add_ms=prev_ms,
            device_accounts_24h=device_accounts,
            buyer_events_1h=int(results[8]),
        )


def to_vector(features: Mapping[str, float | None], imputations: Mapping[str, float]) -> list[float]:
    """Vector đầu vào model: log1p cho đại lượng đếm/thời gian để đuôi dài không lấn át cây."""
    vector: list[float] = []
    for name in FEATURE_NAMES:
        value = features.get(name)
        if value is None:
            value = imputations.get(name, 0.0)
        value = float(value)
        if not math.isfinite(value):
            raise InvalidCartEvent(f"{name} không hữu hạn")
        if name not in {"viewed_event", "has_device"}:
            value = math.log1p(max(0.0, value))
        vector.append(value)
    return vector
