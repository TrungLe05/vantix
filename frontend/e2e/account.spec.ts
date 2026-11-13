import { expect, test } from "@playwright/test";
import { buyer, login } from "./helpers";

test.describe("Tài khoản và phiên đăng nhập", () => {
  test("sai mật khẩu hiển thị lỗi, không vào được", async ({ page }) => {
    await page.goto("/login");
    await page.getByPlaceholder("you@example.com").fill("buyer1@gmail.com");
    await page.getByPlaceholder("Enter your password").fill("wrong-password-1");
    await page.getByRole("button", { name: "Sign in", exact: true }).click();
    await expect(page.getByRole("alert")).toBeVisible();
    await expect(page).toHaveURL(/\/login/);
  });

  test("form đăng ký kiểm tra dữ liệu phía client", async ({ page }) => {
    await page.goto("/register");
    await page.getByRole("button", { name: /Create account|Sign up|Register/i }).click();
    await expect(page.getByText(/valid email|Enter/i).first()).toBeVisible();
  });

  test("phiên giữ được sau khi tải lại trang (refresh token cookie)", async ({ page }) => {
    await login(page, buyer(1));
    await page.goto("/profile");
    await expect(page.getByText("buyer1@gmail.com").first()).toBeVisible();
    await page.reload();
    await expect(page.getByText("buyer1@gmail.com").first()).toBeVisible();
  });

  test("đăng xuất xóa phiên", async ({ page }) => {
    await login(page, buyer(1));
    await page.goto("/profile");
    await page.getByRole("button", { name: /Log out|Sign out/i }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto("/profile");
    await expect(page.getByText("buyer1@gmail.com")).toHaveCount(0);
  });

  test("tài khoản bắt buộc đổi mật khẩu được đưa tới trang đổi mật khẩu và đổi thành công", async ({ page }) => {
    // Cần một tài khoản có must_change_password = true (vd STAFF do ADMIN tạo). Mật khẩu tạm chỉ gửi qua
    // email nên không tự lấy được trong test — truyền qua biến môi trường, thiếu thì bỏ qua.
    const email = process.env.E2E_MCP_EMAIL;
    const password = process.env.E2E_MCP_PASSWORD;
    test.skip(!email || !password, "Đặt E2E_MCP_EMAIL / E2E_MCP_PASSWORD để chạy");
    const newPassword = `New${Date.now()}pw`;

    await login(page, { email: email!, password: password! });
    await expect(page).toHaveURL(/\/change-password/);
    await expect(page.getByRole("heading", { name: "Set a new password" })).toBeVisible();

    await page.getByLabel("Current password").fill(password!);
    await page.getByLabel("New password", { exact: true }).fill(newPassword);
    await page.getByLabel("Confirm new password").fill("mismatch1");
    await page.getByRole("button", { name: "Update password" }).click();
    await expect(page.getByText("The passwords do not match.")).toBeVisible();

    await page.getByLabel("Confirm new password").fill(newPassword);
    await page.getByRole("button", { name: "Update password" }).click();
    await expect(page).toHaveURL(/\/login\?passwordChanged=1/);
    await expect(page.getByText("Your password has been updated.")).toBeVisible();

    await login(page, { email: email!, password: newPassword });
    await expect(page).not.toHaveURL(/\/change-password/);
  });
});
