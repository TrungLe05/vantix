// src/lib/device-id.ts
// Device ID ngẫu nhiên cho bot-detection (D37): tạo một lần, lưu localStorage. Không phải fingerprint —
// không đọc đặc điểm trình duyệt. Backend chỉ giữ HMAC của giá trị này.
const STORAGE_KEY = "vantix.deviceId";
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

// Dự phòng khi localStorage bị chặn (chế độ riêng tư): giữ trong bộ nhớ cho tới khi tải lại trang
let memoryId: string | null = null;

export function getDeviceId(): string | null {
  if (typeof window === "undefined" || typeof crypto?.randomUUID !== "function") {
    return null;
  }
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (stored && UUID_PATTERN.test(stored)) return stored;
    const created = crypto.randomUUID();
    window.localStorage.setItem(STORAGE_KEY, created);
    return created;
  } catch {
    memoryId ??= crypto.randomUUID();
    return memoryId;
  }
}
