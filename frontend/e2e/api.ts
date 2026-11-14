import { expect, request, type APIRequestContext, type APIResponse } from "@playwright/test";
import { admin } from "./helpers";

/** Dựng dữ liệu nhanh qua Gateway bằng token ADMIN, để spec BUYER không phụ thuộc thứ tự chạy spec ADMIN. */
export const apiBaseUrl = () => process.env.E2E_API_URL ?? "http://localhost:8999/api";

export const adminApi = () => apiAs(admin());

/** APIRequestContext đã đăng nhập (Bearer access token) qua Gateway. */
export async function apiAs(account: { email: string; password: string }): Promise<APIRequestContext> {
  const anon = await request.newContext({ baseURL: apiBaseUrl() + "/" });
  const login = await anon.post("auth/login", { data: account });
  expect(login.status(), `đăng nhập ${account.email} qua API`).toBe(200);
  const token = (await login.json()).result.auth.accessToken as string;
  await anon.dispose();
  return request.newContext({
    baseURL: apiBaseUrl() + "/",
    extraHTTPHeaders: { Authorization: `Bearer ${token}` },
  });
}

async function result<T>(response: APIResponse): Promise<T> {
  expect(response.ok(), `${response.url()} -> ${response.status()} ${await response.text()}`).toBeTruthy();
  return (await response.json()).result as T;
}

export type SeededEvent = { id: string; name: string; gaTypeId?: string; seatIds?: string[] };

/** Event PUBLISHED, đang mở bán (không đặt sales window), bắt đầu sau 7 ngày. */
export async function seedEvent(
  api: APIRequestContext,
  options: { name: string; gaQuantity?: number; seats?: number },
): Promise<SeededEvent> {
  const venues = await result<Array<{ id: string }>>(await api.get("venues"));
  const start = new Date(Date.now() + 7 * 86_400_000);
  const event = await result<{ id: string }>(await api.post("events", {
    data: {
      venueId: venues[0].id,
      name: options.name,
      startAt: start.toISOString(),
      endAt: new Date(start.getTime() + 3 * 3_600_000).toISOString(),
    },
  }));
  const seeded: SeededEvent = { id: event.id, name: options.name };
  if (options.seats) {
    await result(await api.post(`events/${event.id}/seat-map`, { data: { name: "Main" } }));
    const created = await result<{ seats: Array<{ id: string }> }>(await api.post(`events/${event.id}/seat-map/seats`, {
      data: {
        seats: Array.from({ length: options.seats }, (_, i) => ({ section: "Floor", rowLabel: "A", seatNumber: String(i + 1) })),
      },
    }));
    seeded.seatIds = created.seats.map((s) => s.id);
    await result(await api.post(`events/${event.id}/ticket-types`, {
      data: { category: "SEATED", name: "VIP", price: 120, seatIds: seeded.seatIds },
    }));
  }
  if (options.gaQuantity) {
    const ga = await result<{ id: string }>(await api.post(`events/${event.id}/ticket-types`, {
      data: { category: "GENERAL_ADMISSION", name: "Standing", price: 25, totalQuantity: options.gaQuantity },
    }));
    seeded.gaTypeId = ga.id;
  }
  await result(await api.patch(`events/${event.id}/publish`));
  return seeded;
}
