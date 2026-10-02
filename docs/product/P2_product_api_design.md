# Vantix — Phase 2: Product — API Design

> **Trạng thái:** Thiết kế — dùng để code theo, chưa phải tài liệu tổng kết sau khi làm xong.
> **Liên quan:** `P2_product_design.md` (quyết định chốt, vòng đời Event, use case, ERD, sequence), `P1_auth_api_design.md` (quy ước chung dùng lại), `00_roadmap_phases_v1.md` (mục P2)
> **Phạm vi:** toàn bộ API của `product-service` trong P2 (Venue, Event, SeatMap/Seat, TicketType). Mỗi API mô tả: request, response, lỗi (HTTP status + errorCode), các bước thực thi.

---

## 0. Giải quyết 2 backlog đã treo ở `P2_product_design.md`

| Backlog | Quyết định | Áp dụng ở P2 |
|---|---|---|
| #1 — Trigger hoàn tiền khi hủy sự kiện | **Hướng A**: `product-service` publish Kafka `product.event-cancelled` ngay khi ADMIN gọi `cancel` | **Implement ngay trong P2** (chỉ phần publish; chưa có consumer vì `order-service` chưa tồn tại tới P3/P4) |
| #2 — Đồng bộ trạng thái ghế real-time | **Hướng C**: `order-service` (P3) publish Kafka `order.seat-status-changed`, `product-service` consume để cache trạng thái hiển thị lên `Seat`, chấp nhận độ trễ nhỏ | **Chưa implement ở P2** — chỉ đặt tên topic trước (mục 8) để nhất quán khi tới P3; P2 không có cột lưu trạng thái bán trên `Seat`, không có consumer |

Cả hai cùng một họ giải pháp (Kafka đồng bộ trạng thái giữa các service, chấp nhận độ trễ nhỏ, không thay thế ràng buộc DB chống bán trùng ở Order) — nhất quán với cách Payment→Order đã làm ở roadmap mục 3.3.

---

## 1. Quy ước chung (dùng lại từ P1, không lặp lại chi tiết)

- **Format response**: `ApiResponse<T>` giống hệt `P1_auth_api_design.md` mục 1.1 (`code` mặc định `200` khi thành công dù HTTP status là gì; `message` bỏ qua nếu null).
- **HTTP status**: theo đúng bảng ở `P1_auth_api_design.md` mục 1.3 (200/201/400/401/403/404/409/429/500/502). P2 không phát sinh HTTP 302/429.
- **`CommonErrorCode` (dải `1000–1999`)**: dùng lại nguyên, không định nghĩa thêm: `1000 INTERNAL_ERROR`, `1001 VALIDATION_FAILED`, `1002 MALFORMED_REQUEST`, `1003 UNAUTHENTICATED`, `1004 ACCESS_DENIED`, `1005 DATA_CONFLICT`.
- **Ngày giờ**: `java.time.Instant`, ISO-8601 UTC trong JSON (theo quy ước đã chốt toàn dự án).
- **Phân quyền ghi**: `@PreAuthorize("hasRole('ADMIN')")` ở tầng service/controller (không chặn bằng URL matcher ở `SecurityConfig`) — lý do giống hệt A14 ở P1: lỗi 403 từ filter chain không đi qua `GlobalExceptionHandler` nên sai format JSON.
- **Trust-The-Gateway**: đọc `X-User-Id`/`X-User-Role` do Gateway inject, không tự verify JWT.
- **Gateway route công khai cần thêm**: mọi `GET` liệt kê ở mục 3 (xem bảng mục 2) phải được thêm vào `app.gateway.public-endpoints` của `api-gateway`, path khai `/api/venues`, `/api/events`, `/api/events/*/seat-map` (wildcard vì có path variable).

---

## 2. Tổng hợp API

| ID | Method | Path | Auth | Ghi chú |
|---|---|---|---|---|
| PR01 | POST | `/api/venues` | ADMIN | |
| PR02 | GET | `/api/venues` | Công khai | Không phân trang (dữ liệu nhỏ, ADMIN quản lý) |
| PR03 | POST | `/api/events` | ADMIN | Tạo sự kiện, status=DRAFT |
| PR04 | PATCH | `/api/events/{id}` | ADMIN | Sửa thông tin sự kiện (một số field giới hạn theo status) |
| PR05 | PATCH | `/api/events/{id}/publish` | ADMIN | DRAFT → PUBLISHED |
| PR06 | PATCH | `/api/events/{id}/cancel` | ADMIN | DRAFT/PUBLISHED → CANCELLED, publish Kafka |
| PR07 | GET | `/api/events` | Công khai | Danh sách, chỉ PUBLISHED, phân trang |
| PR08 | GET | `/api/events/{id}` | Công khai | Chi tiết, PUBLISHED hoặc CANCELLED |
| PR09 | POST | `/api/events/{id}/seat-map` | ADMIN | Tạo sơ đồ ghế (1 lần/event) |
| PR10 | POST | `/api/events/{id}/seat-map/seats` | ADMIN | Thêm ghế hàng loạt |
| PR11 | GET | `/api/events/{id}/seat-map` | Công khai | Xem sơ đồ ghế + hạng vé từng ghế |
| PR12 | POST | `/api/events/{id}/ticket-types` | ADMIN | Tạo hạng vé (SEATED gán seatIds, hoặc GA kèm totalQuantity) |
| PR13 | PATCH | `/api/ticket-types/{id}` | ADMIN | Sửa `name`/`price` |

---

## 3. Quy tắc validation dùng chung

| Field | Quy tắc |
|---|---|
| `name` (venue/event/ticket type) | Bắt buộc, trim, 1–200 ký tự |
| `address` | Bắt buộc, 1–255 ký tự |
| `city` | Bắt buộc, 1–100 ký tự |
| `description` | Tùy chọn, tối đa 2000 ký tự |
| `bannerImageUrl` | Tùy chọn, phải là URL hợp lệ (`http://`/`https://`), tối đa 500 ký tự |
| `startAt` | Bắt buộc, phải ở **tương lai** so với thời điểm gọi API |
| `endAt` | Bắt buộc, phải **sau** `startAt` |
| `salesStartAt` / `salesEndAt` | Tùy chọn; nếu có cả hai: `salesStartAt < salesEndAt`; nếu chỉ có `salesEndAt`: phải `<= startAt` |
| `section` / `rowLabel` / `seatNumber` | Bắt buộc, trim, tối đa 50/20/20 ký tự |
| `seats` (mảng, PR10) | Bắt buộc, 1–2000 phần tử/request (giới hạn kích thước payload) |
| `category` (ticket type) | Bắt buộc, `SEATED` hoặc `GENERAL_ADMISSION` |
| `price` | Bắt buộc, `>= 0`, tối đa 2 chữ số thập phân (cho phép vé miễn phí) |
| `totalQuantity` (GA) | Bắt buộc **khi** `category=GENERAL_ADMISSION`, số nguyên `> 0`. Không được gửi khi `category=SEATED` |
| `seatIds` | Bắt buộc **khi** `category=SEATED`, mảng UUID không rỗng. Không được gửi khi `category=GENERAL_ADMISSION` |

---

## 4. Nguyên tắc kỹ thuật áp dụng cho nhiều API

1. **Không cần idempotency key**: khác Order/Payment (P3/P4), API ở đây chỉ ADMIN thao tác tay, tần suất thấp, không có rủi ro retry trùng ở quy mô cần chống.
2. **Transaction**: dùng `@Transactional` ở mức method service bình thường — khác với `user-service` (phải dùng `TransactionTemplate` bọc riêng vì có pattern "ghi rồi mới ném lỗi" như reuse detection), ở đây **không có** pattern đó, nên `@Transactional` tiêu chuẩn là đủ và đơn giản hơn.
3. **Publish Kafka sau khi commit**: `product.event-cancelled` chỉ publish sau khi transaction đổi `status=CANCELLED` đã commit, lỗi publish chỉ log, không rollback và không trả lỗi cho ADMIN (giống nguyên tắc đã chốt ở P1 mục 1.6 điểm 2).
4. **Thêm ghế hàng loạt (PR10) là tất-cả-hoặc-không-gì**: một transaction duy nhất; bất kỳ ghế nào trùng (`section`+`rowLabel`+`seatNumber` trong cùng seat map, hoặc trùng ngay trong chính payload gửi lên) khiến toàn bộ request thất bại, không insert một phần.
5. **Validate seat thuộc đúng event**: khi tạo `TicketType` loại SEATED (PR12), mọi `seatId` phải thuộc `SeatMap` của **chính event trong path**, không được tham chiếu ghế của event khác — chặn ở tầng service trước khi đụng DB.
6. **Ẩn sự kiện DRAFT khỏi API công khai**: `GET /events` và `GET /events/{id}` không bao giờ trả event `DRAFT`. Với `GET /events/{id}`, event không tồn tại và event đang `DRAFT` dùng **chung một lỗi** `3101 EVENT_NOT_FOUND` — không để lộ sự tồn tại của event chưa publish (cùng tinh thần chống enumeration đã áp dụng ở P1).
7. **Không dùng soft-delete** cho `Venue`/`SeatMap`/`Seat`/`TicketType` ở P2 (đúng quyết định đã ghi ở `P2_product_design.md` mục 4) — các ràng buộc trạng thái (chỉ sửa được khi DRAFT) đóng vai trò bảo vệ thay cho xóa mềm.

---

## 5. Bảng errorCode (dải `3000–3999`)

| Nhóm | Phạm vi |
|---|---|
| `30xx` | Venue |
| `31xx` | Event |
| `32xx` | SeatMap / Seat |
| `33xx` | TicketType |

| Code | Tên | HTTP | Message (mặc định) |
|---|---|---|---|
| 3001 | VENUE_NOT_FOUND | 404 | Không tìm thấy địa điểm |
| 3101 | EVENT_NOT_FOUND | 404 | Không tìm thấy sự kiện |
| 3102 | EVENT_NOT_IN_DRAFT | 409 | Thao tác này chỉ thực hiện được khi sự kiện đang ở trạng thái nháp |
| 3103 | EVENT_ALREADY_CANCELLED | 409 | Sự kiện đã bị hủy |
| 3104 | EVENT_ALREADY_PUBLISHED | 409 | Sự kiện đã được publish trước đó |
| 3105 | EVENT_PUBLISH_VALIDATION_FAILED | 400 | Sự kiện chưa đủ điều kiện để publish |
| 3106 | EVENT_TIME_RANGE_INVALID | 400 | Thời gian diễn ra sự kiện không hợp lệ |
| 3107 | SALES_WINDOW_INVALID | 400 | Khung thời gian mở bán không hợp lệ |
| 3201 | SEAT_MAP_ALREADY_EXISTS | 409 | Sự kiện đã có sơ đồ ghế |
| 3202 | SEAT_MAP_NOT_FOUND | 404 | Sự kiện chưa có sơ đồ ghế |
| 3203 | DUPLICATE_SEAT | 409 | Ghế bị trùng (cùng khu vực/hàng/số ghế) |
| 3204 | SEAT_NOT_FOUND | 404 | Không tìm thấy ghế |
| 3205 | SEAT_ALREADY_ASSIGNED | 409 | Ghế đã được gán cho một hạng vé khác |
| 3206 | SEAT_BELONGS_TO_DIFFERENT_EVENT | 400 | Ghế không thuộc sự kiện này |
| 3301 | TICKET_TYPE_NOT_FOUND | 404 | Không tìm thấy hạng vé |
| 3302 | GA_QUANTITY_REQUIRED | 400 | Hạng vé GA phải có số lượng lớn hơn 0 |
| 3303 | SEAT_IDS_REQUIRED_FOR_SEATED | 400 | Hạng vé SEATED phải gán ít nhất một ghế |

---

## 6. Chi tiết từng API

### PR01 — Tạo địa điểm

`POST /api/venues` · ADMIN

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `name` | string | ✔ | mục 3 |
| `address` | string | ✔ | mục 3 |
| `city` | string | ✔ | mục 3 |

**Response 201**

```json
{
  "code": 200,
  "result": { "id": "b1e...", "name": "Nhà hát Hòa Bình", "address": "240 3/2", "city": "Hồ Chí Minh" }
}
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi:** `@PreAuthorize("hasRole('ADMIN')")` → validate → insert `venues` → trả 201.

---

### PR02 — Danh sách địa điểm

`GET /api/venues` · Công khai

**Response 200**

```json
{ "code": 200, "result": [ { "id": "b1e...", "name": "...", "address": "...", "city": "..." } ] }
```

Không phân trang; mảng rỗng nếu chưa có venue nào. Không có lỗi nghiệp vụ.

---

### PR03 — Tạo sự kiện

`POST /api/events` · ADMIN

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `venueId` | UUID | ✔ | phải tồn tại |
| `name` | string | ✔ | mục 3 |
| `description` | string | ✘ | mục 3 |
| `startAt` | Instant | ✔ | mục 3 |
| `endAt` | Instant | ✔ | mục 3 |
| `salesStartAt` | Instant | ✘ | mục 3 |
| `salesEndAt` | Instant | ✘ | mục 3 |
| `bannerImageUrl` | string | ✘ | mục 3 |

**Response 201**

```json
{
  "code": 200,
  "message": "Tạo sự kiện thành công",
  "result": {
    "id": "e7a...",
    "venueId": "b1e...",
    "name": "Đêm nhạc Trịnh",
    "description": null,
    "startAt": "2026-12-01T10:00:00Z",
    "endAt": "2026-12-01T13:00:00Z",
    "salesStartAt": null,
    "salesEndAt": null,
    "status": "DRAFT",
    "bannerImageUrl": null,
    "createdAt": "2026-10-01T02:00:00Z"
  }
}
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ (field-level) |
| 404 | 3001 | `venueId` không tồn tại |
| 400 | 3106 | `startAt` không ở tương lai, hoặc `endAt <= startAt` |
| 400 | 3107 | Quan hệ `salesStartAt`/`salesEndAt`/`startAt` sai (mục 3) |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi**

1. `@PreAuthorize("hasRole('ADMIN')")`.
2. Validate field-level (Bean Validation) → `1001`.
3. Kiểm tra `venueId` tồn tại → `3001`.
4. Validate cross-field thời gian (`3106`, `3107`).
5. Trong transaction: insert `events` (`status=DRAFT`).
6. Trả 201.

---

### PR04 — Sửa sự kiện

`PATCH /api/events/{id}` · ADMIN

**Request body** — mọi field tùy chọn (partial update), chỉ field có mặt mới được áp dụng/validate.

| Field | Sửa được khi nào |
|---|---|
| `name`, `description`, `bannerImageUrl` | Mọi lúc, trừ khi event đã `CANCELLED` |
| `venueId`, `startAt`, `endAt`, `salesStartAt`, `salesEndAt` | **Chỉ khi** `status = DRAFT` |

**Response 200:** Event DTO đầy đủ (giống response PR03), phản ánh giá trị sau khi sửa.

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ |
| 404 | 3101 | Event không tồn tại |
| 409 | 3103 | Event đã `CANCELLED` |
| 409 | 3102 | Cố sửa field giới hạn (`venueId`/`startAt`/`endAt`/`salesStartAt`/`salesEndAt`) khi event không ở `DRAFT` |
| 404 | 3001 | Đổi `venueId` sang giá trị không tồn tại |
| 400 | 3106 / 3107 | Thời gian mới vi phạm quy tắc mục 3 |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi**

1. `@PreAuthorize("hasRole('ADMIN')")`; tải event → không có → `3101`.
2. `status = CANCELLED` → `3103`.
3. Nếu request có field giới hạn và `status != DRAFT` → `3102`.
4. Validate field-level các field có mặt; nếu đổi `venueId` → kiểm tra tồn tại (`3001`); nếu đổi thời gian → validate lại toàn bộ bộ ba `startAt`/`endAt`/`sales*` bằng giá trị **sau khi áp dụng thay đổi** (không chỉ validate field vừa gửi một mình).
5. Trong transaction: cập nhật các field hợp lệ.
6. Trả 200.

---

### PR05 — Publish sự kiện

`PATCH /api/events/{id}/publish` · ADMIN · không có request body

**Response 200:** Event DTO với `status: "PUBLISHED"`.

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 404 | 3101 | Event không tồn tại |
| 409 | 3104 | Event đã `PUBLISHED` |
| 409 | 3103 | Event đã `CANCELLED` |
| 400 | 3105 | Không đạt điều kiện publish — xem bước 4 |
| 403 | 1004 | Role không phải ADMIN |

**Response 400 mẫu (3105)**

```json
{
  "code": 3105,
  "message": "Sự kiện chưa đủ điều kiện để publish",
  "result": { "reasons": ["Chưa có hạng vé nào", "Còn 3 ghế chưa được gán hạng vé"] }
}
```

**Các bước thực thi** (đúng sequence 5.3 trong `P2_product_design.md`)

1. Tải event → không có → `3101`; `PUBLISHED` → `3104`; `CANCELLED` → `3103`.
2. Gom toàn bộ lý do không đạt (không dừng ở lý do đầu tiên, để ADMIN thấy hết trong một lần gọi):
   - Chưa có `TicketType` nào.
   - Có `TicketType` SEATED nhưng `totalQuantity = 0` (không có ghế nào gán).
   - Còn `Seat` nào trong `SeatMap` của event có `ticket_type_id IS NULL`.
   - Có `TicketType` GENERAL_ADMISSION với `totalQuantity <= 0` (về lý thuyết không xảy ra vì đã chặn ở PR12, nhưng kiểm tra lại cho chắc).
3. Danh sách lý do khác rỗng → `3105` kèm `reasons`.
4. Trong transaction: `status = PUBLISHED`.
5. Trả 200.

---

### PR06 — Hủy sự kiện

`PATCH /api/events/{id}/cancel` · ADMIN · không có request body

**Response 200:** Event DTO với `status: "CANCELLED"`.

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 404 | 3101 | Event không tồn tại |
| 409 | 3103 | Event đã `CANCELLED` |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi**

1. Tải event → không có → `3101`; đã `CANCELLED` → `3103`. Cho phép hủy từ cả `DRAFT` và `PUBLISHED`.
2. Trong transaction: `status = CANCELLED`.
3. Sau khi commit: publish `product.event-cancelled` (payload: `eventId`, `cancelledAt`) — lỗi publish chỉ log, không ảnh hưởng response (mục 4 điểm 3).
4. Trả 200.

**Lưu ý:** P2 chưa có consumer nào lắng nghe topic này (`order-service` chưa tồn tại) — việc publish chỉ để sẵn sàng cho P4, không có tác dụng quan sát được ở P2.

---

### PR07 — Danh sách sự kiện

`GET /api/events` · Công khai

**Query params**

| Param | Kiểu | Mặc định | Validation |
|---|---|---|---|
| `page` | int | 0 | `>= 0` |
| `size` | int | 20 | `1–100` |

Chỉ trả event `status = PUBLISHED` **và** `startAt >= now` (ẩn sự kiện đã diễn ra — xem điểm cần xác nhận ở mục 9), sắp xếp theo `startAt` tăng dần.

**Response 200**

```json
{
  "code": 200,
  "result": {
    "content": [
      {
        "id": "e7a...",
        "name": "Đêm nhạc Trịnh",
        "startAt": "2026-12-01T10:00:00Z",
        "endAt": "2026-12-01T13:00:00Z",
        "status": "PUBLISHED",
        "bannerImageUrl": null,
        "minPrice": 50.00,
        "venue": { "id": "b1e...", "name": "Nhà hát Hòa Bình", "city": "Hồ Chí Minh" }
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  }
}
```

`minPrice` = giá thấp nhất trong các `TicketType` của event (null nếu vì lý do nào đó event không có ticket type nào, về lý thuyết không xảy ra với event đã `PUBLISHED`).

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | `page < 0` hoặc `size` ngoài `1–100` |

---

### PR08 — Chi tiết sự kiện

`GET /api/events/{id}` · Công khai

**Response 200** (khi `PUBLISHED` hoặc `CANCELLED`)

```json
{
  "code": 200,
  "result": {
    "id": "e7a...",
    "name": "Đêm nhạc Trịnh",
    "description": "...",
    "startAt": "2026-12-01T10:00:00Z",
    "endAt": "2026-12-01T13:00:00Z",
    "salesStartAt": null,
    "salesEndAt": null,
    "status": "PUBLISHED",
    "bannerImageUrl": null,
    "venue": { "id": "b1e...", "name": "Nhà hát Hòa Bình", "address": "240 3/2", "city": "Hồ Chí Minh" },
    "ticketTypes": [
      { "id": "t1...", "category": "SEATED", "name": "VIP", "price": 150.00, "totalQuantity": 120 },
      { "id": "t2...", "category": "GENERAL_ADMISSION", "name": "Vé đứng", "price": 50.00, "totalQuantity": 500 }
    ],
    "hasSeatMap": true
  }
}
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 404 | 3101 | Event không tồn tại, **hoặc** event đang `DRAFT` (cùng một lỗi — mục 4 điểm 6) |

---

### PR09 — Tạo sơ đồ ghế

`POST /api/events/{id}/seat-map` · ADMIN

**Request body**

| Field | Kiểu | Bắt buộc | Validation |
|---|---|---|---|
| `name` | string | ✔ | mục 3 |

**Response 201**

```json
{ "code": 200, "result": { "id": "sm1...", "eventId": "e7a...", "name": "Sơ đồ chính" } }
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ |
| 404 | 3101 | Event không tồn tại |
| 409 | 3102 | Event không ở `DRAFT` |
| 409 | 3201 | Event đã có sơ đồ ghế |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi:** tải event (`3101`) → check `DRAFT` (`3102`) → check chưa có seat map (`3201`, cũng là lưới chặn cuối qua unique constraint `event_id`) → insert `seat_maps` → trả 201.

---

### PR10 — Thêm ghế hàng loạt

`POST /api/events/{id}/seat-map/seats` · ADMIN

**Request body**

```json
{ "seats": [ { "section": "VIP", "rowLabel": "A", "seatNumber": "01" }, { "section": "VIP", "rowLabel": "A", "seatNumber": "02" } ] }
```

Validation từng phần tử: mục 3. `displayLabel` **không** nhận từ client — server tự sinh `"{rowLabel}-{seatNumber}"`.

**Response 201**

```json
{
  "code": 200,
  "message": "Đã thêm 2 ghế",
  "result": {
    "createdCount": 2,
    "seats": [
      { "id": "s1...", "section": "VIP", "rowLabel": "A", "seatNumber": "01", "displayLabel": "A-01" },
      { "id": "s2...", "section": "VIP", "rowLabel": "A", "seatNumber": "02", "displayLabel": "A-02" }
    ]
  }
}
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ (gồm mảng rỗng hoặc > 2000 phần tử) |
| 404 | 3101 | Event không tồn tại |
| 409 | 3102 | Event không ở `DRAFT` |
| 404 | 3202 | Event chưa có sơ đồ ghế (phải gọi PR09 trước) |
| 409 | 3203 | Trùng `(section, rowLabel, seatNumber)` — trong payload hoặc với ghế đã có sẵn |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi**

1. Tải event (`3101`) → check `DRAFT` (`3102`) → tải seat map (`3202`).
2. Kiểm tra trùng lặp **trong chính payload** (so sánh bộ ba `section+rowLabel+seatNumber`) → `3203` nếu có, liệt kê rõ phần tử trùng trong `message`.
3. Trong **một transaction**: insert toàn bộ, mỗi dòng tự sinh `displayLabel`. Bắt `DataIntegrityViolationException` (trùng với ghế đã tồn tại từ trước, lớp chặn cuối qua unique index `(seat_map_id, section, row_label, seat_number)`) → map thành `3203`, rollback toàn bộ batch.
4. Trả 201 kèm danh sách ghế vừa tạo (để ADMIN lấy `id` dùng ngay cho PR12).

---

### PR11 — Xem sơ đồ ghế

`GET /api/events/{id}/seat-map` · Công khai

**Response 200**

```json
{
  "code": 200,
  "result": {
    "seatMapId": "sm1...",
    "seats": [
      {
        "id": "s1...", "section": "VIP", "rowLabel": "A", "seatNumber": "01", "displayLabel": "A-01",
        "ticketType": { "id": "t1...", "name": "VIP", "price": 150.00 }
      }
    ]
  }
}
```

`ticketType` là `null` nếu ADMIN mới thêm ghế (PR10) nhưng chưa gán hạng vé (PR12) — chỉ xảy ra khi event còn `DRAFT`, vì publish (PR05) bắt buộc mọi ghế đã được gán.

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 404 | 3101 | Event không tồn tại, hoặc đang `DRAFT` (mục 4 điểm 6) |
| 404 | 3202 | Event `PUBLISHED`/`CANCELLED` nhưng không có sơ đồ ghế (event chỉ bán GA) |

**Ghi chú (liên quan backlog #2, mục 0):** response hiện **không có** trường trạng thái bán/giữ (`saleStatus`) cho từng ghế — sẽ được thêm ở P3 khi `product-service` có consumer cho `order.seat-status-changed` (mục 8). Đây là thay đổi cộng thêm field (additive), không phá vỡ hợp đồng API hiện tại.

---

### PR12 — Tạo hạng vé

`POST /api/events/{id}/ticket-types` · ADMIN

**Request body**

| Field | Kiểu | Bắt buộc | Ghi chú |
|---|---|---|---|
| `category` | string | ✔ | `SEATED` \| `GENERAL_ADMISSION` |
| `name` | string | ✔ | mục 3 |
| `price` | decimal | ✔ | mục 3 |
| `totalQuantity` | int | Chỉ khi GA | Không được gửi khi SEATED |
| `seatIds` | UUID[] | Chỉ khi SEATED | Không được gửi khi GA |

```json
{ "category": "SEATED", "name": "VIP", "price": 150.00, "seatIds": ["s1...", "s2..."] }
```

**Response 201**

```json
{
  "code": 200,
  "result": { "id": "t1...", "eventId": "e7a...", "category": "SEATED", "name": "VIP", "price": 150.00, "totalQuantity": 2 }
}
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ, gồm cả gửi sai field theo `category` (ví dụ gửi `totalQuantity` khi `category=SEATED`) |
| 404 | 3101 | Event không tồn tại |
| 409 | 3102 | Event không ở `DRAFT` |
| 400 | 3303 | `category=SEATED` nhưng thiếu/rỗng `seatIds` |
| 400 | 3302 | `category=GENERAL_ADMISSION` nhưng thiếu/`<=0` `totalQuantity` |
| 404 | 3204 | Một `seatId` không tồn tại |
| 400 | 3206 | Một `seatId` thuộc `SeatMap` của event khác |
| 409 | 3205 | Một `seatId` đã được gán cho hạng vé khác |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi**

1. Tải event (`3101`) → check `DRAFT` (`3102`).
2. Validate field-level + theo `category` (`1001`, `3302`, `3303`).
3. Nếu SEATED: tải toàn bộ `Seat` theo `seatIds` — thiếu bất kỳ id nào → `3204`; id thuộc `SeatMap` không gắn với event trong path → `3206`; id có `ticket_type_id` khác null → `3205`.
4. Trong một transaction: insert `ticket_types` (`total_quantity = seatIds.size()` nếu SEATED, hoặc giá trị nhập tay nếu GA); nếu SEATED, `UPDATE seats SET ticket_type_id = :newId WHERE id IN (:seatIds)`.
5. Trả 201.

---

### PR13 — Sửa hạng vé

`PATCH /api/ticket-types/{id}` · ADMIN

**Request body** — cả hai tùy chọn, chỉ field có mặt mới áp dụng.

| Field | Kiểu | Validation |
|---|---|---|
| `name` | string | mục 3 |
| `price` | decimal | mục 3 |

`category`, `totalQuantity`, danh sách ghế **không sửa được** qua API này (muốn đổi thành phần ghế, tạo hạng vé mới — ngoài phạm vi P2).

**Response 200**

```json
{ "code": 200, "result": { "id": "t1...", "eventId": "e7a...", "category": "SEATED", "name": "VIP+", "price": 180.00, "totalQuantity": 2 } }
```

**Lỗi**

| HTTP | errorCode | Điều kiện |
|---|---|---|
| 400 | 1001 | Request không hợp lệ |
| 404 | 3301 | TicketType không tồn tại |
| 409 | 3103 | Event của hạng vé này đã `CANCELLED` |
| 403 | 1004 | Role không phải ADMIN |

**Các bước thực thi:** tải ticket type kèm event → event `CANCELLED` → `3103`; validate field-level; cập nhật field có mặt; trả 200. Cho phép sửa `name`/`price` bất kể event đang `DRAFT` hay `PUBLISHED` (xem điểm cần xác nhận ở mục 9).

---

## 7. Danh sách lỗi theo HTTP status (tra nhanh)

| HTTP | errorCode xuất hiện trong P2 |
|---|---|
| 400 | 1001, 3106, 3107, 3105, 3302, 3303, 3206 |
| 403 | 1004 |
| 404 | 3001, 3101, 3202, 3204, 3301 |
| 409 | 3102, 3103, 3104, 3201, 3203, 3205 |

---

## 8. Kafka — topic liên quan tới P2

| Topic | Producer | Consumer | Trạng thái | Payload |
|---|---|---|---|---|
| `product.event-cancelled` | product-service | order-service | **Implement ở P2** (chỉ phía publish) | `{ eventId, cancelledAt }` |
| `order.seat-status-changed` | order-service | product-service | **Chỉ đặt tên trước** — code ở P3 | Dự kiến: `{ seatId, status: HELD\|AVAILABLE\|SOLD, changedAt }`, có thể đổi khi thiết kế P3 |

Hằng số topic đầu tiên thêm vào `KafkaTopics` (module `commonlib-kafka`) khi code PR06; hằng số thứ hai thêm khi code `order-service` ở P3, không thêm trước để tránh code chết không ai dùng.

---

## 9. Điểm cần xác nhận trước khi code

1. **`GET /events` ẩn sự kiện đã diễn ra** (`startAt >= now`, mục PR07) — đây là giả định của tôi, không có trong mô tả gốc P2. Nếu muốn hiển thị cả sự kiện cũ (vì P2 chưa có trạng thái `COMPLETED`) thì bỏ điều kiện này.
2. **PR13 cho sửa `price`/`name` ngay cả khi event đã `PUBLISHED`** — P2 chưa có vé bán thật nên chưa rủi ro gì, nhưng đây là quyết định tạm, cần xem lại khi P3/P4 xong (backlog #3 ở `P2_product_design.md`).
3. **Giới hạn 2000 ghế/request ở PR10** — con số tùy ý để tránh payload khổng lồ, có thể chỉnh nếu venue thực tế lớn hơn nhiều.
4. **`minPrice` trong PR07** — thêm field tiện cho UI danh sách sự kiện, không có trong yêu cầu gốc; bỏ nếu không cần.
5. **Venue không có API sửa/xóa** — P2 chỉ có tạo + danh sách, đúng theo danh sách endpoint đã chốt ở `P2_product_design.md` mục 7. Nếu cần sửa venue, bổ sung sau.

---

*Hết tài liệu API design P2. Bước tiếp theo: xác nhận mục 9 → bắt đầu code `product-service` (entity, Flyway migration, repository, service, controller) theo đúng thứ tự đã làm ở P1.*
