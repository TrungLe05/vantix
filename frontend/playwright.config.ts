import { defineConfig } from "@playwright/test";

/**
 * E2E chạy trên stack đã khởi động sẵn (gateway + user + product + order + frontend), không tự dựng server.
 * Biến môi trường:
 *  - E2E_BASE_URL: URL frontend (mặc định http://localhost:3000)
 *  - E2E_ADMIN_EMAIL / E2E_ADMIN_PASSWORD: tài khoản seed ADMIN (DEFAULT_ADMIN_* của user-service)
 *  - E2E_BUYER_PASSWORD: mật khẩu chung buyer0..4@gmail.com (DEFAULT_BUYER_PASSWORD)
 *  - E2E_BROWSER_CHANNEL: "chrome" | "msedge" để dùng browser đã cài sẵn thay vì tải Chromium của Playwright
 * Không commit giá trị thật; xem e2e/README.md.
 */
export default defineConfig({
  testDir: "./e2e",
  // Các spec dùng chung dữ liệu seed (buyer, event) nên chạy tuần tự cho dễ đọc kết quả
  workers: 1,
  fullyParallel: false,
  timeout: 60_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3000",
    channel: process.env.E2E_BROWSER_CHANNEL || undefined,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    locale: "en-US",
  },
});
