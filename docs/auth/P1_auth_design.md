# Vantix — Phase 1: User & Auth — Tài liệu thiết kế

> **Trạng thái:** Thiết kế — dùng để code theo, chưa phải tài liệu tổng kết sau khi làm xong.
> **Liên quan:** `00_roadmap_phases_v1.md` (mục P1), `P0_infrastructure_summary.md`

---

## 1. Quyết định thiết kế đã chốt (bối cảnh cho các phần sau)

| # | Quyết định |
|---|---|
| 1 | Refresh token dùng **rotation + reuse detection theo family** (xem mục 3.3 để hiểu cơ chế, mục 4 cho schema) |
| 2 | Email chưa xác thực → `status = PENDING_VERIFICATION`, **chặn đăng nhập** hoàn toàn |
| 3 | OAuth2 Google xử lý trong `user-service`; sau khi Google xác thực, `user-service` **tự phát hành JWT hệ thống** (không dùng token Google làm session); tài khoản qua Google tự động coi như đã xác thực email |
| 4 | ADMIN tạo tài khoản STAFF → hệ thống sinh **mật khẩu tạm**, gửi qua email (Kafka → Notification), STAFF bắt buộc đổi mật khẩu ở lần đăng nhập đầu |
| 5 | Quên mật khẩu dùng chung pattern OTP-qua-email như xác thực đăng ký |
| 6 | Mô hình JWT: Gateway verify, inject `X-User-Id`/`X-User-Role`; service downstream dùng `GatewayHeaderAuthFilter` (đã có từ `commonlib-security`), không tự verify JWT |

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

**Ghi chú:** SELLER chưa có ở Phase 1, không xuất hiện trong use case này (theo phạm vi đã chốt ở roadmap).

---

## 3. ERD

```mermaid
erDiagram
    USERS ||--o{ REFRESH_TOKENS : "có nhiều"
    USERS ||--o{ USER_OTP : "có nhiều"

    USERS {
        UUID id PK
        VARCHAR email UK
        VARCHAR password_hash "nullable — null khi chỉ đăng nhập Google"
        VARCHAR full_name
        VARCHAR role "BUYER | ADMIN | STAFF"
        VARCHAR status "PENDING_VERIFICATION | ACTIVE | LOCKED"
        VARCHAR oauth_provider "NONE | GOOGLE"
        VARCHAR oauth_id "nullable — sub từ Google"
        BOOLEAN must_change_password "true cho STAFF mới tạo, đến khi đổi mật khẩu lần đầu"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        TIMESTAMPTZ deleted_at "nullable — soft delete"
    }

    REFRESH_TOKENS {
        UUID id PK
        UUID family_id "nhóm các token cùng một phiên đăng nhập"
        UUID user_id FK
        VARCHAR token_hash "SHA-256, không lưu token thô"
        VARCHAR device_fingerprint_hash "nullable"
        TIMESTAMPTZ created_at
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ revoked_at "nullable — null = còn hiệu lực"
    }

    USER_OTP {
        UUID id PK
        UUID user_id FK
        VARCHAR purpose "EMAIL_VERIFICATION | FORGOT_PASSWORD"
        VARCHAR otp_code_hash
        INT attempt_count
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ created_at
    }
```

**Ghi chú thiết kế:**
- `USER_OTP` là bảng, **không phải Redis**, khác với OTP thiết bị mới ở dự án trước (vốn dùng Redis TTL). Lý do: OTP xác thực email/quên mật khẩu cần audit lâu hơn (biết ai đã xác thực khi nào), trong khi Redis phù hợp hơn cho dữ liệu tồn tại rất ngắn và không cần truy vết. Bạn có thể đổi sang Redis nếu muốn nhất quán tuyệt đối với cách làm OTP cũ — đây là điểm mở, nói với mình nếu muốn đổi.
- `email` chỉ unique khi `deleted_at IS NULL` (cho phép tạo lại email đã bị xóa mềm) — cần thể hiện bằng partial unique index khi viết migration, không phải constraint thường.

---

## 4. Sequence diagram — các luồng chính

### 4.1 Đăng ký + xác thực email

```mermaid
sequenceDiagram
    actor U as User (Guest)
    participant GW as api-gateway
    participant US as user-service
    participant K as Kafka
    participant N as notification-service

    U->>GW: POST /api/auth/register
    GW->>US: forward (route công khai, không cần JWT)
    US->>US: Tạo user, status=PENDING_VERIFICATION
    US->>US: Sinh OTP, lưu hash vào USER_OTP
    US->>K: publish user.email-verification-requested
    US-->>U: 201 Created
    K->>N: consume event
    N->>U: Gửi email chứa OTP

    U->>GW: POST /api/auth/verify-email {otp}
    GW->>US: forward
    US->>US: Kiểm tra OTP, đúng → status=ACTIVE
    US-->>U: 200 OK
```

### 4.2 Đăng nhập bằng email/mật khẩu — có JWT qua Gateway

```mermaid
sequenceDiagram
    actor U as User
    participant GW as api-gateway
    participant US as user-service

    U->>GW: POST /api/auth/login {email, password}
    GW->>US: forward (route công khai)
    US->>US: Kiểm tra password, status phải = ACTIVE
    US->>US: Sinh family_id mới, tạo access token (JWT) + refresh token
    US-->>GW: 200 {accessToken, refreshToken}
    GW-->>U: trả về client

    Note over U,GW: Các request sau đều kèm Authorization: Bearer accessToken

    U->>GW: GET /api/users/me (kèm JWT)
    GW->>GW: Verify JWT bằng shared secret
    GW->>GW: Inject X-User-Id, X-User-Role
    GW->>US: forward kèm header
    US->>US: GatewayHeaderAuthFilter đọc header, set SecurityContext
    US-->>GW: 200 {user info}
    GW-->>U: trả về client
```

### 4.3 Refresh token — rotation bình thường và reuse detection

```mermaid
sequenceDiagram
    actor U as User
    participant US as user-service

    Note over U,US: Trường hợp bình thường
    U->>US: POST /api/auth/refresh {refreshToken: T1}
    US->>US: hash(T1), tìm thấy, revoked_at = null
    US->>US: Đánh dấu T1.revoked_at = now()
    US->>US: Sinh T2 cùng family_id, sinh access token mới
    US-->>U: 200 {accessToken, refreshToken: T2}

    Note over U,US: Trường hợp bị đánh cắp — T1 bị dùng lại sau khi đã rotate
    U->>US: POST /api/auth/refresh {refreshToken: T1} (đã bị revoke ở trên)
    US->>US: hash(T1), tìm thấy, NHƯNG revoked_at != null
    US->>US: REUSE DETECTED
    US->>US: Revoke toàn bộ token có cùng family_id (kể cả T2 đang hợp lệ)
    US-->>U: 401 — buộc đăng nhập lại
```

### 4.4 Đăng nhập bằng Google (OAuth2)

```mermaid
sequenceDiagram
    actor U as User
    participant GW as api-gateway
    participant US as user-service
    participant G as Google OAuth2

    U->>GW: GET /api/auth/oauth2/google
    GW->>US: forward
    US-->>U: 302 redirect tới Google consent screen
    U->>G: Đăng nhập + đồng ý
    G-->>U: 302 redirect về callback kèm code
    U->>GW: GET /api/auth/oauth2/callback?code=...
    GW->>US: forward
    US->>G: Đổi code lấy thông tin user (email, sub)
    G-->>US: {email, sub, ...}
    alt Email chưa tồn tại
        US->>US: Tạo user mới, oauth_provider=GOOGLE, status=ACTIVE (bỏ qua OTP)
    else Email đã tồn tại
        US->>US: Liên kết oauth_id vào user hiện có
    end
    US->>US: Sinh family_id mới, access token + refresh token (JWT hệ thống, không phải token Google)
    US-->>U: 200 {accessToken, refreshToken}
```

### 4.5 ADMIN tạo tài khoản STAFF

```mermaid
sequenceDiagram
    actor A as ADMIN
    actor A2 as STAFF
    participant GW as api-gateway
    participant US as user-service
    participant K as Kafka
    participant N as notification-service

    A->>GW: POST /api/users/staff {email, fullName} (kèm JWT role=ADMIN)
    GW->>GW: Verify JWT, inject X-User-Role=ADMIN
    GW->>US: forward
    US->>US: @PreAuthorize("hasRole('ADMIN')") pass
    US->>US: Tạo user role=STAFF, sinh mật khẩu tạm, must_change_password=true
    US->>K: publish user.staff-account-created (kèm mật khẩu tạm)
    US-->>A: 201 Created
    K->>N: consume event
    N->>A2: Gửi email cho STAFF chứa mật khẩu tạm

    Note over A2: STAFF đăng nhập lần đầu
    A2->>GW: POST /api/auth/login {email, tempPassword}
    GW->>US: forward
    US-->>A2: 200 {accessToken, refreshToken, mustChangePassword: true}
    A2->>GW: PATCH /api/auth/change-password {oldPassword, newPassword}
    GW->>US: forward
    US->>US: must_change_password = false
    US-->>A2: 200 OK
```

### 4.6 Quên mật khẩu

```mermaid
sequenceDiagram
    actor U as User
    participant US as user-service
    participant K as Kafka
    participant N as notification-service

    U->>US: POST /api/auth/forgot-password {email}
    US->>US: Sinh OTP, lưu USER_OTP (purpose=FORGOT_PASSWORD)
    US->>K: publish user.forgot-password-requested
    K->>N: consume
    N->>U: Gửi email OTP
    U->>US: POST /api/auth/reset-password {email, otp, newPassword}
    US->>US: Kiểm tra OTP đúng → cập nhật password_hash
    US->>US: Thu hồi TOÀN BỘ refresh token hiện có của user (mọi family) — bắt đăng nhập lại ở mọi thiết bị
    US-->>U: 200 OK
```

**Điểm cần lưu ý ở luồng 4.6:** sau khi đổi mật khẩu (dù qua quên mật khẩu hay đổi thủ công), nên thu hồi toàn bộ refresh token đang có — nếu không, một kẻ đã chiếm được refresh token cũ trước đó vẫn tiếp tục dùng được dù mật khẩu đã đổi.

---

## 5. API dự kiến (chốt chi tiết khi viết OpenAPI thật)

| Method | Path | Auth | Mô tả |
|---|---|---|---|
| POST | `/api/auth/register` | Công khai | Đăng ký |
| POST | `/api/auth/verify-email` | Công khai | Xác thực OTP đăng ký |
| POST | `/api/auth/login` | Công khai | Đăng nhập |
| GET | `/api/auth/oauth2/google` | Công khai | Bắt đầu luồng Google |
| GET | `/api/auth/oauth2/callback` | Công khai | Callback Google |
| POST | `/api/auth/refresh` | Công khai (cần refresh token hợp lệ) | Làm mới access token |
| POST | `/api/auth/logout` | Cần JWT | Thu hồi family hiện tại |
| POST | `/api/auth/forgot-password` | Công khai | Gửi OTP quên mật khẩu |
| POST | `/api/auth/reset-password` | Công khai (cần OTP đúng) | Đặt lại mật khẩu |
| PATCH | `/api/auth/change-password` | Cần JWT | Đổi mật khẩu (kể cả STAFF lần đầu) |
| GET | `/api/users/me` | Cần JWT | Xem thông tin bản thân |
| POST | `/api/users/staff` | Cần JWT, role=ADMIN | Tạo tài khoản STAFF |

---

## 6. Kafka event mới cần thêm vào `commonlib-kafka`

| Topic | Producer | Consumer | Payload chính |
|---|---|---|---|
| `user.email-verification-requested` | user-service | notification-service | userId, email, fullName, otpCode |
| `user.forgot-password-requested` | user-service | notification-service | userId, email, fullName, otpCode |
| `user.staff-account-created` | user-service | notification-service | userId, email, fullName, temporaryPassword |

**Lưu ý bảo mật:** `temporaryPassword` đi qua Kafka ở dạng plaintext trong nội bộ hệ thống — chấp nhận được vì Kafka nằm trong docker network nội bộ, không expose ra ngoài, nhưng cần ghi chú rõ trong code review sau này để không vô tình log payload này ra file log.

---