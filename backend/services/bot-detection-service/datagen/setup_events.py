"""Tạo và publish các sự kiện GA dùng cho một vòng sinh dữ liệu (qua Gateway, tài khoản admin datagen).

    python -I datagen/setup_events.py --round A [--gateway http://localhost:8999]
"""
from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / "datagen" / ".local"
EVENT_COUNT = 4


def check(response: httpx.Response) -> dict:
    if response.status_code >= 300:
        try:
            code = response.json().get("code")
        except ValueError:
            code = None
        raise SystemExit(f"{response.request.method} {response.request.url.path} → HTTP {response.status_code} "
                         f"code={code}")
    return response.json()["result"]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--round", required=True, choices=["A", "B", "C"])
    parser.add_argument("--gateway", default="http://localhost:8999")
    args = parser.parse_args()
    rnd = args.round.lower()
    secrets_file = json.loads((LOCAL / f"accounts_{rnd}.json").read_text(encoding="utf-8"))

    with httpx.Client(base_url=args.gateway + "/api", timeout=20) as http:
        login = check(http.post("/auth/login", json={"email": secrets_file["admin"],
                                                     "password": secrets_file["password"]}))
        http.headers["Authorization"] = f"Bearer {login['auth']['accessToken']}"
        venue = check(http.post("/venues", json={"name": f"Datagen Arena {args.round}", "address": "1 Test St",
                                                 "city": "Ho Chi Minh"}))
        start = datetime.now(tz=timezone.utc) + timedelta(days=40)
        events = []
        for i in range(EVENT_COUNT):
            event = check(http.post("/events", json={
                "venueId": venue["id"],
                "name": f"Datagen {args.round} show {i + 1}",
                "startAt": (start + timedelta(days=i)).isoformat().replace("+00:00", "Z"),
                "endAt": (start + timedelta(days=i, hours=3)).isoformat().replace("+00:00", "Z"),
            }))
            ticket_types = [
                check(http.post(f"/events/{event['id']}/ticket-types", json={
                    "category": "GENERAL_ADMISSION", "name": name, "price": price, "totalQuantity": 20000,
                }))["id"]
                for name, price in (("Standing", 40.0), ("Early bird", 25.0))
            ]
            check(http.patch(f"/events/{event['id']}/publish"))
            events.append({"eventId": event["id"], "ticketTypeIds": ticket_types})
    (LOCAL / f"events_{rnd}.json").write_text(json.dumps(events), encoding="utf-8")
    print(f"Vòng {args.round}: {len(events)} sự kiện đã publish")


if __name__ == "__main__":
    main()
