import { expect, test } from "@playwright/test";
import { admin, localDateTime, login, uniqueName } from "./helpers";

test.describe("ADMIN — quản lý sự kiện", () => {
  test("đăng nhập ADMIN vào trang quản trị sự kiện", async ({ page }) => {
    await login(page, admin());
    await expect(page).toHaveURL(/\/admin\/events/);
  });

  test("tạo sự kiện GA qua wizard và publish, sự kiện xuất hiện ở trang công khai", async ({ page }) => {
    const name = uniqueName("E2E GA concert");
    await login(page, admin());
    await page.goto("/admin/events/new");

    await page.getByLabel("Event name").fill(name);
    await page.getByLabel("Venue").selectOption({ index: 1 });
    await page.getByLabel("Starts").fill(localDateTime(60 * 24 * 7));
    await page.getByLabel("Ends").fill(localDateTime(60 * 24 * 7 + 180));
    await page.getByRole("button", { name: /Save draft and continue/ }).click();

    await page.getByText("General admission", { exact: true }).click();
    await page.getByRole("button", { name: /^Continue/ }).click();

    await page.getByLabel("Ticket type name").fill("Standing");
    await page.getByLabel("Price (USD)").fill("25");
    await page.getByLabel("Quantity").fill("50");
    await page.getByRole("button", { name: /Create ticket type/ }).click();
    await expect(page.getByText(/1 ticket types created/)).toBeVisible();

    await page.getByRole("button", { name: /Publish/ }).click();
    await expect(page.getByText("Event published successfully.")).toBeVisible();

    await page.goto("/");
    await expect(page.getByText(name)).toBeVisible();
  });

  test("tạo sự kiện ghế ngồi: sơ đồ ghế, gán ghế cho hạng vé, publish", async ({ page }) => {
    const name = uniqueName("E2E seated show");
    await login(page, admin());
    await page.goto("/admin/events/new");

    await page.getByLabel("Event name").fill(name);
    await page.getByLabel("Venue").selectOption({ index: 1 });
    await page.getByLabel("Starts").fill(localDateTime(60 * 24 * 10));
    await page.getByLabel("Ends").fill(localDateTime(60 * 24 * 10 + 120));
    await page.getByRole("button", { name: /Save draft and continue/ }).click();

    await page.getByText("Reserved seating", { exact: true }).click();
    await page.getByRole("textbox", { name: "Section" }).fill("Floor");
    await page.getByLabel("First row (A–Z)").fill("A");
    await page.getByLabel("Number of rows").fill("2");
    await page.getByLabel("Seats per row").fill("3");
    await page.getByRole("button", { name: /^Continue/ }).click();

    await expect(page.getByText("Selected 0 / 6 available seats.")).toBeVisible();
    // Publish khi còn ghế chưa gán hạng vé → backend trả danh sách lý do
    for (const label of ["A-1", "A-2", "A-3", "B-1", "B-2", "B-3"]) {
      await page.getByLabel(label, { exact: true }).check();
    }
    await page.getByLabel("Ticket type name").fill("VIP");
    await page.getByLabel("Price (USD)").fill("120");
    await page.getByRole("button", { name: /Create ticket type/ }).click();
    await expect(page.getByText("All seats have been assigned.")).toBeVisible();

    await page.getByRole("button", { name: /Publish/ }).click();
    await expect(page.getByText("Event published successfully.")).toBeVisible();
  });

  test("publish thiếu điều kiện hiển thị lý do từ backend", async ({ page }) => {
    await login(page, admin());
    await page.goto("/admin/events/new");
    await page.getByLabel("Event name").fill(uniqueName("E2E incomplete"));
    await page.getByLabel("Venue").selectOption({ index: 1 });
    await page.getByLabel("Starts").fill(localDateTime(60 * 24 * 5));
    await page.getByLabel("Ends").fill(localDateTime(60 * 24 * 5 + 60));
    await page.getByRole("button", { name: /Save draft and continue/ }).click();
    await page.getByText("Reserved seating", { exact: true }).click();
    await page.getByLabel("Number of rows").fill("1");
    await page.getByLabel("Seats per row").fill("2");
    await page.getByRole("button", { name: /^Continue/ }).click();
    await page.getByLabel("A-1", { exact: true }).check();
    await page.getByLabel("Ticket type name").fill("Half");
    await page.getByLabel("Price (USD)").fill("10");
    await page.getByRole("button", { name: /Create ticket type/ }).click();

    await page.getByRole("button", { name: /Publish/ }).click();
    await expect(page.getByText(/chưa đủ điều kiện|ghế chưa được gán/i).first()).toBeVisible();
  });

  test("trang địa điểm và danh sách sự kiện ADMIN tải được", async ({ page }) => {
    await login(page, admin());
    await page.goto("/admin/venues");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    await page.goto("/admin/events");
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  });

  test("BUYER không vào được trang quản trị", async ({ page }) => {
    const { buyer } = await import("./helpers");
    await login(page, buyer(4));
    await page.goto("/admin/events");
    await expect(page).not.toHaveURL(/\/admin\/events$/);
  });
});
