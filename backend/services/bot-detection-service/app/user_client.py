"""Tuổi tài khoản từ user-service ``/internal/users/{id}/account`` (role INTERNAL, D39)."""
from __future__ import annotations

import logging
from typing import Any

import httpx

from app.features import parse_instant_ms

log = logging.getLogger(__name__)

# Service gọi service: danh tính kỹ thuật, giống notification-service (D34)
SERVICE_USER_ID = "00000000-0000-0000-0000-000000000000"
_MISSING = "missing"


class UserServiceUnavailable(RuntimeError):
    """Lỗi tạm thời: bản tin được retry thay vì chấm điểm với feature sai."""


class AccountAgeClient:
    def __init__(self, http: httpx.AsyncClient, base_url: str, redis_client: Any, cache_ttl_seconds: int) -> None:
        self._http = http
        self._base_url = base_url
        self._redis = redis_client
        self._ttl = cache_ttl_seconds

    async def created_at_ms(self, buyer_id: str) -> int | None:
        cache_key = f"bot:account:{buyer_id}"
        cached = await self._redis.get(cache_key)
        if cached is not None:
            text = cached.decode() if isinstance(cached, bytes) else str(cached)
            return None if text == _MISSING else int(text)

        try:
            response = await self._http.get(
                f"{self._base_url}/internal/users/{buyer_id}/account",
                headers={"X-User-Id": SERVICE_USER_ID, "X-User-Role": "INTERNAL"},
            )
        except httpx.HTTPError as exc:
            raise UserServiceUnavailable(type(exc).__name__) from exc

        if response.status_code == 404:
            # Tài khoản đã xóa/không có: chấm điểm với giá trị thay thế, cache ngắn để không hỏi dồn dập
            await self._redis.set(cache_key, _MISSING, ex=300)
            return None
        if response.status_code != 200:
            raise UserServiceUnavailable(f"HTTP {response.status_code}")
        try:
            created = parse_instant_ms(response.json()["result"]["createdAt"])
        except (KeyError, TypeError, ValueError) as exc:
            raise UserServiceUnavailable("response không đúng định dạng") from exc
        if created is None:
            raise UserServiceUnavailable("thiếu createdAt")
        await self._redis.set(cache_key, str(created), ex=self._ttl)
        return created
