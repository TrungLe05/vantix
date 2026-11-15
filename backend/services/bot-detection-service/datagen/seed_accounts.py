"""Seed tài khoản cho một vòng sinh dữ liệu vào ``users_service_e2e`` (không đụng DB dev).

Mỗi vòng (``--round A``/``B``) có tài khoản riêng để tập kiểm tra cuối không chứa tài khoản đã thấy khi
huấn luyện. Email tổng hợp ``dg-<round>-<kind>-NNN@example.test``; nhãn chỉ nằm ở tiền tố email và
file ``training/data/accounts_<round>.csv`` — model không bao giờ thấy nhãn.

Mật khẩu sinh ngẫu nhiên mỗi lần chạy, chỉ ghi vào ``datagen/.local/`` (gitignored) cho k6 đọc.

Chạy (cần container ``postgres`` của compose và user-service đã chạy Flyway trên DB e2e):
    python -I datagen/seed_accounts.py --round A
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import random
import secrets
import subprocess
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

import bcrypt

ROOT = Path(__file__).resolve().parent.parent
LOCAL = ROOT / "datagen" / ".local"
DATA = ROOT / "training" / "data"
DATABASE = "users_service_e2e"

# Số tài khoản mỗi loại trong một vòng
KINDS = {
    "human": 200,
    "botfast": 18,     # dồn dập, không xem trang, xoay giỏ
    "botviewer": 14,   # gọi availability rồi thêm ngay, gom nhiều sự kiện
    "botfarm": 24,     # nhiều tài khoản trên vài thiết bị chung, hành vi từng tài khoản gần giống người
}


def account_age(kind: str, rng: random.Random) -> timedelta:
    """Phân bố tuổi tài khoản có chồng lấn có chủ đích: có người dùng mới và có bot dùng tài khoản cũ."""
    if kind == "human":
        if rng.random() < 0.12:
            return timedelta(hours=rng.uniform(0.2, 24))
        return timedelta(hours=math.exp(rng.uniform(math.log(24), math.log(24 * 730))))
    if rng.random() < 0.3:
        return timedelta(days=rng.uniform(30, 400))
    return timedelta(hours=rng.uniform(0.05, 48))


def psql(sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", "-i", "postgres", "sh", "-c",
         f'psql -v ON_ERROR_STOP=1 -q -t -A -U "$POSTGRES_USER" -d {DATABASE}'],
        input=sql, capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise SystemExit(f"psql lỗi: {result.stderr.strip()[:300]}")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--round", required=True, choices=["A", "B", "C"])
    parser.add_argument("--seed", type=int, default=None)
    args = parser.parse_args()
    rnd = args.round.lower()
    rng = random.Random(args.seed if args.seed is not None else {"a": 11, "b": 22, "c": 33}[rnd])

    now = datetime.now(tz=timezone.utc)
    password = "Dg-" + secrets.token_urlsafe(18)
    # Một hash cho mọi tài khoản của vòng: bcrypt chậm có chủ đích, hash 250 lần mất cả phút
    password_hash = bcrypt.hashpw(password.encode(), bcrypt.gensalt(rounds=10)).decode()

    rows, accounts = [], []
    for kind, count in KINDS.items():
        for i in range(count):
            user_id = str(uuid.uuid4())
            email = f"dg-{rnd}-{kind}-{i:03d}@example.test"
            created = now - account_age(kind, rng)
            rows.append((user_id, email, created))
            accounts.append({"id": user_id, "email": email, "kind": kind,
                             "label": 0 if kind == "human" else 1, "createdAt": created.isoformat()})
    admin_email = f"dg-{rnd}-admin@example.test"

    values = ",\n".join(
        f"('{uid}', '{email}', '{password_hash}', 'Datagen {email.split('@')[0]}', 'BUYER', 'ACTIVE', "
        f"'{created.isoformat()}', '{created.isoformat()}')"
        for uid, email, created in rows
    )
    psql(f"""
BEGIN;
DELETE FROM users WHERE email LIKE 'dg-{rnd}-%@example.test';
INSERT INTO users (id, email, password_hash, full_name, role, status, created_at, updated_at) VALUES
{values};
INSERT INTO users (email, password_hash, full_name, role, status)
VALUES ('{admin_email}', '{password_hash}', 'Datagen admin', 'ADMIN', 'ACTIVE');
COMMIT;
""")

    LOCAL.mkdir(parents=True, exist_ok=True)
    DATA.mkdir(parents=True, exist_ok=True)
    (LOCAL / f"accounts_{rnd}.json").write_text(json.dumps({
        "password": password,
        "admin": admin_email,
        "accounts": [{k: a[k] for k in ("id", "email", "kind")} for a in accounts],
    }), encoding="utf-8")
    # Bản commit được: không có email/mật khẩu, chỉ id ngẫu nhiên + nhãn + mốc tạo
    with (DATA / f"accounts_{rnd}.csv").open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=["buyer_id", "kind", "label", "created_at"])
        writer.writeheader()
        for a in accounts:
            writer.writerow({"buyer_id": a["id"], "kind": a["kind"], "label": a["label"],
                             "created_at": a["createdAt"]})
    print(f"Vòng {args.round}: {len(accounts)} tài khoản + 1 admin")


if __name__ == "__main__":
    main()
