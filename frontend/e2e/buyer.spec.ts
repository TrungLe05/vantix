import { expect, test, type Page } from "@playwright/test";
import { adminApi, apiAs, seedEvent, type SeededEvent } from "./api";
import { buyer, login, uniqueName } from "./helpers";

let gaEvent: SeededEvent;
let seatEvent: SeededEvent;

test.beforeAll(async () => {
  const api = await adminApi();
  gaEvent = await seedEvent(api, { name: uniqueName("E2E GA buyer"), gaQuantity: 30 });
  seatEvent = await seedEvent(api, { name: uniqueName("E2E seats buyer"), seats: 4 });
  await api.dispose();
});

async function holdGa(page: Page, eventId: string, eventName: string, quantity: number) {
  await page.goto(`/events/${eventId}`);
  await expect(page.getByRole("heading", { name: eventName })).toBeVisible();
  for (let i = 0; i < quantity; i++) {
    await page.getByRole("button", { name: "Increase quantity for Standing" }).click();
  }
  await page.getByRole("button", { name: `Continue · ${quantity} tickets` }).click();
}

test.describe("BUYER — giữ vé, giỏ hàng, xác nhận", () => {
  test("trang chủ hiển thị sự kiện đang mở bán, khách chưa đăng nhập xem được chi tiết", async ({ page }) => {
    await page.goto("/");
    await expect(page.getByText(gaEvent.name)).toBeVisible();
    await page.getByText(gaEvent.name).click();
    await expect(page.getByRole("heading", { name: gaEvent.name })).toBeVisible();
  });

  test("giữ vé GA, sửa số lượng trong giỏ, xác nhận giỏ", async ({ page }) => {
    await login(page, buyer(0));
    await holdGa(page, gaEvent.id, gaEvent.name, 2);

    await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
    await expect(page.getByText("$50.00").first()).toBeVisible();

    await page.getByRole("button", { name: "Increase Standing" }).click();
    await expect(page.getByText("$75.00").first()).toBeVisible();
    await page.getByRole("button", { name: "Decrease Standing" }).click();
    await expect(page.getByText("$50.00").first()).toBeVisible();

    await page.getByRole("button", { name: /Confirm cart/ }).click();
    await expect(page.getByText("Your cart is confirmed")).toBeVisible();

    await page.goto("/orders");
    await expect(page.getByText(gaEvent.name)).toBeVisible();
  });

  test("giữ ghế trên sơ đồ, buyer khác thấy ghế đã bị giữ", async ({ page, browser }) => {
    await login(page, buyer(1));
    await page.goto(`/events/${seatEvent.id}/seat-map`);
    await page.getByRole("button", { name: "Seat Floor A-1", exact: true }).click();
    await page.getByRole("button", { name: /Continue|Hold|Reserve/ }).click();
    await expect(page).toHaveURL(/\/orders\/[0-9a-f-]{36}$/);
    await expect(page.getByText("$120.00").first()).toBeVisible();

    const other = await browser.newContext();
    const otherPage = await other.newPage();
    await login(otherPage, buyer(2));
    await otherPage.goto(`/events/${seatEvent.id}/seat-map`);
    await expect(otherPage.getByRole("button", { name: "Seat Floor A-1, unavailable" })).toBeVisible();
    await other.close();
  });

  test("hai buyer cùng giữ một ghế: chỉ một người thành công, người kia nhận thông báo", async ({ browser }) => {
    const contexts = await Promise.all([browser.newContext(), browser.newContext()]);
    const pages = await Promise.all(contexts.map((c) => c.newPage()));
    await login(pages[0], buyer(3));
    await login(pages[1], buyer(4));
    for (const p of pages) {
      await p.goto(`/events/${seatEvent.id}/seat-map`);
      await p.getByRole("button", { name: "Seat Floor A-2", exact: true }).click();
    }
    await Promise.all(pages.map((p) => p.getByRole("button", { name: /Continue|Hold|Reserve/ }).click()));

    await expect.poll(() => pages.filter((p) => /\/orders\//.test(p.url())).length).toBe(1);
    const loser = pages.find((p) => !/\/orders\//.test(p.url()))!;
    await expect(loser.getByText(/not held|no longer available|không còn khả dụng/i).first()).toBeVisible();
    await Promise.all(contexts.map((c) => c.close()));
  });

  test("giới hạn 10 vé mỗi đơn hiển thị lỗi, không giữ vé", async ({ page }) => {
    await login(page, buyer(2));
    await holdGa(page, gaEvent.id, gaEvent.name, 11);
    await expect(page.getByText(/tối đa|maximum|limit/i).first()).toBeVisible();
    await expect(page).not.toHaveURL(/\/orders\//);
  });

  test("bỏ vé cuối cùng thì giỏ bị hủy", async ({ page }) => {
    const api = await adminApi();
    const event = await seedEvent(api, { name: uniqueName("E2E remove"), gaQuantity: 5 });
    await api.dispose();
    await login(page, buyer(3));
    await holdGa(page, event.id, event.name, 1);
    await expect(page).toHaveURL(/\/orders\//);
    await page.getByRole("button", { name: "Remove Standing" }).click();
    await expect(page.getByText(/cancel/i).first()).toBeVisible();
  });
});

test.describe("ADMIN hủy sự kiện → giỏ của BUYER bị hủy (Kafka product.event-cancelled)", () => {
  test("giỏ đang giữ chuyển sang hủy sau khi ADMIN hủy sự kiện", async ({ page }) => {
    const api = await adminApi();
    const event = await seedEvent(api, { name: uniqueName("E2E cancel"), gaQuantity: 5 });
    await login(page, buyer(0));
    await holdGa(page, event.id, event.name, 1);
    await expect(page).toHaveURL(/\/orders\//);
    const orderUrl = page.url();

    const cancel = await api.patch(`events/${event.id}/cancel`);
    expect(cancel.ok()).toBeTruthy();
    await api.dispose();

    // Chứng minh đường Kafka: GET /orders/{id} không gọi Product/availability (lưới an toàn), nên Order chỉ
    // thành CANCELLED khi consumer order-service nhận product.event-cancelled từ outbox của product
    const orderId = orderUrl.split("/").pop()!;
    const buyerApi = await apiAs(buyer(0));
    await expect.poll(async () => {
      const response = await buyerApi.get(`orders/${orderId}`);
      return (await response.json()).result?.status;
    }, { timeout: 30_000, intervals: [1_000] }).toBe("CANCELLED");
    await buyerApi.dispose();

    await page.goto(orderUrl);
    await expect(page.getByText("Event cancelled").first()).toBeVisible({ timeout: 15_000 });
    // UI cũng hiện "Event cancelled" khi chỉ event bị hủy; kiểm thêm Order đã CANCELLED ở backend:
    // danh sách mặc định chỉ có Order đang hoạt động nên Order này phải biến mất
    await expect.poll(async () => {
      await page.goto("/orders");
      await page.getByRole("heading", { level: 1 }).waitFor();
      await page.waitForLoadState("networkidle");
      return page.getByText(event.name).count();
    }, { timeout: 30_000, intervals: [2_000] }).toBe(0);
  });
});
