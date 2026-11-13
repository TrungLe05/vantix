# Vantix — Phase 1: User & Auth — Tài liệu thiết kế

> **Trạng thái:** Đã triển khai. Cập nhật 13/11/2026 theo code `user-service`/`api-gateway` và mục 0 của `P1_auth_api_design.md` (OTP chuyển Redis, refresh token JWT trong cookie, OAuth2 qua Spring Security + exchange). Chi tiết request/response/lỗi: `P1_auth_api_design.md`.
> **Liên quan:** `../00_roadmap_phases_v1.md` (mục P1), `../P0_infrastructure_summary.md`

---

## 1. Quyết định thiết kế đã chốt (bối cảnh cho các phần sau)

| # | Quyết định |
|---|---|
| 1 | Refresh token dùng **rotation + reuse detection theo family** (mục 4.3). Refresh token là JWT (`token_type=refresh`), DB chỉ lưu SHA-256 hash; đi bằng cookie `httpOnly`, `Path=/api/auth` |
| 2 | Email chưa xác thực → `status = PENDING_VERIFICATION`, **chặn đăng nhập** hoàn toàn; có API gửi lại OTP |
| 3 | OAuth2 Google xử lý trong `user-service` bằng `oauth2Login()` của Spring Security; sau khi Google xác thực, `user-service` **tự phát hành JWT hệ thống** qua bước đổi one-time code; tài khoản qua Google coi như đã xác thực email |
| 4 | ADMIN tạo tài khoản STAFF → hệ thống sinh **mật khẩu tạm**, gửi qua email (Kafka → Notification), STAFF bắt buộc đổi mật khẩu (claim `mcp=true`, Gateway chỉ cho gọi allowlist) |
| 5 | Quên mật khẩu dùng chung pattern OTP-qua-email như xác thực đăng ký |
| 6 | Mô hình JWT: Gateway verify, inject `X-User-Id`/`X-User-Role`; service downstream dùng `GatewayHeaderAuthFilter` (`commonlib-security`), không tự verify JWT |
| 7 | OTP lưu ở **Redis** với TTL (bỏ bảng `USER_OTP`), hash `HMAC-SHA256` |

---

## 2. Use case tổng quát

```mermaid
graph LR
    Buyer([BUYER])
    Admin([ADMIN])
    Staff([STAFF])
    Guest([Người dùng chưa đăng nhập])

    Guest --> UC1[Đăng ký tài khoản]
    Guest --> UC2[Xác thực email bằng OTP]
    Guest --> UC2b[Gửi lại OTP xác thực]
    Guest --> UC3[Đăng nhập bằng email/mật khẩu]
    Guest --> UC4[Đăng nhập bằng Google]
    Guest --> UC5[Quên mật khẩu]

    Buyer --> UC6[Làm mới access token]
    Buyer --> UC7[Đăng xuất]
    Buyer --> UC8[Xem thông tin tài khoản]

    Admin --> UC9[Tạo tài khoản STAFF]
    Staff --> UC10[Đổi mật khẩu lần đầu]

    UC1 -.include.-> UC2
    UC3 -.include.-> UC6
```

**Ghi chú:** SELLER chưa có ở Phase 1. ADMIN đầu tiên, 1 STAFF và 5 BUYER mẫu được seed khi khởi động (`ApplicationRunConfig`, xem API design mục 7).

---

## 3. ERD

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "có nhiều"

    USERS {
        UUID id PK
        VARCHAR email "unique khi deleted_at IS NULL"
        VARCHAR password_hash "nullable — null khi chỉ đăng nhập Google"
        VARCHAR full_name
        user_role role "BUYER | ADMIN | STAFF"
        user_status status "PENDING_VERIFICATION | ACTIVE | LOCKED"
        oauth_provider oauth_provider "NONE | GOOGLE"
        VARCHAR oauth_id "nullable — sub từ Google"
        BOOLEAN must_change_password "true cho STAFF mới tạo"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        TIMESTAMPTZ deleted_at "nullable — soft delete"
    }

    REFRESH_TOKENS {
        UUID id PK
        UUID family_id "nhóm các token cùng một phiên đăng nhập"
        UUID user_id FK
        VARCHAR token_hash "SHA-256 hex, unique"
        VARCHAR device_fingerprint_hash "nullable — chưa dùng (P6/P8)"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ revoked_at "nullable — null = còn hiệu lực"
    }
```

**Ghi chú thiết kế:**
- Migration: `V1__init_users_schema.sql` (role/status/provider là PostgreSQL enum), `V2__delete_col_expried_at.sql` bỏ `refresh_tokens.expires_at` — hạn dùng do claim `exp` của JWT quyết định.
- `email` unique bằng partial index `idx_users_email ... WHERE deleted_at IS NULL`; `(oauth_provider, oauth_id)` unique khi provider khác `NONE`.
- **Bảng `USER_OTP` đã bỏ.** OTP là dữ liệu tạm có thời hạn, lưu Redis:

| Khóa Redis | Giá trị | TTL |
|---|---|---|
| `otp:{purpose}:{userId}` | JSON `{otpCodeHash, attemptCount}` | 10 phút |
| `otp:cooldown:{purpose}:{userId}` | `"1"` | 60 giây |
| `oauth:code:{code}` | `"{userId}:{isNewUser}"` | 60 giây |

Đánh đổi: khóa hết hạn biến mất nên không phân biệt "hết hạn" với "chưa từng có" (gộp lỗi 2301) và không còn audit lịch sử OTP.

---

## 4. Sequence diagram — các luồng chính

### 4.1 Đăng ký + xác thực email

```mermaid
sequenceDiagram
    actor U as User (Guest)
    participant GW as api-gateway
    participant US as user-service
    participant R as Redis
    participant K as Kafka
    participant N as notification-service

    U->>GW: POST /api/auth/register
    GW->>US: forward (route công khai)
    US->>US: Tạo user, status=PENDING_VERIFICATION (TransactionTemplate)
    US->>R: SET otp:EMAIL_VERIFICATION:{userId} = {HMAC(otp), 0} TTL 10m + cooldown 60s
    US->>K: publish user.email-verification-requested (sau commit, lỗi chỉ log)
    US-->>U: 201 Created
    K-->>N: consume event (P5 — chưa triển khai)
    N-->>U: Gửi email chứa OTP

    U->>GW: POST /api/auth/verify-email {email, otp}
    GW->>US: forward
    US->>R: đọc OTP; sai → attemptCount+1 (quá 5 → 2303); đúng → DEL
    US->>US: status=ACTIVE
    US-->>U: 200 OK
```

Hết hạn/mất email: `POST /api/auth/resend-verification {email}` luôn trả 200 (chống enumeration), tôn trọng cooldown.

### 4.2 Đăng nhập bằng email/mật khẩu — có JWT qua Gateway

```mermaid
sequenceDiagram
    actor U as User
    participant GW as api-gateway
    participant US as user-service

    U->>GW: POST /api/auth/login {email, password}
    GW->>US: forward (route công khai)
    US->>US: Kiểm tra password (hash giả nếu email không tồn tại), status phải = ACTIVE
    US->>US: Sinh family_id mới, access JWT (15m) + refresh JWT (7d), lưu SHA-256(refresh)
    US-->>U: 200 {auth: {accessToken, tokenType, expiresIn}, user, isNewUser:false} + Set-Cookie refreshToken (httpOnly, Path=/api/auth)

    Note over U,GW: Các request sau kèm Authorization: Bearer accessToken (token chỉ giữ trong bộ nhớ)

    U->>GW: GET /api/users/me (kèm JWT)
    GW->>GW: Xóa X-User-* từ client, verify chữ ký/exp/token_type=access
    GW->>GW: mcp=true và ngoài allowlist → 403 9004
    GW->>US: forward kèm X-User-Id, X-User-Role
    US->>US: GatewayHeaderAuthFilter set SecurityContext
    US-->>U: 200 {user info}
```

### 4.3 Refresh token — rotation bình thường và reuse detection

```mermaid
sequenceDiagram
    actor U as User
    participant US as user-service

    Note over U,US: Trường hợp bình thường
    U->>US: POST /api/auth/refresh (Cookie refreshToken=T1)
    US->>US: verifyRefreshToken(T1) (chữ ký, exp, token_type=refresh)
    US->>US: tra hash(T1), revoked_at = null → revokeIfActive (update nguyên tử)
    US->>US: Sinh T2 cùng family_id + access token mới
    US-->>U: 200 {auth, user} + Set-Cookie refreshToken=T2

    Note over U,US: T1 bị dùng lại sau khi đã rotate (hoặc hai request refresh chạy song song)
    U->>US: POST /api/auth/refresh (Cookie refreshToken=T1)
    US->>US: hash(T1) tìm thấy NHƯNG revoked_at != null → REUSE DETECTED
    US->>US: Revoke cả family (commit trước khi ném lỗi — TransactionTemplate)
    US-->>U: 401 2202 — buộc đăng nhập lại
```

Hệ quả: frontend phải serialize refresh (một request refresh duy nhất), nếu không chính người dùng sẽ bị coi là reuse.

### 4.4 Đăng nhập bằng Google (OAuth2)

```mermaid
sequenceDiagram
    actor U as Browser
    participant GW as api-gateway
    participant US as user-service (Spring Security oauth2Login)
    participant G as Google
    participant R as Redis
    participant FE as Frontend

    U->>GW: GET /api/oauth2/authorization/google
    GW->>US: forward
    US-->>U: 302 tới Google (state lưu trong HttpSession ngắn hạn)
    U->>G: Đăng nhập + đồng ý
    G-->>U: 302 về /api/login/oauth2/code/google?code&state
    U->>GW: GET /api/login/oauth2/code/google
    GW->>US: forward
    US->>G: Spring Security đổi code, lấy userinfo (email, email_verified, sub)
    alt email_verified != true
        US-->>U: 302 FE ?error=2504
    else Email chưa tồn tại
        US->>US: Tạo user GOOGLE, status=ACTIVE, isNewUser=true
    else Email đã tồn tại
        US->>US: Liên kết oauth_id (oauth_id khác → ?error=2505)
    end
    US->>R: SET oauth:code:{code} = userId:isNewUser TTL 60s
    US->>US: Hủy HttpSession
    US-->>U: 302 FE /auth-callback?code=...
    FE->>GW: POST /api/auth/oauth2/exchange {code}
    GW->>US: forward
    US->>R: GETDEL oauth:code:{code} (dùng một lần)
    US-->>FE: 200 {auth, user, isNewUser} + Set-Cookie refreshToken
```

Token không nằm trên URL; hệ thống phát hành JWT riêng, không dùng token Google. `isNewUser` cho phép frontend chạy onboarding lần đầu.

### 4.5 ADMIN tạo tài khoản STAFF

```mermaid
sequenceDiagram
    actor A as ADMIN
    actor A2 as STAFF
    participant GW as api-gateway
    participant US as user-service
    participant K as Kafka
    participant N as notification-service

    A->>GW: POST /api/users/staff {email, fullName} (JWT role=ADMIN)
    GW->>US: forward kèm X-User-Role=ADMIN
    US->>US: @PreAuthorize("hasRole('ADMIN')")
    US->>US: Tạo user role=STAFF, status=ACTIVE, mật khẩu tạm, must_change_password=true
    US->>K: publish user.staff-account-created (kèm mật khẩu tạm, KHÔNG log)
    US-->>A: 201 Created
    K-->>N: consume (P5 — chưa triển khai)
    N-->>A2: Email chứa mật khẩu tạm

    A2->>GW: POST /api/auth/login {email, tempPassword}
    GW->>US: forward
    US-->>A2: 200 (access JWT có mcp=true, user.mustChangePassword=true)
    A2->>GW: PATCH /api/auth/change-password {oldPassword, newPassword}
    GW->>GW: mcp=true nhưng thuộc allowlist → cho qua
    GW->>US: forward
    US->>US: must_change_password=false, thu hồi mọi refresh token
    US-->>A2: 200 OK → đăng nhập lại để nhận JWT mcp=false
```

### 4.6 Quên mật khẩu

```mermaid
sequenceDiagram
    actor U as User
    participant US as user-service
    participant R as Redis
    participant K as Kafka
    participant N as notification-service

    U->>US: POST /api/auth/forgot-password {email}
    US->>R: SET otp:FORGOT_PASSWORD:{userId} TTL 10m (email không tồn tại → vẫn 200)
    US->>K: publish user.forgot-password-requested
    K-->>N: consume (P5)
    N-->>U: Gửi email OTP
    U->>US: POST /api/auth/reset-password {email, otp, newPassword}
    US->>R: Kiểm tra OTP (tối đa 5 lần sai) → DEL
    US->>US: Cập nhật password_hash, thu hồi TOÀN BỘ refresh token (mọi family)
    US-->>U: 200 OK
```

Sau khi đổi mật khẩu (quên mật khẩu hay đổi thủ công) luôn thu hồi toàn bộ refresh token, để refresh token cũ bị đánh cắp không còn dùng được. Access token còn sống tới hết 15 phút (giới hạn đã biết).

---

## 5. API (đã triển khai — chi tiết ở `P1_auth_api_design.md`)

| ID | Method | Path | Auth | Mô tả |
|---|---|---|---|---|
| A01 | POST | `/api/auth/register` | Công khai | Đăng ký |
| A02 | POST | `/api/auth/verify-email` | Công khai | Xác thực OTP đăng ký (`{email, otp}`) |
| A03 | POST | `/api/auth/resend-verification` | Công khai | Gửi lại OTP |
| A04 | POST | `/api/auth/login` | Công khai | Đăng nhập, set cookie refresh |
| A05 | GET | `/api/oauth2/authorization/google` | Công khai | Bắt đầu luồng Google (Spring Security) |
| A06 | GET | `/api/login/oauth2/code/google` | Công khai | Callback Google, 302 về frontend kèm one-time code |
| A07 | POST | `/api/auth/oauth2/exchange` | Công khai | Đổi one-time code lấy token |
| A08 | POST | `/api/auth/refresh` | Công khai (cookie refresh) | Làm mới access token, rotation |
| A09 | POST | `/api/auth/logout` | Cần JWT | Thu hồi family hiện tại (đọc cookie) |
| A10 | POST | `/api/auth/forgot-password` | Công khai | Gửi OTP quên mật khẩu |
| A11 | POST | `/api/auth/reset-password` | Công khai (OTP đúng) | Đặt lại mật khẩu |
| A12 | PATCH | `/api/auth/change-password` | Cần JWT | Đổi mật khẩu (kể cả STAFF lần đầu) |
| A13 | GET | `/api/users/me` | Cần JWT | Xem thông tin bản thân |
| A14 | POST | `/api/users/staff` | Cần JWT, role=ADMIN | Tạo tài khoản STAFF |

---

## 6. Kafka event (`commonlib-kafka`)

| Topic | Producer | Consumer | Payload chính |
|---|---|---|---|
| `user.email-verification-requested` | user-service | notification-service (P5, chưa có) | userId, email, fullName, otpCode |
| `user.forgot-password-requested` | user-service | notification-service (P5) | userId, email, fullName, otpCode |
| `user.staff-account-created` | user-service | notification-service (P5) | userId, email, fullName, temporaryPassword |

Publish sau khi transaction commit, lỗi chỉ log (không outbox ở P1 — mất event thì người dùng dùng resend/forgot-password).

**Lưu ý bảo mật:** OTP và `temporaryPassword` đi qua Kafka ở dạng plaintext trong nội bộ hệ thống — chấp nhận được vì Kafka nằm trong docker network nội bộ, nhưng tuyệt đối không log payload các topic này.

---
