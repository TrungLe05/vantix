from __future__ import annotations

import asyncio
import contextlib
import logging
from contextlib import asynccontextmanager
from typing import Any

import httpx
import redis.asyncio as redis_async
from fastapi import APIRouter, FastAPI, Header, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel

from app.config import load_settings
from app.consumer import ScoringConsumer
from app.features import FEATURE_NAMES, InvalidCartEvent, RedisFeatureStore
from app.model import ModelBundle, load_model
from app.user_client import AccountAgeClient

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("bot_detection")


class ApiError(Exception):
    """Lỗi nghiệp vụ theo dải 7xxx, trả cùng shape ApiResponse với service Java."""

    def __init__(self, code: int, message: str, status: int) -> None:
        super().__init__(message)
        self.code, self.message, self.status = code, message, status


ADMIN_REQUIRED = (7001, "Chỉ ADMIN được dùng API này", 403)
MODEL_UNAVAILABLE = (7002, "Chưa có model bot-detection", 503)
INVALID_FEATURES = (7003, "Feature không hợp lệ", 400)


class ScoreRequest(BaseModel):
    features: dict[str, float | None]


def ok(result: Any) -> dict[str, Any]:
    return {"code": 200, "result": result}


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    app.state.settings = settings
    app.state.model = load_model(settings.model_dir)
    app.state.consumer = None
    app.state.consumer_task = None
    redis_client = redis_async.from_url(settings.redis_url)
    http = httpx.AsyncClient(timeout=settings.user_service_timeout_seconds)
    app.state.redis = redis_client
    if settings.consumer_enabled:
        accounts = AccountAgeClient(http, settings.user_service_url, redis_client,
                                    settings.account_cache_ttl_seconds)
        consumer = ScoringConsumer(settings, RedisFeatureStore(redis_client), accounts.created_at_ms,
                                   lambda: app.state.model)
        try:
            await consumer.start()
            app.state.consumer = consumer
            app.state.consumer_task = asyncio.create_task(consumer.run())
        except Exception as exc:
            # Kafka chưa lên: API vẫn phục vụ, /health báo consumerRunning=false
            log.error("Không khởi động được Kafka consumer (%s)", type(exc).__name__)
            await consumer.stop()
    try:
        yield
    finally:
        if app.state.consumer_task is not None:
            app.state.consumer_task.cancel()
            with contextlib.suppress(asyncio.CancelledError, Exception):
                await app.state.consumer_task
        if app.state.consumer is not None:
            await app.state.consumer.stop()
        await http.aclose()
        await redis_client.aclose()


app = FastAPI(
    title="bot-detection-service",
    docs_url="/api/bot-detection/docs",
    redoc_url="/api/bot-detection/redoc",
    openapi_url="/api/bot-detection/openapi.json",
    lifespan=lifespan,
)

api_router = APIRouter(prefix="/api/bot-detection")


@app.exception_handler(ApiError)
async def api_error_handler(_: Request, exc: ApiError) -> JSONResponse:
    return JSONResponse(status_code=exc.status, content={"code": exc.code, "message": exc.message})


@app.exception_handler(RequestValidationError)
async def validation_error_handler(_: Request, __: RequestValidationError) -> JSONResponse:
    code, message, status = INVALID_FEATURES
    return JSONResponse(status_code=status, content={"code": code, "message": message})


def require_admin(role: str | None) -> None:
    # Trust-The-Gateway (D03): Gateway luôn ghi đè X-User-Role bằng role trong JWT
    if role != "ADMIN":
        raise ApiError(*ADMIN_REQUIRED)


def current_model(request: Request) -> ModelBundle:
    model = request.app.state.model
    if model is None:
        raise ApiError(*MODEL_UNAVAILABLE)
    return model


@api_router.get("/health")
def health_check(request: Request) -> dict[str, Any]:
    model = getattr(request.app.state, "model", None)
    consumer = getattr(request.app.state, "consumer", None)
    return {
        "status": "UP",
        "modelLoaded": model is not None,
        "modelVersion": model.version if model is not None else None,
        "consumerRunning": bool(consumer is not None and consumer.running),
    }


@api_router.get("/model")
def model_info(request: Request, x_user_role: str | None = Header(default=None)) -> dict[str, Any]:
    require_admin(x_user_role)
    return ok(current_model(request).describe())


@api_router.post("/score")
def score(body: ScoreRequest, request: Request, x_user_role: str | None = Header(default=None)) -> dict[str, Any]:
    require_admin(x_user_role)
    model = current_model(request)
    unknown = set(body.features) - set(FEATURE_NAMES)
    missing = [name for name in FEATURE_NAMES if name not in body.features and name not in model.imputations]
    if unknown or missing:
        raise ApiError(*INVALID_FEATURES)
    try:
        result = model.score(body.features)
    except InvalidCartEvent as exc:
        raise ApiError(*INVALID_FEATURES) from exc
    return ok({"anomalyScore": result.anomaly_score, "riskLevel": result.risk_level, "modelVersion": model.version})


app.include_router(api_router)
