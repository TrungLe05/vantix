"""Cấu hình đọc từ biến môi trường (không có secret nào ở service này)."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

SERVICE_ROOT = Path(__file__).resolve().parent.parent


def _bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


@dataclass(frozen=True)
class Settings:
    kafka_bootstrap_servers: str
    redis_url: str
    user_service_url: str
    model_dir: Path
    consumer_enabled: bool
    consumer_group: str
    input_topic: str
    output_topic: str
    account_cache_ttl_seconds: int
    user_service_timeout_seconds: float


def load_settings() -> Settings:
    return Settings(
        kafka_bootstrap_servers=os.getenv("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
        # DB 2: tách khỏi key của order-service (DB 0) và integration test (DB 15)
        redis_url=os.getenv("REDIS_URL", "redis://localhost:6379/2"),
        user_service_url=os.getenv("USER_SERVICE_URL", "http://localhost:9004/api").rstrip("/"),
        model_dir=Path(os.getenv("BOT_MODEL_DIR", str(SERVICE_ROOT / "model"))),
        consumer_enabled=_bool("BOT_CONSUMER_ENABLED", True),
        consumer_group=os.getenv("BOT_CONSUMER_GROUP", "bot-detection-service"),
        input_topic="cart.item_added",
        output_topic="bot_score.evaluated",
        account_cache_ttl_seconds=int(os.getenv("BOT_ACCOUNT_CACHE_TTL_SECONDS", "86400")),
        user_service_timeout_seconds=float(os.getenv("BOT_USER_SERVICE_TIMEOUT_SECONDS", "3")),
    )
