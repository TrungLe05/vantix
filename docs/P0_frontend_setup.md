# Vantix Frontend — Phase 0: Setup & Convention

> **Trạng thái:** Thiết kế — dùng để code theo. Không có API design/ERD (đó là việc của backend) — tài liệu này chỉ mô tả cách dựng project, cấu trúc thư mục, và convention dùng chung cho mọi phase UI sau này.
> **Liên quan:** `P1_auth_api_design.md`, `P2_product_api_design.md` (nguồn sự thật cho field/response), 4 mockup UXMagic đã duyệt (login, seat-map-selection, event-details, event-discovery).

---

## 1. Tech stack đã chọn

| Thành phần | Lựa chọn | Lý do |
|---|---|---|
| Framework | **Next.js (App Router)** | Đã quen (Mức 1) |
| Ngôn ngữ | TypeScript | Khớp kiểu dữ liệu với DTO backend, bắt lỗi sớm hơn JS thuần |
| UI style | Tailwind CSS + **shadcn/ui** | Component sẵn (Input, Button, Card, Dialog...), dễ style theo đúng tông tối giản kiểu Luma đã chốt trong mockup — không phải tự vẽ từ đầu |
| Server state | **TanStack Query (React Query)** | Cache, tự retry, tự invalidate sau mutation — hợp vì gần như mọi màn hình đều gọi API |
| Client state (auth) | **Zustand** | Chỉ cần lưu access token + thông tin user hiện tại trong bộ nhớ, không cần Redux cho ngần ấy state |
| Form & validate | **React Hook Form + Zod** | Validate phía client theo đúng rule đã có sẵn ở `P1_auth_api_design.md`/`P2_product_api_design.md`, giảm số lần phải chờ server trả lỗi |
| HTTP client | **Axios** | Cần interceptor cho luồng refresh token (mục 4) — `fetch` thuần làm được nhưng phải tự viết nhiều hơn |
| Ngày giờ | **date-fns** | Chuyển đổi `Instant` (UTC) ở backend sang giờ địa phương khi hiển thị, và ngược lại khi gửi request |

**Giả định cần bạn xác nhận:** chọn `npm` làm package manager (không phải `pnpm`/`yarn`) vì đơn giản, không có lý do kỹ thuật bắt buộc nào ở quy mô project này.

---

## 2. Lệnh khởi tạo project

```bash
npx create-next-app@latest vantix-frontend --typescript --tailwind --eslint --app --src-dir --import-alias "@/*"
cd vantix-frontend

npx shadcn@latest init
npx shadcn@latest add button input label card dialog form select badge separator skeleton toast dropdown-menu avatar

npm install @tanstack/react-query axios zod react-hook-form @hookform/resolvers zustand date-fns
```

Trả lời các câu hỏi `shadcn init` theo gợi ý: style **Default**, base color gần với accent indigo trong mockup, CSS variables **Yes**.

`.env.local`:

```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8999/api
```

Trỏ thẳng `api-gateway`, không trỏ service lẻ — đúng nguyên tắc Trust-The-Gateway đã dùng xuyên suốt backend.

---

## 3. Cấu trúc thư mục

```
src/
├── app/
│   ├── (auth)/
│   │   ├── login/page.tsx
│   │   ├── register/page.tsx
│   │   ├── forgot-password/page.tsx
│   │   ├── auth-callback/page.tsx        # xử lý ?code= từ Google OAuth (A07 exchange)
│   │   └── layout.tsx                    # layout riêng cho nhóm trang auth (căn giữa, không có header)
│   ├── (main)/
│   │   ├── layout.tsx                    # header + footer cho toàn bộ trang chính
│   │   ├── page.tsx                      # trang discovery/home
│   │   └── events/
│   │       └── [eventId]/
│   │           ├── page.tsx              # chi tiết sự kiện
│   │           └── seat-map/page.tsx     # chọn ghế
│   ├── (admin)/
│   │   └── admin/
│   │       ├── venues/page.tsx
│   │       └── events/
│   │           ├── page.tsx
│   │           ├── new/page.tsx
│   │           └── [eventId]/edit/page.tsx
│   ├── layout.tsx                        # root layout (Providers: React Query, Zustand hydrate)
│   └── globals.css
├── components/
│   ├── ui/                               # do shadcn sinh ra, KHÔNG tự sửa tay trực tiếp trừ khi cần thiết
│   ├── auth/
│   ├── events/
│   ├── admin/
│   └── layout/                           # Header, Footer, Nav
├── lib/
│   ├── api/
│   │   ├── client.ts                     # instance axios + interceptor (mục 4)
│   │   ├── auth.ts                       # các hàm gọi API /auth/*
│   │   ├── events.ts
│   │   └── venues.ts
│   ├── validators/                       # schema Zod, 1 file/domain (auth.ts, event.ts...)
│   └── utils.ts
├── hooks/
│   ├── use-auth.ts
│   └── use-current-user.ts
├── store/
│   └── auth-store.ts                     # Zustand: accessToken, user, actions
└── types/
    ├── auth.ts                           # khớp AuthResponse/UserResponse/LoginResponse
    ├── event.ts
    └── common.ts                         # ApiResponse<T>, PaginatedResponse<T>
```

## 4. Quy ước đặt tên

| Loại | Quy ước | Ví dụ |
|---|---|---|
| Component | PascalCase, file trùng tên component | `EventCard.tsx` |
| Hook | camelCase, tiền tố `use` | `useAuth.ts` |
| Route segment (thư mục trong `app/`) | kebab-case | `forgot-password/`, `seat-map/` |
| Zod schema | hậu tố `Schema` | `loginSchema`, `createEventSchema` |
| Type khớp DTO backend | **giữ nguyên tên** response backend | `UserResponse`, `EventResponse`, `TicketTypeResponse` — để khi đọc API design không phải tự dịch tên |

---

## 5. API client — interceptor và luồng refresh token

Đây là phần dễ sai nhất vì phải khớp đúng 2 ràng buộc đã có ở backend: access token sống 15 phút, và **phải serialize việc gọi refresh** (gọi refresh trùng lặp sẽ bị tính là reuse, đá người dùng ra khỏi mọi thiết bị — đã cảnh báo ở `P1_auth_api_design.md` A08).

```typescript
// lib/api/client.ts
import axios from "axios";
import { useAuthStore } from "@/store/auth-store";

export const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
  withCredentials: true, // bắt buộc — để trình duyệt gửi/nhận cookie refreshToken
});

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  // Nhiều request 401 cùng lúc chỉ tạo ĐÚNG MỘT request refresh thật —
  // các request sau "ăn theo" cùng 1 Promise thay vì tự gọi refresh riêng
  if (!refreshPromise) {
    refreshPromise = apiClient
      .post("/auth/refresh")
      .then((res) => {
        const token = res.data.result.auth.accessToken;
        useAuthStore.getState().setAccessToken(token);
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

apiClient.interceptors.response.use(
  (res) => res,
  async (error) => {
    const { config, response } = error;
    const errorCode = response?.data?.code;

    // CHỈ tự refresh khi đúng 9003 (hết hạn) — KHÔNG refresh khi 9002 (token sai),
    // tránh vòng lặp vô ích (đúng lưu ý đã ghi ở P1_auth_api_design.md mục 4)
    if (errorCode === 9003 && !config._retried) {
      config._retried = true;
      try {
        const newToken = await refreshAccessToken();
        config.headers.Authorization = `Bearer ${newToken}`;
        return apiClient.request(config);
      } catch {
        useAuthStore.getState().clear();
        window.location.href = "/login";
      }
    }

    if (errorCode === 9001 || errorCode === 9002) {
      useAuthStore.getState().clear();
      window.location.href = "/login";
    }

    return Promise.reject(error);
  }
);
```

**Silent refresh lúc tải trang:** access token chỉ sống trong bộ nhớ JS (Zustand), **không lưu `localStorage`** (tránh rủi ro XSS đọc được token) — hệ quả là F5 lại trang sẽ mất access token. Khắc phục bằng cách gọi `POST /auth/refresh` ngay khi app khởi động (cookie `refreshToken` trình duyệt tự gửi kèm) để lấy access token mới — đặt trong `root layout` hoặc một `AuthProvider` bọc toàn app.

---

## 6. State management

- **Zustand (`auth-store.ts`)**: chỉ chứa `accessToken`, `user` (từ `UserResponse`), và 2 action `setAccessToken`/`clear`. Không cần `persist` middleware vì cố tình không lưu token qua reload (mục 5).
- **React Query**: mọi API đọc (danh sách event, chi tiết event, seat map...) qua `useQuery`; mọi API ghi (login, tạo event, chọn ghế...) qua `useMutation`, `onSuccess` gọi `queryClient.invalidateQueries` cho đúng key liên quan (ví dụ tạo ticket type xong thì invalidate query chi tiết event).

---

## 7. Form & validation

Zod schema **phản ánh đúng** bảng validation của backend, không tự nới lỏng hay siết chặt hơn — ví dụ:

```typescript
// lib/validators/auth.ts
import { z } from "zod";

export const loginSchema = z.object({
  email: z.string().email(),
  password: z.string().min(1),
});

export const registerSchema = z.object({
  email: z.string().email().max(255),
  password: z
    .string()
    .min(8).max(64)
    .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, "Mật khẩu phải có ít nhất 1 chữ cái và 1 chữ số"),
  fullName: z.string().trim().min(1).max(100),
});
```

Validate client-side **không thay thế** validate server-side — chỉ để UX nhanh hơn (báo lỗi ngay khi gõ, không cần round-trip). Lỗi `1001` từ server (nếu lọt qua) hiển thị thẳng `message` trả về, không cần tự dịch.

---

## 8. Xử lý lỗi — đọc thẳng `message`, không tự viết lại

Mọi lỗi từ backend đã có sẵn `message` bằng tiếng Việt (theo `ApiResponse`), nên component chỉ cần hiển thị `error.response.data.message` trong toast/alert — **không** cần bảng tra `errorCode → message` phía frontend, tránh hai nguồn message lệch nhau khi backend đổi câu chữ. Chỉ 3 mã cần xử lý **logic** riêng (không chỉ hiển thị): `9003` (tự refresh, mục 5), `9001`/`9002` (đăng xuất, mục 5), `9004` (redirect sang trang đổi mật khẩu bắt buộc — chi tiết ở `P1_auth_ui_plan.md`).

---

## 9. Ngày giờ

Backend trả mọi thời điểm dạng `Instant` (chuỗi ISO-8601 UTC, luôn có hậu tố `Z`). Quy ước:

- **Hiển thị**: `format(new Date(isoString), "...", { locale: vi })` của `date-fns` — tự quy đổi sang giờ trình duyệt của người dùng.
- **Gửi lên**: `new Date(localValue).toISOString()` — không tự ghép chuỗi giờ UTC thủ công.

---

## 10. Phân quyền hiển thị (role-based UI)

Đọc `role` từ `UserResponse` (lưu trong Zustand sau login) để **ẩn/hiện** mục menu ADMIN (ví dụ link "Quản lý sự kiện"). **Đây chỉ là UX, không phải bảo mật thật** — mọi quyết định phân quyền thật sự đã nằm ở `@PreAuthorize` phía backend (`1004 ACCESS_DENIED` nếu cố tình gọi API không đủ quyền). Không được dựa vào việc ẩn nút trên UI để coi là đã bảo vệ route.


---

## 11. Checklist hoàn thành Phase 0

- [ ] `npx create-next-app` chạy xong, `npm run dev` lên trang mặc định.
- [ ] `shadcn/ui` init xong, thử add 1 component (`Button`) render được.
- [ ] `apiClient` gọi thử `GET /api/events` (route công khai, không cần token) trả về đúng dữ liệu đã tạo ở backend P2.
- [ ] Màu accent + font khớp mockup đã duyệt (chỉnh `tailwind.config`/CSS variables của shadcn theo đúng mã màu indigo trong 4 mockup).

---

*Hết tài liệu setup Phase 0. Bước tiếp theo: `P1_auth_ui_plan.md`.*