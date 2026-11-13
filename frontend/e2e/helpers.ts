import { expect, type Page } from "@playwright/test";

/** Đọc biến môi trường bắt buộc; thiếu thì fail rõ ràng thay vì đăng nhập với chuỗi rỗng. */
export function env(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Thiếu biến môi trường ${name} (xem e2e/README.md)`);
  return value;
}

export const admin = () => ({ email: env("E2E_ADMIN_EMAIL"), password: env("E2E_ADMIN_PASSWORD") });
/** Seed user-service tạo buyer0..4@gmail.com dùng chung DEFAULT_BUYER_PASSWORD. */
export const buyer = (index: number) => ({ email: `buyer${index}@gmail.com`, password: env("E2E_BUYER_PASSWORD") });

export async function login(page: Page, account: { email: string; password: string }) {
  await page.goto("/login");
  await page.getByPlaceholder("you@example.com").fill(account.email);
  await page.getByPlaceholder("Enter your password").fill(account.password);
  await page.getByRole("button", { name: "Sign in", exact: true }).click();
  await expect(page).not.toHaveURL(/\/login/);
}

/** Giá trị cho input datetime-local, lệch so với hiện tại theo phút (giờ địa phương của browser). */
export function localDateTime(offsetMinutes: number): string {
  const d = new Date(Date.now() + offsetMinutes * 60_000);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const uniqueName = (prefix: string) => `${prefix} ${Date.now().toString(36)}`;

/** Giá trị sinh lúc chạy (đủ chữ + số theo rule backend) — không để chuỗi giống mật khẩu cứng trong repo. */
export const randomSecret = () => `${crypto.randomUUID().replaceAll("-", "")}A1`;
