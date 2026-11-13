import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const apiBaseUrl = __ENV.API_BASE_URL || "http://localhost:8999/api";
const eventId = __ENV.EVENT_ID;
const ticketTypeId = __ENV.TICKET_TYPE_ID;
const seatId = __ENV.SEAT_ID;
const mode = __ENV.MODE || "seat";
const runId = __ENV.RUN_ID || Date.now().toString();
const successCount = new Counter("hold_success");
const conflictCount = new Counter("hold_conflict");
const orderResponseCallback = http.expectedStatuses(200, 201, 409);
const loginResponseCallback = http.expectedStatuses(200);

let buyerAccounts;
try {
  buyerAccounts = JSON.parse(__ENV.BUYER_ACCOUNTS_JSON || "[]");
} catch {
  throw new Error("BUYER_ACCOUNTS_JSON must be a valid JSON array.");
}

if (!Array.isArray(buyerAccounts) || buyerAccounts.length < 2) {
  throw new Error("Set BUYER_ACCOUNTS_JSON to at least two buyer email/password accounts.");
}
if (buyerAccounts.some((account) => !account?.email || !account?.password)) {
  throw new Error("Each BUYER_ACCOUNTS_JSON entry must contain email and password.");
}
if (!eventId || !ticketTypeId || (mode === "seat" && !seatId)) {
  throw new Error("Set EVENT_ID, TICKET_TYPE_ID, and SEAT_ID when MODE=seat.");
}
if (mode !== "seat" && mode !== "ga") {
  throw new Error("MODE must be seat or ga.");
}

// seat: đúng 1 buyer giữ được ghế. ga: số buyer giữ được = min(VUs, tồn GA còn lại) — truyền GA_AVAILABLE
// (availableQuantity từ GET /orders/events/{id}/availability ngay trước khi chạy).
const vus = buyerAccounts.length;
const expectedSuccess = mode === "seat" ? 1 : Math.min(vus, Number(__ENV.GA_AVAILABLE ?? NaN));
if (!Number.isInteger(expectedSuccess)) {
  throw new Error("Set GA_AVAILABLE (integer) when MODE=ga.");
}

export const options = {
  scenarios: {
    [`${mode}_contention`]: {
      executor: "per-vu-iterations",
      vus,
      iterations: 1,
      maxDuration: "1m",
    },
  },
  thresholds: {
    hold_success: [`count==${expectedSuccess}`],
    hold_conflict: [`count==${vus - expectedSuccess}`],
    checks: ["rate==1"],
  },
};

export function setup() {
  const buyerTokens = buyerAccounts.map((account, index) => {
    const response = http.post(
      `${apiBaseUrl}/auth/login`,
      JSON.stringify({ email: account.email, password: account.password }),
      {
        headers: { "Content-Type": "application/json" },
        responseCallback: loginResponseCallback,
        tags: { name: "buyer_login" },
      },
    );

    if (response.status !== 200) {
      throw new Error(`Login failed for buyer account #${index + 1} (HTTP ${response.status}).`);
    }

    let result;
    try {
      result = response.json().result;
    } catch {
      throw new Error(`Login response for buyer account #${index + 1} was not valid JSON.`);
    }

    if (result?.user?.role !== "BUYER") {
      throw new Error(`Account #${index + 1} must have BUYER role.`);
    }
    if (!result?.auth?.accessToken) {
      throw new Error(`Login response for buyer account #${index + 1} did not contain an access token.`);
    }

    return result.auth.accessToken;
  });

  return { buyerTokens };
}

export default function (setupData) {
  const token = setupData.buyerTokens[__VU - 1];
  const item = mode === "seat" ? { ticketTypeId, seatId, quantity: 1 } : { ticketTypeId, quantity: 1 };
  const response = http.post(
    `${apiBaseUrl}/orders`,
    JSON.stringify({ eventId, items: [item] }),
    {
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
        "Idempotency-Key": `k6-${mode}-${runId}-${__VU}-${__ITER}`,
      },
      responseCallback: orderResponseCallback,
    },
  );

  if (response.status === 201 || response.status === 200) successCount.add(1);
  if (response.status === 409) conflictCount.add(1);
  if (![200, 201, 409].includes(response.status)) {
    let errorCode = "unknown";
    try {
      errorCode = response.json().code ?? "unknown";
    } catch {
      // Chỉ ghi status và mã lỗi; không in payload có thể chứa dữ liệu nhạy cảm.
    }
    console.error(`VU=${__VU} received HTTP ${response.status}, apiCode=${errorCode}`);
  }

  check(response, {
    "request either holds inventory or receives a conflict": (result) => result.status === 201 || result.status === 200 || result.status === 409,
  });
}
