# Vantix — Phase 1: User & Auth — API Design

> **Trạng thái:** Đã triển khai — nội dung khớp với code `user-service` và `api-gateway` khi hoàn thành P1 (14 API, đã manual test).
> **Liên quan:** `P1_auth_design.md` (use case, ERD, sequence — **cần cập nhật theo mục 0**), `P0_infrastructure_summary.md`, `00_roadmap_phases_v1.md` (mục P1)
> **Phạm vi:** toàn bộ API của `user-service` trong P1 (auth + user + tạo STAFF) và phần xác thực JWT tại `api-gateway`. Mỗi API mô tả: request, response, lỗi (HTTP status + errorCode), các bước thực thi.

---

## 0. Thay đổi so với thiết kế ban đầu

Trong quá trình code, thiết kế ban đầu (`P1_auth_design.md` và bản API design đầu tiên) đã thay đổi ở các điểm sau. Ký hiệu: ✏️ = đổi thiết kế, ➕ = thêm mới.

| # | Loại | Thay đổi | Lý do / hệ quả |
|---|---|---|---|
| 1 | ✏️ | `POST /api/auth/verify-email` nhận `{email, otp}` thay vì chỉ `{otp}` | Endpoint công khai, chưa có JWT → server không biết OTP thuộc user nào nếu thiếu `email` |
| 2 | ➕ | Thêm `POST /api/auth/resend-verification` | User `PENDING_VERIFICATION` bị chặn đăng nhập và không đăng ký lại được (email đã tồn tại) → cần đường thoát khi OTP hết hạn/mất email |
| 3 | ✏️ | **OTP lưu ở Redis với TTL, bỏ bảng `USER_OTP`**. Gộp `2301 OTP_INVALID` và `2302 OTP_EXPIRED` thành `2301` | OTP là dữ liệu tạm có thời hạn, phù hợp Redis TTL (cùng nhóm với hold ở P3). Hệ quả: khi key hết hạn thì biến mất hoàn toàn nên không phân biệt được "hết hạn" và "chưa từng có" |
| 4 | ✏️ | **Refresh token là JWT** (cùng khóa ký với access token, claim `token_type=refresh`), DB chỉ lưu `SHA-256(token)`. Verify bằng `JwtUtils.verifyRefreshToken()` rồi tra DB. **Bỏ cột `refresh_tokens.expires_at`** (migration `V2`) | JWT tự mang `exp` nên không cần lặp lại ở DB; DB chỉ giữ thứ JWT không làm được (trạng thái thu hồi, `family_id`) |
| 5 | ✏️ | **Refresh token đi bằng cookie `httpOnly`**, không nằm trong body. A08 (refresh) và A09 (logout) đọc token từ cookie | JavaScript không đọc được cookie httpOnly → giảm rủi ro bị đánh cắp qua XSS |
| 6 | ✏️ | Response login / exchange / refresh dạng lồng `{auth, user}`; `UserResponse` bỏ `createdAt` | Tách rõ phần token và phần thông tin user |
| 7 | ✏️ | **OAuth2 Google dùng `oauth2Login()` của Spring Security** thay vì tự gọi Google. A05/A06 dùng path mặc định của Spring Security; `state` lưu trong HttpSession ngắn hạn (`SessionCreationPolicy.IF_REQUIRED`), session bị hủy ngay sau khi login xong/thất bại. Bỏ khóa Redis `oauth:state:*`; `2501`, `2503` không còn phát sinh | Bỏ code HTTP client thủ công; Spring Security lo đổi `code` và lấy thông tin user |
| 8 | ✏️ | Callback OAuth trả **302 về frontend kèm one-time code**, thêm `POST /api/auth/oauth2/exchange` để đổi code lấy token | Callback là request điều hướng của trình duyệt; token không nên nằm trên URL. Hệ thống tự phát hành JWT riêng, **không dùng token của Google** |
| 9 | ➕ | JWT thêm claim `mcp` (must change password) và `token_type`. Gateway chặn API ngoài allowlist khi `mcp=true`, và **chỉ chấp nhận `token_type=access`** | `mcp`: ép STAFF đổi mật khẩu phía server. `token_type`: nếu thiếu, refresh token dùng được như access token, và vì Gateway không tra DB nên token đã thu hồi vẫn gọi API được đến hết hạn |
| 10 | ✏️ | Không dùng `@Transactional` trên service; dùng `TransactionTemplate` bao riêng từng lệnh ghi (mục 1.6) | Tránh rollback nhầm khi vừa ghi vừa ném lỗi, và method `@Modifying @Query` tự viết không tự có transaction |
| 11 | ➕ | Seed sẵn tài khoản ADMIN + STAFF khi khởi động (mục 7) | Không có API nào tạo ADMIN đầu tiên |

**Cần cập nhật `P1_auth_design.md`:** ERD bỏ bảng `USER_OTP` và cột `REFRESH_TOKENS.expires_at`; sequence 4.1 và 4.6 (OTP ở Redis); 4.3 (refresh đọc từ cookie); 4.4 (`oauth2Login()` + bước exchange); 4.2/4.3 (response nhận token qua cookie).

---

## 1. Quy ước chung

### 1.1 Format response

Tuân theo `ApiResponse<T>` (`commonlib-api-response`). Field `null` (`message`, `result`) không xuất hiện trong JSON.

```json
// Thành công
{ "code": 200, "message": "…(bỏ qua nếu null)…", "result": { } }

// Lỗi — code/message lấy từ ErrorCode
{ "code": 2101, "message": "Email hoặc mật khẩu không đúng" }
```

- `code` khi thành công luôn là `200` (kể cả khi HTTP status là `201`) — HTTP status phản ánh loại thành công. Giá trị `200` không đụng dải errorCode (≥ 1000).
- Lỗi validation (`1001`): `message` chứa thông điệp lỗi.

```json
{
  "code": 1001,
  "message": "Dữ liệu không hợp lệ"
}
```

- Refresh token **không bao giờ** nằm trong body; nó nằm trong header `Set-Cookie` (mục 1.4).

### 1.2 Phân dải errorCode

`errorCode` (nghiệp vụ, int) tách biệt với HTTP status. Mỗi service sở hữu một dải nghìn; trong dải chia tiếp theo nhóm chức năng (hàng trăm).

| Dải | Chủ sở hữu | Ghi chú |
|---|---|---|
| `1000–1999` | Common (`CommonErrorCode`) | Lỗi dùng chung mọi service: `1000`–`1005` |
| `2000–2999` | **user-service** | Chia nhóm bên dưới |
| `3000–3999` | product-service | Dành cho P2 |
| `4000–4999` | order-service | Dành cho P3 |
| `5000–5999` | payment-service | Dành cho P4 |
| `6000–6999` | notification-service | Dành cho P5 |
| `7000–7999` | bot-detection-service | Dành cho P6 |
| `8000–8999` | assistant-service | Dành cho P7 |
| `9000–9999` | api-gateway | Lỗi JWT tại Gateway |

Phân nhóm trong dải user-service:

| Nhóm | Chức năng |
|---|---|
| `20xx` | Đăng ký & xác thực email |
| `21xx` | Đăng nhập & trạng thái tài khoản |
| `22xx` | Refresh token |
| `23xx` | OTP (dùng chung xác thực email / quên mật khẩu) |
| `24xx` | Mật khẩu |
| `25xx` | OAuth2 Google |
| `26xx` | Quản lý user / STAFF |

### 1.3 Quy tắc chọn HTTP status

| HTTP | Dùng khi |
|---|---|
| 200 | Thành công |
| 201 | Tạo mới tài nguyên thành công |
| 302 | Chỉ dùng cho luồng OAuth2 (redirect trình duyệt) |
| 400 | Dữ liệu sai / nghiệp vụ không hợp lệ (OTP sai, mật khẩu cũ sai…) |
| 401 | Chưa xác thực hoặc thông tin xác thực sai/hết hạn (sai mật khẩu, refresh token không hợp lệ, JWT lỗi) |
| 403 | Đã xác thực nhưng không được phép (sai role, tài khoản chưa xác thực/bị khóa) |
| 404 | Không tìm thấy tài nguyên (chỉ dùng khi không lộ thông tin nhạy cảm) |
| 409 | Xung đột trạng thái (email đã tồn tại, đã xác thực rồi) |
| 429 | Quá nhiều lần thử |
| 500 / 502 | Lỗi hệ thống / lỗi nhà cung cấp bên ngoài |

### 1.4 Token, cookie & OTP

| Mục | Giá trị |
|---|---|
| Access token | JWT ký HMAC bằng khóa dùng chung, TTL **15 phút** (`app.jwt.expiration`). Claim: `sub` (userId), `role`, `email`, `mcp` (boolean), `token_type=access`, `jti`, `iss=Vantix`, `iat`, `exp`. Ký ở user-service, verify ở Gateway bằng cùng khóa (lấy từ biến môi trường) |
| Refresh token | JWT cùng khóa ký, cùng claim nhưng `token_type=refresh`, TTL **7 ngày** (`app.jwt.expiration-refresh`). DB chỉ lưu `SHA-256(token)` (hex) cùng `family_id`, `user_id`, `revoked_at`; **không có cột `expires_at`** — hạn dùng do JWT (`exp`) quyết định |
| Cookie refresh token | Tên `refreshToken`, `HttpOnly`, `Path=/api/auth`, `Max-Age` = TTL refresh token. `Secure` và `SameSite` đặt qua `app.cookie.secure` / `app.cookie.same-site`. Cùng site (dev): `Lax` + `secure=false`. Khác domain khi deploy: bắt buộc `SameSite=None` + `Secure` (nghĩa là phải HTTPS) |
| OTP | 6 chữ số, TTL **10 phút**, tối đa **5 lần nhập sai**/OTP, cooldown gửi lại **60 giây**. Lưu ở Redis (mục 1.7) |
| Hash OTP | `HMAC-SHA256(secret, userId + ":" + purpose + ":" + otp)` — không dùng SHA-256 thường vì không gian OTP chỉ 10⁶, lộ Redis là brute-force được ngay. Secret lấy từ biến môi trường (`app.otp.hmac-secret`) |
| Hash mật khẩu | BCrypt (`BCryptPasswordEncoder`) |
| One-time code (OAuth) | 32 byte ngẫu nhiên (Base64URL), TTL **60 giây**, dùng một lần |

### 1.5 Quy tắc validation dùng chung

| Field | Quy tắc |
|---|---|
| `email` | Bắt buộc, đúng định dạng, tối đa 255 ký tự. **Trim + lowercase** trước khi lưu/so sánh |
| `password` / `newPassword` | 8–64 ký tự (giới hạn 64 vì BCrypt chỉ xử lý 72 byte), có ít nhất 1 chữ cái và 1 chữ số |
| `fullName` | Bắt buộc, trim, 1–100 ký tự |
| `otp` | Đúng 6 chữ số (`^\d{6}$`) |
| `code` (exchange) | Bắt buộc, không rỗng, tối đa 128 ký tự |

### 1.6 Nguyên tắc bảo mật & kỹ thuật áp dụng cho nhiều API

1. **Chống user enumeration**: login trả cùng một lỗi cho "sai email" và "sai mật khẩu" (kể cả chạy `matches` với hash giả để thời gian phản hồi không lộ email tồn tại); `forgot-password`, `resend-verification` luôn trả 200 dù email có tồn tại hay không; `verify-email`/`reset-password` với email không tồn tại trả cùng lỗi `2301` như OTP sai. Ngoại lệ chấp nhận được: `register` phải báo email đã tồn tại (`2001`).
2. **Publish Kafka sau khi transaction DB commit**: transaction chỉ bao lệnh ghi DB (`TransactionTemplate`), event chỉ được gửi sau khi transaction đã trả về (đã commit). Việc gửi là bất đồng bộ, lỗi chỉ được log, **không** làm hỏng request của người dùng. **Giới hạn đã biết:** nếu Kafka lỗi ngay sau commit thì event mất (user không nhận email) — giảm nhẹ bằng `resend-verification` / `forgot-password`; giải pháp triệt để là outbox pattern, tạm chưa làm ở P1.
3. **Transaction**: không đặt `@Transactional` bao cả method service. Dùng `TransactionTemplate` bao riêng từng lệnh ghi.
   - Thao tác *ghi rồi mới ném lỗi* (thu hồi cả family khi phát hiện reuse refresh token) phải commit trước khi ném exception, nếu không sẽ bị rollback và mất tác dụng bảo mật.
   - Method `@Modifying @Query` tự viết trong repository **không tự có transaction** (khác `save()`/`findById()` có sẵn) — gọi ngoài transaction sẽ ném `TransactionRequiredException`, nên phải bọc trong `TransactionTemplate`.
4. **Không log** mật khẩu, OTP, refresh token, mật khẩu tạm, payload Kafka chứa các giá trị này.
5. **Mass assignment**: `role`, `status`, `mustChangePassword` không bao giờ nhận từ request body của API công khai.
6. **Gateway luôn xóa** `X-User-Id`/`X-User-Role` do client gửi lên (kể cả ở route công khai) trước khi xử lý.
7. **Trust-The-Gateway**: service phía sau tin tuyệt đối vào 2 header trên, nên cổng của service (ví dụ `9004`) không được truy cập trực tiếp từ bên ngoài. Dev cho phép gọi thẳng để test; siết bằng docker network ở P9.

### 1.7 Khóa Redis của user-service

| Khóa | Giá trị | TTL |
|---|---|---|
| `otp:{purpose}:{userId}` | JSON `{otpCodeHash, attemptCount}` | 10 phút |
| `otp:cooldown:{purpose}:{userId}` | `"1"` | 60 giây |
| `oauth:code:{code}` | `userId` | 60 giây |

`purpose` là `EMAIL_VERIFICATION` hoặc `FORGOT_PASSWORD`.

---

## 2. Tổng hợp API

| ID | Method | Path | Auth | Ghi chú |
|---|---|---|---|---|
| A01 | POST | `/api/auth/register` | Công khai | |
| A02 | POST | `/api/auth/verify-email` | Công khai | ✏️ thêm `email` |
| A03 | POST | `/api/auth/resend-verification` | Công khai | ➕ mới |
| A04 | POST | `/api/auth/login` | Công khai | Set cookie refresh token |
| A05 | GET | `/api/oauth2/authorization/google` | Công khai | ✏️ path do Spring Security |
| A06 | GET | `/api/login/oauth2/code/google` | Công khai | ✏️ Spring Security xử lý, trả 302 |
| A07 | POST | `/api/auth/oauth2/exchange` | Công khai | ➕ mới, set cookie refresh token |
| A08 | POST | `/api/auth/refresh` | Công khai | ✏️ đọc refresh token từ cookie |
| A09 | POST | `/api/auth/logout` | JWT | ✏️ đọc refresh token từ cookie |
| A10 | POST | `/api/auth/forgot-password` | Công khai | |
| A11 | POST | `/api/auth/reset-password` | Công khai | Cần OTP đúng |
| A12 | PATCH | `/api/auth/change-password` | JWT | |
| A13 | GET | `/api/users/me` | JWT | |
| A14 | POST | `/api/users/staff` | JWT + ADMIN | |

Ghi chú Gateway: route công khai là A01–A08, A10, A11 (khai theo cặp `METHOD /path`, sai method sẽ bị coi là cần token). Route cần JWT là A09, A12, A13, A14. Lỗi JWT của Gateway (mục 4) áp dụng cho mọi route cần JWT nên **không lặp lại** trong bảng lỗi từng API.

---

## 3. Chi tiết từng API

### A01 — Đăng ký

`POST /api/auth/register` · Công khai

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `email` | string | ✔ | mục 1.5 |
| `password` | string | ✔ | mục 1.5 |
| `fullName` | string | ✔ | mục 1.5 |

```json
{ "email": "buyer@example.com", "password": "Passw0rd123", "fullName": "Nguyễn Văn A" }
```

**Response 201**

```json
{
  "code": 200,
  "message": "Đăng ký thành công. Vui lòng kiểm tra email để lấy mã xác thực",
  "result": {
    "id": "0b6f1c3e-6f0c-4a55-9d3b-7b1f2a9c1e10",
    "email": "buyer@example.com",
    "fullName": "Nguyễn Văn A",
    "role": "BUYER",
    "status": "PENDING_VERIFICATION",
    "oauthProvider": "NONE",
    "mustChangePassword": false
  }
}
```

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ |
| 409 | 2001 | EMAIL_ALREADY_EXISTS | Email đã có user (chưa xóa mềm), kể cả user đang PENDING_VERIFICATION |

**Các bước thực thi**

1. Validate request; chuẩn hóa email.
2. Kiểm tra email đã tồn tại (`deleted_at IS NULL`) → `2001`. Đây chỉ là chặn sớm, không phải cơ chế chống race.
3. Băm mật khẩu bằng BCrypt (ngoài transaction, vì tốn CPU).
4. Trong một transaction (`TransactionTemplate`): insert `users` (role `BUYER`, status `PENDING_VERIFICATION`, `oauth_provider=NONE`, `must_change_password=false`). Bắt `DataIntegrityViolationException` → `2001`.
5. Sau commit: sinh OTP `EMAIL_VERIFICATION` (lưu Redis, mục 1.7) rồi publish `user.email-verification-requested` (userId, email, fullName, otpCode).
6. Trả 201.

**Lưu ý:** hai request đăng ký cùng email đến đồng thời sẽ cùng qua bước 2 → lớp bảo vệ cuối là partial unique index (`email` khi `deleted_at IS NULL`), được map thành `2001` thay vì để lộ ra 500.

---

### A02 — Xác thực email

`POST /api/auth/verify-email` · Công khai

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `email` | string | ✔ | mục 1.5 |
| `otp` | string | ✔ | 6 chữ số |

```json
{ "email": "buyer@example.com", "otp": "482913" }
```

**Response 200**

```json
{ "code": 200, "message": "Xác thực email thành công. Bạn có thể đăng nhập" }
```

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ |
| 400 | 2301 | OTP_INVALID | Email không tồn tại, không có OTP còn hiệu lực (chưa gửi hoặc đã hết hạn), hoặc sai mã |
| 429 | 2303 | OTP_ATTEMPTS_EXCEEDED | Đã nhập sai ≥ 5 lần với OTP này (phải gửi lại OTP mới) |
| 409 | 2002 | EMAIL_ALREADY_VERIFIED | User đã `ACTIVE` |
| 403 | 2103 | ACCOUNT_LOCKED | User đang `LOCKED` |

**Các bước thực thi**

1. Validate; chuẩn hóa email.
2. Tìm user theo email → không có → `2301` (cùng lỗi với OTP sai để không lộ email có tồn tại).
3. `status = ACTIVE` → `2002`; `status = LOCKED` → `2103`.
4. Đọc `otp:EMAIL_VERIFICATION:{userId}` từ Redis:
   - Không có key → `2301`.
   - `attemptCount ≥ 5` → `2303`.
5. So khớp hash OTP bằng so sánh **constant-time** (`MessageDigest.isEqual`).
   - Sai → tăng `attemptCount` và ghi lại **giữ nguyên TTL còn lại** (nếu đặt lại TTL đầy đủ, việc nhập sai liên tục sẽ tự gia hạn OTP vô thời hạn) → `2301`.
   - Đúng → xóa key OTP (dùng một lần).
6. Trong một transaction: cập nhật `users.status = ACTIVE`.
7. Trả 200 (không tự đăng nhập, không trả token).

---

### A03 — Gửi lại OTP xác thực email

`POST /api/auth/resend-verification` · Công khai

**Request body:** `{ "email": "buyer@example.com" }`

**Response 200** — **luôn** trả cùng nội dung dù email có tồn tại hay không:

```json
{ "code": 200, "message": "Nếu email hợp lệ, mã xác thực mới đã được gửi" }
```

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Email sai định dạng |

**Các bước thực thi**

1. Validate; chuẩn hóa email.
2. Tìm user; **chỉ tiếp tục nếu tồn tại và `status = PENDING_VERIFICATION`**, ngược lại trả 200 im lặng.
3. Cooldown: nếu tồn tại khóa `otp:cooldown:EMAIL_VERIFICATION:{userId}` → trả 200 im lặng, không gửi thêm (trả 429 sẽ làm lộ email tồn tại; frontend tự đếm ngược 60s ở nút "Gửi lại").
4. Sinh OTP mới. Việc ghi khóa OTP **ghi đè OTP cũ** nên không cần bước vô hiệu hóa riêng; đồng thời đặt lại khóa cooldown 60 giây.
5. Publish `user.email-verification-requested`.
6. Trả 200. (Không có lệnh ghi DB nên không cần transaction.)

---

### A04 — Đăng nhập bằng email/mật khẩu

`POST /api/auth/login` · Công khai

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `email` | string | ✔ | không rỗng |
| `password` | string | ✔ | không rỗng (không áp policy độ mạnh ở login) |

```json
{ "email": "buyer@example.com", "password": "Passw0rd123" }
```

**Response 200**

```
Set-Cookie: refreshToken=<jwt>; Path=/api/auth; Max-Age=604800; HttpOnly; SameSite=Lax
```

```json
{
  "code": 200,
  "result": {
    "auth": {
      "accessToken": "eyJhbGciOi…",
      "tokenType": "Bearer",
      "expiresIn": 900
    },
    "user": {
      "id": "0b6f1c3e-6f0c-4a55-9d3b-7b1f2a9c1e10",
      "email": "buyer@example.com",
      "fullName": "Nguyễn Văn A",
      "role": "BUYER",
      "status": "ACTIVE",
      "oauthProvider": "NONE",
      "mustChangePassword": false
    }
  }
}
```

`expiresIn` tính bằng giây. `user.mustChangePassword = true` với STAFF mới tạo (xem A14).

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ |
| 401 | 2101 | INVALID_CREDENTIALS | Email không tồn tại, sai mật khẩu, hoặc tài khoản chỉ có Google (`password_hash IS NULL`) |
| 403 | 2102 | ACCOUNT_NOT_VERIFIED | Mật khẩu **đúng** nhưng `status = PENDING_VERIFICATION` |
| 403 | 2103 | ACCOUNT_LOCKED | Mật khẩu **đúng** nhưng `status = LOCKED` |

**Các bước thực thi**

1. Validate; chuẩn hóa email.
2. Tìm user theo email (`deleted_at IS NULL`).
3. Kiểm tra mật khẩu. Nếu không tìm thấy user hoặc `password_hash` null → vẫn chạy một lần `matches` với hash giả để thời gian phản hồi không lộ email có tồn tại → `2101`. Sai mật khẩu → `2101`.
4. **Chỉ sau khi mật khẩu đúng** mới kiểm tra `status` (nếu kiểm tra trước, kẻ tấn công dò được email nào đã đăng ký/bị khóa): `PENDING_VERIFICATION` → `2102`; `LOCKED` → `2103`.
5. Phát hành token (dùng chung với A07, A08): sinh `family_id` mới; sinh refresh token (JWT, `token_type=refresh`); insert `refresh_tokens` (`family_id`, `user_id`, `token_hash`, `device_fingerprint_hash = NULL`).
6. Sinh access token (JWT, `token_type=access`, claim `mcp = must_change_password`).
7. Trả 200; refresh token đặt vào cookie, không nằm trong body.

**Lưu ý:** P1 **chưa thu thập** device fingerprint (cột để NULL) — việc này gắn với consent ở P8 và bot-detection ở P6. Chống brute-force mật khẩu (rate limit theo IP/tài khoản) thuộc P9; P1 chưa có cơ chế tự khóa tài khoản.

---

### A05 — Bắt đầu đăng nhập Google

`GET /api/oauth2/authorization/google` · Công khai

Path do Spring Security cố định theo dạng `/oauth2/authorization/{registrationId}` (cộng context-path `/api`), không có controller riêng.

**Request:** không có tham số.

**Response 302** → `Location: https://accounts.google.com/o/oauth2/v2/auth?client_id=…&redirect_uri=…&response_type=code&scope=openid%20email%20profile&state=<state>&nonce=<nonce>`

Không có lỗi nghiệp vụ ở bước này; mọi sự cố được báo ở callback (A06).

**Các bước thực thi (do Spring Security thực hiện)**

1. Sinh `state`/`nonce`, lưu vào **HttpSession** tạm (nên cần `SessionCreationPolicy.IF_REQUIRED`; các API khác vẫn không dùng session).
2. Trả 302 tới Google. `redirect_uri` là `{host gốc}/api/login/oauth2/code/google`; khi đi qua Gateway, `user-service` cần `server.forward-headers-strategy: framework` để nhận đúng host của Gateway, và URI này phải được khai y hệt trong Google Cloud Console (Authorized redirect URIs).

---

### A06 — Callback Google

`GET /api/login/oauth2/code/google?code=…&state=…` · Công khai

Do Spring Security (`OAuth2LoginAuthenticationFilter`) xử lý, sau đó gọi `OAuth2LoginSuccessHandler` / `OAuth2LoginFailureHandler`. Đây là request điều hướng trình duyệt nên **mọi kết quả (kể cả lỗi) đều là 302 về frontend**, không trả JSON.

**Response 302**

- Thành công: `Location: {FRONTEND_CALLBACK_URL}?code=<one-time-code>`
- Lỗi: `Location: {FRONTEND_CALLBACK_URL}?error=<errorCode>`

**Lỗi (errorCode đặt trong query `error`)**

| errorCode | Tên | Điều kiện |
|---|---|---|
| 2502 | OAUTH_CODE_INVALID | Mọi thất bại phía Spring Security: user bấm hủy ở Google (`access_denied`), `state` sai/thiếu/hết session, Google từ chối đổi `code`, lỗi kết nối Google |
| 2504 | OAUTH_EMAIL_NOT_VERIFIED | Google trả `email_verified ≠ true` |
| 2505 | OAUTH_ACCOUNT_CONFLICT | Email đã liên kết với một tài khoản Google **khác** (`oauth_id` khác `sub`) |
| 2103 | ACCOUNT_LOCKED | User `LOCKED` |

`2501 OAUTH_STATE_INVALID` và `2503 OAUTH_PROVIDER_UNAVAILABLE` vẫn được khai báo trong `UserErrorCode` nhưng **hiện không phát sinh** (các trường hợp đó đều gộp vào `2502` ở failure handler).

**Các bước thực thi**

1. Spring Security kiểm tra `state` với session, đổi `code` lấy token Google và tải thông tin user OIDC (`sub`, `email`, `email_verified`, `name`). Token của Google **chỉ dùng trong bước này**, không được lưu hay trả ra ngoài.
2. Thất bại → `OAuth2LoginFailureHandler`: hủy session, xóa `SecurityContext`, redirect `?error=2502`.
3. Thành công → `OAuth2LoginSuccessHandler`: hủy session, xóa `SecurityContext` (session chỉ phục vụ luồng OAuth; nếu để lại thì `SecurityContext` đã xác thực sống theo timeout của session), rồi xử lý nghiệp vụ:
   - `email_verified ≠ true` → `2504`.
   - Tìm user theo `(oauth_provider=GOOGLE, oauth_id=sub)`; nếu `LOCKED` → `2103`.
   - Không có thì tìm theo email:
      - **Có user theo email:** `oauth_id` đã có và khác `sub` → `2505`; `LOCKED` → `2103`. Nếu đang `PENDING_VERIFICATION` → đặt `ACTIVE` **và xóa `password_hash`** (chống pre-hijacking: kẻ khác đăng ký trước bằng email nạn nhân + mật khẩu của hắn thì mật khẩu đó không được sống sót). Sau đó gán `oauth_provider=GOOGLE`, `oauth_id=sub`.
      - **Chưa có:** tạo user mới (`role=BUYER`, `status=ACTIVE`, `password_hash=NULL`, `full_name = name` hoặc phần trước `@` nếu thiếu).
   - Sinh one-time code, lưu Redis `oauth:code:{code}` = `userId`, TTL **60 giây**. **Không** đưa token vào URL.
4. `AppException` ở bước 3 được bắt tại handler và đổi thành redirect `?error=<code>`; thành công thì redirect `?code=`.

**Lưu ý:** tài khoản đã liên kết Google **vẫn đăng nhập được bằng mật khẩu** nếu `password_hash` còn giá trị.

---

### A07 — Đổi one-time code lấy token (OAuth)

`POST /api/auth/oauth2/exchange` · Công khai

**Request body:** `{ "code": "<one-time-code>" }` (mục 1.5)

**Response 200:** giống A04 (body dạng `{auth, user}`, kèm `Set-Cookie: refreshToken=…`).

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Thiếu `code` |
| 400 | 2506 | OAUTH_EXCHANGE_CODE_INVALID | Code không tồn tại, đã dùng, hoặc hết hạn (60s) |
| 404 | 2601 | USER_NOT_FOUND | User của code không còn tồn tại / bị xóa mềm |
| 403 | 2103 | ACCOUNT_LOCKED | User bị khóa sau khi có code |

**Các bước thực thi**

1. Validate.
2. `GETDEL oauth:code:{code}` (đọc và xóa nguyên tử, chỉ dùng được đúng một lần) → không có → `2506`.
3. Tải user theo `userId`: không còn → `2601`; `LOCKED` → `2103`.
4. Phát hành token với `family_id` mới (giống bước 5–6 của A04). Trả 200.

---

### A08 — Làm mới access token (rotation + reuse detection)

`POST /api/auth/refresh` · Công khai (bảo vệ bằng refresh token)

**Request:** không có body. Refresh token lấy từ cookie `refreshToken` (trình duyệt tự gửi vì `Path=/api/auth`; frontend gọi với `credentials: 'include'`).

**Response 200:** giống A04. Cookie `refreshToken` mới thay cookie cũ; token cũ bị vô hiệu ngay.

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 401 | 2201 | REFRESH_TOKEN_INVALID | Thiếu cookie; JWT sai chữ ký / sai `token_type` / hết hạn; không tìm thấy hash trong DB; user không còn hợp lệ |
| 401 | 2202 | REFRESH_TOKEN_REUSED | Token đã bị thu hồi mà vẫn được dùng lại → toàn bộ family bị thu hồi |
| 403 | 2103 | ACCOUNT_LOCKED | User đã bị khóa (family cũng bị thu hồi) |

**Các bước thực thi**

1. Cookie thiếu/rỗng → `2201`.
2. Verify JWT bằng `verifyRefreshToken` (chữ ký, `exp`, `token_type=refresh`) → lỗi → `2201`. Đây là nguồn kiểm tra hạn dùng duy nhất.
3. Tính `SHA-256(token)`, tìm trong `refresh_tokens` → không có → `2201`.
4. Nếu `revoked_at != NULL` → **REUSE DETECTED**: thu hồi mọi token cùng `family_id` chưa bị thu hồi (`UPDATE … SET revoked_at = now WHERE family_id = ? AND revoked_at IS NULL`) **trong transaction riêng và commit trước khi ném lỗi** (mục 1.6 điểm 3), rồi trả `2202`.
5. Kiểm tra user còn tồn tại (chưa xóa mềm) và `status = ACTIVE` → nếu không: thu hồi family; `LOCKED` → `2103`, còn lại → `2201`.
6. Thu hồi token hiện tại **nguyên tử**: `UPDATE … SET revoked_at = now WHERE id = ? AND revoked_at IS NULL`. Affected rows = 0 nghĩa là request khác vừa dùng token này → coi như reuse (thu hồi family, `2202`).
7. Phát hành cặp token mới **cùng `family_id`** (access token mới lấy `mcp` từ `users.must_change_password`), đặt cookie mới. Trả 200.

**Lưu ý:** frontend phải **serialize** việc gọi refresh (chỉ một request refresh tại một thời điểm, các request khác chờ kết quả). Nếu hai tab/hai request cùng gửi một refresh token, request thứ hai sẽ bị coi là reuse và **đá người dùng thật ra khỏi mọi thiết bị** — đây là hành vi đúng theo thiết kế, nhưng dễ gặp do lỗi ở client.

---

### A09 — Đăng xuất

`POST /api/auth/logout` · Cần JWT

**Request:** không có body. Refresh token lấy từ cookie `refreshToken`; danh tính lấy từ header `X-User-Id` do Gateway inject.

**Response 200**

```
Set-Cookie: refreshToken=; Path=/api/auth; Max-Age=0; HttpOnly; SameSite=Lax
```

```json
{ "code": 200, "message": "Đăng xuất thành công" }
```

**Lỗi:** không có lỗi nghiệp vụ (logout luôn idempotent). Lỗi JWT do Gateway xử lý ở mục 4.

**Các bước thực thi**

1. Cookie thiếu/rỗng → bỏ qua bước thu hồi, vẫn xóa cookie và trả 200.
2. Băm token, tìm trong DB.
3. Nếu tìm thấy **và** `user_id` khớp `X-User-Id` → thu hồi toàn bộ token chưa thu hồi của `family_id` đó (transaction riêng).
4. Nếu không tìm thấy, hoặc thuộc user khác → **không làm gì, vẫn trả 200** (không lộ thông tin về token của người khác).
5. Trả 200 kèm cookie xóa (`Max-Age=0`).

**Giới hạn đã biết:** access token hiện tại vẫn dùng được đến hết TTL (15 phút) vì Gateway verify stateless; nếu cần thu hồi tức thì có thể blacklist `jti` trong Redis (bàn sau). Vì API này cần access token hợp lệ nên nếu token đã hết hạn (`9003`), frontend phải refresh trước rồi mới logout.

---

### A10 — Quên mật khẩu

`POST /api/auth/forgot-password` · Công khai

**Request body:** `{ "email": "buyer@example.com" }`

**Response 200** — luôn cùng nội dung:

```json
{ "code": 200, "message": "Nếu email hợp lệ, mã đặt lại mật khẩu đã được gửi" }
```

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Email sai định dạng |

**Các bước thực thi**

1. Validate; chuẩn hóa email.
2. Chỉ tiếp tục nếu user tồn tại và `status = ACTIVE`; ngược lại (không tồn tại / `PENDING_VERIFICATION` / `LOCKED`) trả 200 im lặng.
3. Cooldown: nếu tồn tại khóa `otp:cooldown:FORGOT_PASSWORD:{userId}` → trả 200 im lặng.
4. Sinh OTP `FORGOT_PASSWORD` (ghi đè OTP cũ nếu còn, đặt cooldown 60 giây).
5. Publish `user.forgot-password-requested`.
6. Trả 200.

**Lưu ý:** tài khoản chỉ đăng nhập Google (`password_hash = NULL`) vẫn dùng được luồng này — kết quả là họ **đặt** một mật khẩu để đăng nhập thêm bằng email/mật khẩu. A10 và A11 là hai bước của **một vòng đời** "quên mật khẩu": A11 vừa xác thực OTP vừa đổi mật khẩu, không có bước verify OTP riêng.

---

### A11 — Đặt lại mật khẩu

`POST /api/auth/reset-password` · Công khai (bảo vệ bằng OTP)

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `email` | string | ✔ | mục 1.5 |
| `otp` | string | ✔ | 6 chữ số |
| `newPassword` | string | ✔ | mục 1.5 |

**Response 200:** `{ "code": 200, "message": "Đặt lại mật khẩu thành công. Vui lòng đăng nhập lại" }`

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ (gồm mật khẩu mới không đạt policy) |
| 400 | 2301 | OTP_INVALID | Email không tồn tại / không `ACTIVE`, không có OTP còn hiệu lực, hoặc sai mã |
| 429 | 2303 | OTP_ATTEMPTS_EXCEEDED | Sai ≥ 5 lần |

**Các bước thực thi**

1. Validate; chuẩn hóa email.
2. Tìm user `ACTIVE` theo email → không có → `2301`.
3. Xác thực OTP `FORGOT_PASSWORD` như bước 4–5 của A02 (sai → tăng `attemptCount` giữ nguyên TTL → `2301`; đúng → xóa key OTP).
4. Trong **một transaction**: cập nhật `password_hash`; đặt `must_change_password = false`; **thu hồi toàn bộ refresh token** của user (mọi family) — kẻ đang giữ refresh token cũ mất quyền truy cập.
5. Trả 200 (không tự đăng nhập).

---

### A12 — Đổi mật khẩu

`PATCH /api/auth/change-password` · Cần JWT (dùng cả cho STAFF đổi lần đầu)

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `oldPassword` | string | ✔ | không rỗng |
| `newPassword` | string | ✔ | mục 1.5 |

**Response 200:** `{ "code": 200, "message": "Đổi mật khẩu thành công. Vui lòng đăng nhập lại" }`

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ |
| 400 | 2401 | PASSWORD_INCORRECT | `oldPassword` sai |
| 400 | 2402 | NEW_PASSWORD_SAME_AS_OLD | `newPassword` trùng mật khẩu hiện tại |
| 400 | 2403 | PASSWORD_NOT_SET | Tài khoản chưa có mật khẩu (chỉ Google) → hướng dẫn dùng luồng "Quên mật khẩu" để đặt |
| 404 | 2601 | USER_NOT_FOUND | User trong JWT không còn tồn tại / bị xóa mềm |

**Các bước thực thi**

1. Đọc `X-User-Id`, tải user (không tồn tại / bị xóa mềm → `2601`).
2. `password_hash IS NULL` → `2403`.
3. `oldPassword` không khớp → `2401`. `newPassword` trùng mật khẩu hiện tại → `2402`.
4. Trong một transaction: cập nhật `password_hash`; `must_change_password = false`; **thu hồi toàn bộ refresh token** của user.
5. Trả 200. Client xóa token cục bộ và đăng nhập lại (lấy access token mới với `mcp = false`; access token cũ mang `mcp = true` vẫn sẽ bị Gateway chặn ở các API khác).

**Lưu ý:** đổi mật khẩu dùng `2401` (400) chứ không dùng 401 — người dùng đã xác thực hợp lệ, lỗi nằm ở dữ liệu nhập; dùng 401 sẽ làm frontend nhầm là token hết hạn và kích hoạt luồng refresh.

---

### A13 — Xem thông tin tài khoản

`GET /api/users/me` · Cần JWT

**Response 200**

```json
{
  "code": 200,
  "result": {
    "id": "0b6f1c3e-6f0c-4a55-9d3b-7b1f2a9c1e10",
    "email": "buyer@example.com",
    "fullName": "Nguyễn Văn A",
    "role": "BUYER",
    "status": "ACTIVE",
    "oauthProvider": "NONE",
    "mustChangePassword": false
  }
}
```

Không bao giờ trả `password_hash`, `oauth_id`, `deleted_at`.

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 404 | 2601 | USER_NOT_FOUND | User trong JWT không còn tồn tại / bị xóa mềm sau khi token được cấp |

**Các bước thực thi**

1. Đọc `X-User-Id`.
2. Tải user (`deleted_at IS NULL`) → không có → `2601`.
3. Map Entity → DTO bằng tay (`UserMapper`); trả 200.

---

### A14 — ADMIN tạo tài khoản STAFF

`POST /api/users/staff` · Cần JWT, role `ADMIN`

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `email` | string | ✔ | mục 1.5 |
| `fullName` | string | ✔ | tối đa 100 ký tự |

**Response 201**

```json
{
  "code": 200,
  "message": "Tạo tài khoản STAFF thành công. Mật khẩu tạm đã được gửi qua email",
  "result": {
    "id": "5d1a7c92-2b1f-4d0e-8f43-0c6d0f1b7a22",
    "email": "staff@example.com",
    "fullName": "Trần Thị B",
    "role": "STAFF",
    "status": "ACTIVE",
    "oauthProvider": "NONE",
    "mustChangePassword": true
  }
}
```

**Không** trả mật khẩu tạm trong response.

**Lỗi**

| HTTP | errorCode | Tên | Điều kiện |
|---|---|---|---|
| 400 | 1001 | VALIDATION_FAILED | Request không hợp lệ (validation chạy trước kiểm tra quyền, nên user không phải ADMIN gửi body sai sẽ nhận `400` thay vì `403`) |
| 403 | 1004 | ACCESS_DENIED | Role không phải `ADMIN` |
| 409 | 2001 | EMAIL_ALREADY_EXISTS | Email đã tồn tại |

**Các bước thực thi**

1. `@PreAuthorize("hasRole('ADMIN')")` (cần `@EnableMethodSecurity`; dựa vào `SecurityContext` do `GatewayHeaderAuthFilter` dựng từ `X-User-Role` với authority `ROLE_<role>`) → không đạt → `AuthorizationDeniedException` → `1004`. Không dùng rule URL `hasRole` ở `SecurityConfig` vì lỗi 403 từ filter chain không đi qua `GlobalExceptionHandler`, sẽ không đúng format JSON.
2. Validate; chuẩn hóa email; kiểm tra tồn tại → `2001` (kèm bắt unique violation như A01).
3. Sinh mật khẩu tạm bằng `SecureRandom` (12 ký tự, bỏ các ký tự dễ nhầm `I l O 0 1`, luôn có ít nhất 1 chữ cái và 1 chữ số); băm BCrypt.
4. Insert user: `role=STAFF`, `status=ACTIVE` (email do ADMIN cung cấp, việc dùng được mật khẩu tạm gửi tới email đó là bằng chứng sở hữu), `must_change_password=true`, `oauth_provider=NONE`.
5. Sau commit: publish `user.staff-account-created` (userId, email, fullName, `temporaryPassword`). **Không log payload này.**
6. Trả 201.

**Luồng tiếp theo của STAFF:** login (A04) → `user.mustChangePassword: true`, access token mang `mcp=true` → chỉ gọi được A12/A09/A13 (mục 4) → đổi mật khẩu → đăng nhập lại.

**Nếu Kafka lỗi ngay sau commit:** tài khoản đã tạo nhưng chưa ai có mật khẩu tạm. Lối thoát: STAFF dùng luồng quên mật khẩu (A10 → A11) vì tài khoản đã `ACTIVE`.

---

## 4. Xác thực JWT tại Gateway

Triển khai bằng `GlobalFilter` (`JwtAuthenticationGlobalFilter`, order cao nhất) trong `api-gateway` (WebFlux). Gateway dùng `commonlib-jwt`, **không** dùng `commonlib-security` (module servlet).

Thứ tự xử lý mỗi request:

1. **Luôn xóa** `X-User-Id` và `X-User-Role` do client gửi, kể cả ở route công khai.
2. Khớp `METHOD + path` với danh sách route công khai (`app.gateway.public-endpoints`) → cho qua, không cần token. Sai method hoặc path không có trong danh sách được coi là route cần JWT.
3. Đọc `Authorization: Bearer <token>`, verify bằng `verifyAccessToken` (chữ ký, `exp`, `token_type=access`).
4. Nếu `mcp=true` và request không thuộc allowlist (`app.gateway.password-change-allowlist`) → `9004`.
5. Inject `X-User-Id` (= `sub`) và `X-User-Role` (= `role`), chuyển tiếp tới service.

| HTTP | errorCode | Tên | Điều kiện | Frontend xử lý |
|---|---|---|---|---|
| 401 | 9001 | TOKEN_MISSING | Thiếu header `Authorization: Bearer …` | Chuyển tới trang đăng nhập |
| 401 | 9002 | TOKEN_INVALID | Sai chữ ký / sai định dạng / **không phải access token** (ví dụ dùng refresh token làm Bearer) / thiếu `sub` hoặc `role` | Xóa token, đăng nhập lại |
| 401 | 9003 | TOKEN_EXPIRED | Access token hết hạn | **Gọi A08 refresh rồi thử lại** |
| 403 | 9004 | PASSWORD_CHANGE_REQUIRED | JWT có `mcp=true` mà gọi API ngoài allowlist | Chuyển tới màn hình đổi mật khẩu |

Route công khai (khai dạng `METHOD /path`): `POST /api/auth/register`, `verify-email`, `resend-verification`, `login`, `oauth2/exchange`, `refresh`, `forgot-password`, `reset-password`; `GET /api/oauth2/authorization/**`; `GET /api/login/oauth2/code/**`; `GET /api/*/health` (health của các service Python).

Allowlist khi `mcp=true`: `PATCH /api/auth/change-password`, `POST /api/auth/logout`, `GET /api/users/me`.

Route `user-service` trên Gateway gồm: `/api/users/**`, `/api/auth/**`, `/api/oauth2/**`, `/api/login/oauth2/**`.

CORS cấu hình ở Gateway (`globalcors`): origin cụ thể lấy từ biến môi trường (không dùng `*`), `allow-credentials: true` để trình duyệt gửi/nhận cookie refresh token; frontend gọi với `credentials: 'include'`.

Tách `9002` và `9003` là **có chủ đích**: frontend chỉ được tự động refresh khi token *hết hạn*; nếu token *sai* mà cứ refresh sẽ thành vòng lặp vô ích.

Ngoài ra, service phía sau vẫn có thể trả `1003 UNAUTHENTICATED` (401) nếu request chưa xác thực — chỉ xảy ra khi gọi thẳng service không qua Gateway.

---

## 5. Bảng errorCode tổng hợp (P1)

| Code | Tên | HTTP | Message (mặc định) |
|---|---|---|---|
| 1000 | INTERNAL_ERROR | 500 | Lỗi hệ thống, vui lòng thử lại sau |
| 1001 | VALIDATION_FAILED | 400 | Dữ liệu không hợp lệ |
| 1002 | MALFORMED_REQUEST | 400 | Nội dung request không đúng định dạng |
| 1003 | UNAUTHENTICATED | 401 | Chưa xác thực |
| 1004 | ACCESS_DENIED | 403 | Bạn không có quyền thực hiện thao tác này |
| 1005 | DATA_CONFLICT | 409 | Dữ liệu bị xung đột (lưới an toàn cho `DataIntegrityViolationException` chưa được service xử lý riêng) |
| 2001 | EMAIL_ALREADY_EXISTS | 409 | Email đã được sử dụng |
| 2002 | EMAIL_ALREADY_VERIFIED | 409 | Email đã được xác thực trước đó |
| 2101 | INVALID_CREDENTIALS | 401 | Email hoặc mật khẩu không đúng |
| 2102 | ACCOUNT_NOT_VERIFIED | 403 | Tài khoản chưa xác thực email |
| 2103 | ACCOUNT_LOCKED | 403 | Tài khoản đã bị khóa |
| 2201 | REFRESH_TOKEN_INVALID | 401 | Phiên đăng nhập không hợp lệ hoặc đã hết hạn |
| 2202 | REFRESH_TOKEN_REUSED | 401 | Phiên đăng nhập bị thu hồi vì lý do bảo mật, vui lòng đăng nhập lại |
| 2301 | OTP_INVALID | 400 | Mã xác thực không đúng hoặc đã hết hạn |
| 2303 | OTP_ATTEMPTS_EXCEEDED | 429 | Bạn đã nhập sai quá số lần cho phép, vui lòng yêu cầu mã mới |
| 2401 | PASSWORD_INCORRECT | 400 | Mật khẩu hiện tại không đúng |
| 2402 | NEW_PASSWORD_SAME_AS_OLD | 400 | Mật khẩu mới không được trùng mật khẩu hiện tại |
| 2403 | PASSWORD_NOT_SET | 400 | Tài khoản chưa có mật khẩu, hãy dùng chức năng quên mật khẩu để đặt |
| 2501 | OAUTH_STATE_INVALID | 400 | Phiên đăng nhập Google không hợp lệ *(hiện không phát sinh, gộp vào 2502)* |
| 2502 | OAUTH_CODE_INVALID | 400 | Đăng nhập Google không thành công |
| 2503 | OAUTH_PROVIDER_UNAVAILABLE | 502 | Không thể kết nối tới Google, vui lòng thử lại *(hiện không phát sinh, gộp vào 2502)* |
| 2504 | OAUTH_EMAIL_NOT_VERIFIED | 403 | Email Google chưa được xác minh |
| 2505 | OAUTH_ACCOUNT_CONFLICT | 409 | Email đã liên kết với một tài khoản Google khác |
| 2506 | OAUTH_EXCHANGE_CODE_INVALID | 400 | Mã đăng nhập không hợp lệ hoặc đã hết hạn |
| 2601 | USER_NOT_FOUND | 404 | Không tìm thấy người dùng |
| 9001 | TOKEN_MISSING | 401 | Thiếu access token |
| 9002 | TOKEN_INVALID | 401 | Access token không hợp lệ |
| 9003 | TOKEN_EXPIRED | 401 | Access token đã hết hạn |
| 9004 | PASSWORD_CHANGE_REQUIRED | 403 | Bạn cần đổi mật khẩu trước khi tiếp tục |

Mã `2302 OTP_EXPIRED` đã bị bỏ (gộp vào `2301`, xem mục 0 điểm 3).

---

## 6. Cấu hình liên quan

| Khóa | Dùng ở | Ghi chú |
|---|---|---|
| `app.jwt.secret-key` | user-service, api-gateway | **Cùng một giá trị** ở hai nơi; đọc từ biến môi trường, không commit |
| `app.jwt.expiration`, `app.jwt.expiration-refresh` | user-service | Ví dụ `15m`, `7d`; Gateway chỉ verify nên không cần |
| `app.otp.hmac-secret` | user-service | Secret HMAC cho hash OTP, từ biến môi trường |
| `app.cookie.secure`, `app.cookie.same-site` | user-service | Xem mục 1.4 |
| `spring.security.oauth2.client.registration.google.*` | user-service | `client-id`, `client-secret`, `scope: openid,email,profile` |
| `app.oauth2.frontend-callback-url` | user-service | Nơi A06 redirect về (kèm `?code=` hoặc `?error=`) |
| `server.forward-headers-strategy: framework` | user-service | Để `redirect_uri` OAuth theo host của Gateway |
| `app.gateway.public-endpoints`, `app.gateway.password-change-allowlist` | api-gateway | Danh sách `METHOD /path` |
| `spring.cloud.gateway.server.webflux.globalcors` | api-gateway | Origin frontend, `allow-credentials: true` |
| `app.run-config.default-admin.*`, `app.run-config.default-staff.*` | user-service | Tài khoản seed (mục 7) |

Dependency: `user-service` dùng `commonlib-api-response`, `commonlib-kafka`, `commonlib-security`, `commonlib-jwt`; `api-gateway` dùng `commonlib-jwt` và `commonlib-api-response`, không dùng `commonlib-security`.

---

## 7. Dữ liệu seed (môi trường dev)

`ApplicationRunConfig` (một `ApplicationRunner`) tạo sẵn 1 tài khoản `ADMIN` và 1 tài khoản `STAFF` khi khởi động nếu email chưa tồn tại, đọc từ `app.run-config.default-admin.*` / `default-staff.*`. Cả hai `status=ACTIVE`, `must_change_password=false`, email được chuẩn hóa (trim + lowercase); khởi động lại không tạo trùng. Đây là cách có tài khoản ADMIN đầu tiên để gọi A14.

Chỉ dùng cho dev: không commit mật khẩu thật vào file cấu hình (lấy từ biến môi trường), và phải tắt/giới hạn theo profile khi deploy (P9).

---

## 8. Giới hạn đã biết

1. **Access token còn hiệu lực đến hết TTL** sau logout hoặc đổi mật khẩu (Gateway verify stateless, không tra DB). Chấp nhận ở P1.
2. **Chưa có rate limiting / khóa tài khoản** khi sai mật khẩu nhiều lần (thuộc P9). OTP thì có giới hạn 5 lần.
3. **Kafka không dùng outbox:** mất event nếu Kafka lỗi ngay sau commit; lối thoát là `resend-verification` / `forgot-password`.
4. **OTP ở Redis không được xử lý nguyên tử:** bộ đếm sai và bước "kiểm tra rồi xóa" là đọc-rồi-ghi, nên dưới tải đồng thời số lần đoán thực tế có thể vượt 5 một chút, và hai request đúng OTP cùng lúc đều có thể qua. Đủ cho mức P1; nếu cần siết thì chuyển sang `INCR`/Lua script. Ngoài ra không phân biệt được "hết hạn" với "chưa từng có" (mục 0 điểm 3).
5. **Chưa thu thập device fingerprint** (cột `device_fingerprint_hash` để NULL) — thuộc P6/P8.
6. **Trust-The-Gateway:** cổng của service không được expose trực tiếp; siết ở P9.
7. **Cookie khác domain:** khi frontend và backend khác domain cần `SameSite=None` + `Secure` (HTTPS kể cả dev).
8. **Frontend phải serialize refresh** (A08), nếu không sẽ bị reuse detection đá ra khỏi mọi thiết bị.
9. **Preflight `OPTIONS` qua Gateway** chưa được kiểm chứng đầy đủ — cần kiểm tra khi tích hợp frontend.

---

*Hết tài liệu API design P1 (đã cập nhật khớp code). Bước tiếp theo: cập nhật `P1_auth_design.md` theo mục 0, rồi sang P2 — Product.*