"""Dựng/dừng stack sinh dữ liệu P6 trên DB ``*_e2e`` (không đụng DB dev).

    python -I datagen/run_stack.py start      # gateway, user, product, order (jar đã build) + bot-detection
    python -I datagen/run_stack.py stop

Secret (JWT, OTP HMAC, Google client…) nạp từ ``backend/.env`` vào môi trường tiến trình con, không in ra.
Redis dùng DB riêng (3 cho service Java, 4 cho bot-detection), consumer group riêng để không tranh bản tin
với stack dev dùng chung Kafka. ``DEVICE_HASH_SECRET`` của stack này sinh một lần, lưu ở ``.local/``.
"""
from __future__ import annotations

import json
import os
import secrets
import subprocess
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
BACKEND = ROOT.parent.parent
LOCAL = ROOT / "datagen" / ".local"
LOGS = LOCAL / "logs"
PIDS = LOCAL / "pids.json"

# Máy dev ít RAM: heap nhỏ, JIT C1 — đủ cho vài trăm request/phút
JVM = ["-Xmx320m", "-XX:TieredStopAtLevel=1", "-XX:+UseSerialGC"]


def read_env_file(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.is_file():
        return values
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        key = key.strip().removeprefix("export ").strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        values[key] = value
    return values


def device_secret() -> str:
    LOCAL.mkdir(parents=True, exist_ok=True)
    path = LOCAL / "device_hash_secret"
    if not path.exists():
        path.write_text(secrets.token_urlsafe(32), encoding="utf-8")
    return path.read_text(encoding="utf-8").strip()


def java_service(name: str, database: str | None, extra: dict[str, str]) -> tuple[list[str], dict[str, str]]:
    jar = next((BACKEND / "services" / name / "target").glob(f"{name}-*.jar"))
    env = dict(extra)
    if database:
        env.update({
            "SPRING_DATASOURCE_URL": f"jdbc:postgresql://localhost:5432/{database}",
            "SPRING_DATASOURCE_USERNAME": "vantix_test",
            "SPRING_DATASOURCE_PASSWORD": "vantix_test",
            "SPRING_DATA_REDIS_DATABASE": "3",
        })
    return ["java", *JVM, "-jar", str(jar)], env


def start() -> None:
    if PIDS.exists():
        raise SystemExit("Stack đang chạy (có .local/pids.json) — chạy stop trước")
    LOGS.mkdir(parents=True, exist_ok=True)
    base = {**os.environ, **read_env_file(BACKEND / ".env")}
    python = str(ROOT / ".venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python"))
    services = {
        "user-service": java_service("user-service", "users_service_e2e", {}),
        "product-service": java_service("product-service", "products_service_e2e", {}),
        "order-service": java_service("order-service", "orders_service_e2e", {
            "DEVICE_HASH_SECRET": device_secret(),
            "APP_ORDER_KAFKA_CONSUMER_GROUP": "order-service-datagen",
        }),
        "api-gateway": java_service("api-gateway", None, {}),
        "bot-detection-service": ([python, "-m", "uvicorn", "app.main:app", "--port", "9005"], {
            "REDIS_URL": "redis://localhost:6379/4",
            "BOT_CONSUMER_GROUP": "bot-detection-datagen",
        }),
    }
    flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    pids = {}
    for name, (command, env) in services.items():
        log = (LOGS / f"{name}.log").open("w", encoding="utf-8")
        cwd = ROOT if name == "bot-detection-service" else BACKEND
        proc = subprocess.Popen(command, env={**base, **env}, stdout=log, stderr=subprocess.STDOUT,
                                cwd=cwd, creationflags=flags)
        pids[name] = proc.pid
        print(f"start {name} pid={proc.pid}")
    PIDS.write_text(json.dumps(pids), encoding="utf-8")
    wait_healthy()


def wait_healthy(timeout: float = 240) -> None:
    checks = {
        "user-service": "http://localhost:9004/api/actuator/health",
        "product-service": "http://localhost:9003/api/actuator/health",
        "order-service": "http://localhost:9001/api/actuator/health",
        "api-gateway": "http://localhost:8999/actuator/health",
        "bot-detection-service": "http://localhost:9005/api/bot-detection/health",
    }
    deadline = time.time() + timeout
    pending = dict(checks)
    while pending and time.time() < deadline:
        for name, url in list(pending.items()):
            try:
                if httpx.get(url, timeout=2).status_code < 500:
                    print(f"up {name}")
                    pending.pop(name)
            except httpx.HTTPError:
                pass
        time.sleep(3)
    if pending:
        raise SystemExit(f"Chưa lên: {', '.join(pending)} — xem datagen/.local/logs/")


def stop() -> None:
    if not PIDS.exists():
        print("Không có stack nào đang chạy")
        return
    for name, pid in json.loads(PIDS.read_text(encoding="utf-8")).items():
        if os.name == "nt":
            subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
        else:
            subprocess.run(["kill", str(pid)], check=False)
        print(f"stop {name} pid={pid}")
    PIDS.unlink()


if __name__ == "__main__":
    {"start": start, "stop": stop}[sys.argv[1] if len(sys.argv) > 1 else "start"]()
