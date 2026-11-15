from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from tests.conftest import BOT_FEATURES


@pytest.fixture()
def client(monkeypatch: pytest.MonkeyPatch, model_dir):
    monkeypatch.setenv("BOT_CONSUMER_ENABLED", "false")
    monkeypatch.setenv("BOT_MODEL_DIR", str(model_dir))
    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture()
def client_without_model(monkeypatch: pytest.MonkeyPatch, tmp_path):
    monkeypatch.setenv("BOT_CONSUMER_ENABLED", "false")
    monkeypatch.setenv("BOT_MODEL_DIR", str(tmp_path))
    from app.main import app

    with TestClient(app) as test_client:
        yield test_client


ADMIN = {"X-User-Id": "00000000-0000-0000-0000-0000000000ad", "X-User-Role": "ADMIN"}


def test_health_reports_model_and_consumer(client: TestClient) -> None:
    body = client.get("/api/bot-detection/health").json()
    assert body == {"status": "UP", "modelLoaded": True, "modelVersion": "iforest-test", "consumerRunning": False}


def test_admin_only_endpoints(client: TestClient) -> None:
    for role in (None, "BUYER", "STAFF"):
        headers = {} if role is None else {"X-User-Role": role}
        response = client.get("/api/bot-detection/model", headers=headers)
        assert response.status_code == 403 and response.json()["code"] == 7001
        response = client.post("/api/bot-detection/score", json={"features": BOT_FEATURES}, headers=headers)
        assert response.status_code == 403 and response.json()["code"] == 7001

    info = client.get("/api/bot-detection/model", headers=ADMIN).json()["result"]
    assert info["version"] == "iforest-test" and info["thresholds"]["high"] >= info["thresholds"]["medium"]

    scored = client.post("/api/bot-detection/score", json={"features": BOT_FEATURES}, headers=ADMIN).json()
    assert scored["code"] == 200 and scored["result"]["riskLevel"] == "HIGH"


def test_score_validates_feature_set(client: TestClient) -> None:
    unknown = client.post("/api/bot-detection/score", json={"features": {**BOT_FEATURES, "ip": 1}}, headers=ADMIN)
    assert unknown.status_code == 400 and unknown.json()["code"] == 7003
    partial = dict(BOT_FEATURES)
    partial.pop("buyer_adds_1m")
    missing = client.post("/api/bot-detection/score", json={"features": partial}, headers=ADMIN)
    assert missing.status_code == 400 and missing.json()["code"] == 7003
    # Có giá trị thay thế trong meta → được phép bỏ trống
    imputed = {**BOT_FEATURES, "account_age_hours": None}
    assert client.post("/api/bot-detection/score", json={"features": imputed}, headers=ADMIN).status_code == 200
    bad_type = client.post("/api/bot-detection/score", json={"features": {"buyer_adds_1m": "many"}}, headers=ADMIN)
    assert bad_type.status_code == 400 and bad_type.json()["code"] == 7003


def test_without_model_score_is_unavailable(client_without_model: TestClient) -> None:
    assert client_without_model.get("/api/bot-detection/health").json()["modelLoaded"] is False
    response = client_without_model.post("/api/bot-detection/score", json={"features": BOT_FEATURES},
                                         headers=ADMIN)
    assert response.status_code == 503 and response.json()["code"] == 7002
