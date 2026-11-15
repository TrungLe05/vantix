// src/lib/auth-redirect.ts
// Đưa người chưa đăng nhập về /login rồi quay lại đúng trang sau khi đăng nhập.

export function loginRedirectPath(next: string) {
  return `/login?reason=checkout&next=${encodeURIComponent(next)}`;
}

// Chỉ nhận đường dẫn nội bộ: chặn open redirect kiểu "//evil.com" hoặc "https://…"
export function safeNextPath(raw: string | null): string | null {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return null;
  return raw;
}
