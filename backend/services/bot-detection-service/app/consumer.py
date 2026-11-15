"""Vòng consume cart.item_added → publish bot_score.evaluated (at-least-once).

Commit offset chỉ sau khi đã gửi kết quả: sập giữa chừng thì bản tin được xử lý lại, cửa sổ Redis
không đếm trùng (khóa theo bản tin) và evaluationId không đổi. Lỗi tạm thời (Redis, user-service, Kafka)
→ chờ backoff rồi thử lại đúng bản tin đó; bản tin sai schema → log và bỏ qua.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any, Callable

from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition

from app.config import Settings
from app.features import FeatureStore, InvalidCartEvent
from app.model import ModelBundle
from app.scoring import AccountLookup, evaluate

log = logging.getLogger(__name__)

MAX_BACKOFF_SECONDS = 30.0


class ScoringConsumer:
    def __init__(
            self,
            settings: Settings,
            store: FeatureStore,
            account_lookup: AccountLookup,
            model_provider: Callable[[], ModelBundle | None],
    ) -> None:
        self._settings = settings
        self._store = store
        self._account_lookup = account_lookup
        self._model_provider = model_provider
        self._consumer: AIOKafkaConsumer | None = None
        self._producer: AIOKafkaProducer | None = None
        self.running = False
        self.processed = 0

    async def start(self) -> None:
        self._consumer = AIOKafkaConsumer(
            self._settings.input_topic,
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            group_id=self._settings.consumer_group,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
        )
        self._producer = AIOKafkaProducer(
            bootstrap_servers=self._settings.kafka_bootstrap_servers,
            acks="all",
            enable_idempotence=True,
        )
        await self._producer.start()
        await self._consumer.start()
        self.running = True

    async def stop(self) -> None:
        self.running = False
        if self._consumer is not None:
            await self._consumer.stop()
        if self._producer is not None:
            await self._producer.stop()

    async def run(self) -> None:
        assert self._consumer is not None and self._producer is not None
        try:
            async for message in self._consumer:
                await self._handle_with_retry(message)
        except asyncio.CancelledError:
            raise
        except Exception:
            log.exception("Consumer dừng do lỗi không mong đợi")
            self.running = False
            raise

    async def _handle_with_retry(self, message: Any) -> None:
        assert self._consumer is not None and self._producer is not None
        delay = 1.0
        while True:
            try:
                result = await self._process(message.value)
                if result is not None:
                    await self._producer.send_and_wait(
                        self._settings.output_topic,
                        key=result["orderId"].encode(),
                        value=json.dumps(result, separators=(",", ":")).encode(),
                    )
                await self._consumer.commit({TopicPartition(message.topic, message.partition): message.offset + 1})
                self.processed += 1
                return
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # lỗi tạm thời: thử lại cùng bản tin
                # Chỉ log loại lỗi và vị trí, không log payload (buyerId, deviceHash)
                log.warning("Chấm điểm thất bại partition=%s offset=%s (%s); thử lại sau %.0fs",
                            message.partition, message.offset, type(exc).__name__, delay)
                await asyncio.sleep(delay)
                delay = min(MAX_BACKOFF_SECONDS, delay * 2)

    async def _process(self, raw: bytes | None) -> dict[str, Any] | None:
        try:
            payload = json.loads(raw) if raw else None
        except (UnicodeDecodeError, json.JSONDecodeError):
            log.error("Bỏ qua cart.item_added không phải JSON")
            return None
        try:
            return await evaluate(payload, self._store, self._account_lookup, self._model_provider())
        except InvalidCartEvent as exc:
            log.error("Bỏ qua cart.item_added sai schema: %s", exc)
            return None
