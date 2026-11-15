// k6 — sinh traffic thêm giỏ cho P6 (người dùng mô phỏng + 3 kiểu bot) qua Gateway thật.
//
//   k6 run -e ROUND=a -e GATEWAY=http://localhost:8999 datagen/traffic.js
//
// Đọc datagen/.local/accounts_<round>.json và events_<round>.json (do seed_accounts.py, setup_events.py
// tạo; gitignored vì có mật khẩu). Không dùng --summary-export: summary có thể kèm dữ liệu setup.
// Nhãn của mỗi tài khoản nằm ở tiền tố email; model không thấy nhãn (xem training/build_dataset.py).
import http from 'k6/http';
import { sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const ROUND = (__ENV.ROUND || 'a').toLowerCase();
const GATEWAY = (__ENV.GATEWAY || 'http://localhost:8999') + '/api';
// Rút ngắn thời gian "suy nghĩ" của người dùng khi chạy thử nhanh (1 = như thật)
const THINK_SCALE = Number(__ENV.THINK_SCALE || '1');

const secrets = JSON.parse(open(`./.local/accounts_${ROUND}.json`));
const EVENTS = JSON.parse(open(`./.local/events_${ROUND}.json`));
// Mã riêng của mỗi lần dựng sự kiện: thiết bị "dùng chung" của hai lần chạy cùng vòng không được trùng nhau,
// nếu không Redis của bot-detection đếm cả tài khoản của lần trước (cửa sổ 24 giờ) — lỗi đã làm sai số liệu
// live vòng C (báo cáo P6 mục 4)
const RUN_TAG = EVENTS[0].eventId.replace(/-/g, '').slice(0, 4);
const byKind = (kind) => secrets.accounts.filter((a) => a.email.includes(`-${kind}-`));
const ACCOUNTS = {
  human: byKind('human'),
  botfast: byKind('botfast'),
  botviewer: byKind('botviewer'),
  botfarm: byKind('botfarm'),
};

const adds = new Counter('cart_add_ok');
const blocked = new Counter('cart_add_blocked_4019');
const rejected = new Counter('cart_add_rejected');

export const options = {
  scenarios: {
    human: { executor: 'shared-iterations', exec: 'human', vus: 70, iterations: ACCOUNTS.human.length, maxDuration: '25m' },
    botfast: { executor: 'shared-iterations', exec: 'botFast', vus: ACCOUNTS.botfast.length, iterations: ACCOUNTS.botfast.length, maxDuration: '25m' },
    botviewer: { executor: 'shared-iterations', exec: 'botViewer', vus: ACCOUNTS.botviewer.length, iterations: ACCOUNTS.botviewer.length, maxDuration: '25m' },
    botfarm: { executor: 'shared-iterations', exec: 'botFarm', vus: ACCOUNTS.botfarm.length, iterations: ACCOUNTS.botfarm.length, maxDuration: '25m' },
  },
  // Chỉ in số liệu tổng; không threshold vì đây là công cụ sinh dữ liệu, không phải test
  summaryTrendStats: ['avg', 'p(50)', 'p(95)'],
};

// ---------- tiện ích ----------

const rand = (min, max) => min + Math.random() * (max - min);
const randInt = (min, max) => Math.floor(rand(min, max + 1));
const pick = (list) => list[Math.floor(Math.random() * list.length)];
const think = (min, max) => sleep(rand(min, max) * THINK_SCALE);
// Thời gian suy nghĩ lệch phải (đa số ngắn, vài người rất lâu), giống người xem trang thật
const humanThink = (min, max) => sleep(Math.min(max, min + (-Math.log(1 - Math.random()) * (max - min) / 3)) * THINK_SCALE);

function account(kind) {
  return ACCOUNTS[kind][exec.scenario.iterationInTest];
}

function login(acc) {
  const res = http.post(`${GATEWAY}/auth/login`, JSON.stringify({ email: acc.email, password: secrets.password }),
    { headers: { 'Content-Type': 'application/json' }, tags: { name: 'login' } });
  if (res.status !== 200) {
    throw new Error(`login ${res.status}`);
  }
  return res.json('result.auth.accessToken');
}

function headers(token, device) {
  const h = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
  if (device) h['X-Device-Id'] = device;
  return h;
}

function view(token, device, event) {
  http.get(`${GATEWAY}/events/${event.eventId}`, { headers: headers(token, device), tags: { name: 'event' } });
  http.get(`${GATEWAY}/orders/events/${event.eventId}/availability`,
    { headers: headers(token, device), tags: { name: 'availability' } });
}

function track(res) {
  if (res.status === 200 || res.status === 201) {
    adds.add(1);
    return res.json('result');
  }
  let code = null;
  try { code = res.json('code'); } catch (e) { /* không phải JSON */ }
  if (code === 4019) blocked.add(1); else rejected.add(1);
  return null;
}

function createCart(token, device, event, quantity) {
  const body = { eventId: event.eventId, items: [{ ticketTypeId: pick(event.ticketTypeIds), quantity }] };
  const h = headers(token, device);
  h['Idempotency-Key'] = crypto.randomUUID();
  return track(http.post(`${GATEWAY}/orders`, JSON.stringify(body), { headers: h, tags: { name: 'create' } }));
}

function addItems(token, device, order, event, quantity) {
  const body = { items: [{ ticketTypeId: pick(event.ticketTypeIds), quantity }] };
  const h = headers(token, device);
  h['Idempotency-Key'] = crypto.randomUUID();
  return track(http.post(`${GATEWAY}/orders/${order.id}/items`, JSON.stringify(body), { headers: h, tags: { name: 'add' } }));
}

function cancelCart(token, device, order) {
  http.del(`${GATEWAY}/orders/${order.id}`, null, { headers: headers(token, device), tags: { name: 'cancel' } });
}

// Thiết bị dùng chung giữa vài tài khoản "gia đình": cùng chỉ số /12 → cùng thiết bị
// (UUID xác định theo nhóm vì mỗi VU k6 có bản sao biến toàn cục riêng)
function humanDevice(index) {
  if (index % 12 <= 1) {
    const family = String(Math.floor(index / 12)).padStart(4, '0');
    return `00000000-0000-4000-9000-${RUN_TAG}0000${family}`;
  }
  return crypto.randomUUID();
}

// ---------- người dùng ----------

export function human() {
  const index = exec.scenario.iterationInTest;
  const acc = account('human');
  // Người dùng đến rải rác trong suốt lần chạy
  sleep(rand(0, 60) * THINK_SCALE);
  const device = humanDevice(index);
  const token = login(acc);
  http.get(`${GATEWAY}/events`, { headers: headers(token, device), tags: { name: 'events' } });
  humanThink(2, 20);

  const first = pick(EVENTS);
  view(token, device, first);
  humanThink(5, 90);
  const order = createCart(token, device, first, randInt(1, 4));

  const roll = Math.random();
  if (order && roll < 0.25) {
    // Quay lại thêm vé cho bạn bè sau khi cân nhắc
    humanThink(15, 120);
    view(token, device, first);
    humanThink(3, 30);
    addItems(token, device, order, first, randInt(1, 2));
  } else if (roll < 0.37) {
    // Xem thêm một sự kiện khác
    humanThink(10, 90);
    const second = pick(EVENTS.filter((e) => e.eventId !== first.eventId));
    view(token, device, second);
    humanThink(5, 60);
    createCart(token, device, second, randInt(1, 3));
  }
}

// ---------- bot ----------

// Dồn dập, không xem trang; một nửa không gửi thiết bị, nửa còn lại đổi thiết bị mỗi request
export function botFast() {
  const index = exec.scenario.iterationInTest;
  const acc = account('botfast');
  sleep(rand(0, 360) * THINK_SCALE);
  const token = login(acc);
  const rotate = index % 2 === 0;
  const device = () => (rotate ? crypto.randomUUID() : null);
  const events = EVENTS.slice().sort(() => Math.random() - 0.5).slice(0, randInt(2, 4));
  for (const event of events) {
    for (let churn = 0; churn < 2; churn++) {
      const order = createCart(token, device(), event, randInt(3, 5));
      if (!order) break;
      for (let i = 0; i < 3; i++) {
        sleep(rand(0.1, 0.6));
        if (!addItems(token, device(), order, event, randInt(1, 3))) break;
      }
      // Hủy giỏ để nhả rồi giữ lại (xoay tồn kho)
      sleep(rand(0.1, 0.4));
      cancelCart(token, device(), order);
    }
  }
}

// Có gọi availability (giả làm đã xem) nhưng không chờ; gom nhiều sự kiện với số lượng lớn
export function botViewer() {
  const acc = account('botviewer');
  sleep(rand(0, 360) * THINK_SCALE);
  const device = crypto.randomUUID();
  const token = login(acc);
  for (const event of EVENTS.slice().sort(() => Math.random() - 0.5).slice(0, randInt(3, 4))) {
    view(token, device, event);
    sleep(rand(0.3, 1.5));
    const order = createCart(token, device, event, randInt(4, 6));
    if (order) {
      sleep(rand(0.5, 2));
      addItems(token, device, order, event, 2);
    }
  }
}

// Trang trại tài khoản: 24 tài khoản mới trên 3 thiết bị; từng tài khoản thêm giỏ ít như người dùng
export function botFarm() {
  const index = exec.scenario.iterationInTest;
  const acc = account('botfarm');
  // Mỗi VU có bản sao biến toàn cục riêng: dùng UUID cố định theo vòng để các VU chung thiết bị thật sự
  const device = `00000000-0000-4000-8000-${RUN_TAG}0000000${index % 3}`;
  sleep(rand(0, 420) * THINK_SCALE);
  const token = login(acc);
  const event = pick(EVENTS);
  view(token, device, event);
  sleep(rand(1, 4));
  const order = createCart(token, device, event, randInt(1, 2));
  if (order && Math.random() < 0.5) {
    sleep(rand(5, 15));
    addItems(token, device, order, event, randInt(1, 2));
  }
}
