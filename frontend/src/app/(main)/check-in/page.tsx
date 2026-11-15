"use client";

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import {
  AlertCircle,
  ArrowLeft,
  CalendarClock,
  Camera,
  CameraOff,
  CheckCircle2,
  LoaderCircle,
  MapPin,
  ScanLine,
  XCircle,
} from "lucide-react";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";
import { getAllEvents } from "@/lib/api/events";
import { checkInTicket, type CheckInOutcome } from "@/lib/api/orders";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";
import type { EventSummary } from "@/types/events";

// BarcodeDetector chưa có trong lib.dom của TypeScript: khai báo phần đang dùng.
// Có trên Chrome/Edge/Android; trình duyệt khác STAFF nhập mã tay (không thêm thư viện quét, D36).
interface DetectedBarcode {
  rawValue: string;
}
interface BarcodeDetectorLike {
  detect(source: HTMLVideoElement): Promise<DetectedBarcode[]>;
}
type BarcodeDetectorConstructor = new (options: {
  formats: string[];
}) => BarcodeDetectorLike;

function getBarcodeDetector(): BarcodeDetectorConstructor | null {
  if (typeof window === "undefined") return null;
  const candidate = (window as unknown as { BarcodeDetector?: BarcodeDetectorConstructor })
    .BarcodeDetector;
  return candidate ?? null;
}

const subscribeNoop = () => () => {};

// Khớp app.order.check-in-opens-before của order-service (D44). Backend vẫn là nơi quyết định.
const CHECK_IN_OPENS_BEFORE_MS = 3 * 60 * 60 * 1000;
// Chỉ liệt kê sự kiện bắt đầu trong 7 ngày tới (hoặc đang diễn ra)
const UPCOMING_HORIZON_MS = 7 * 24 * 60 * 60 * 1000;
// Camera thấy cùng một mã liên tục: bỏ qua mã vừa gửi trong khoảng này để không gửi lặp
const SAME_CODE_COOLDOWN_MS = 4000;

const time = new Intl.DateTimeFormat("en-US", { timeStyle: "short" });
const dateTime = new Intl.DateTimeFormat("en-US", { weekday: "short", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });

interface ScanEntry {
  id: number;
  code: string;
  outcome: CheckInOutcome | null;
  error: unknown;
}

function windowOf(event: EventSummary) {
  const start = new Date(event.startAt).getTime();
  return { opensAt: start - CHECK_IN_OPENS_BEFORE_MS, closesAt: new Date(event.endAt).getTime() };
}

function useNow(intervalMs: number) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export default function CheckInPage() {
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const canScan = user?.role === "ADMIN" || user?.role === "STAFF";
  const now = useNow(30_000);
  const [selected, setSelected] = useState<EventSummary | null>(null);

  // Phân quyền thật ở backend (OR13 chỉ ADMIN/STAFF); ở đây chỉ để không hiện trang cho người không dùng được
  useEffect(() => {
    if (!user) router.replace("/login");
    else if (!canScan) router.replace("/403");
  }, [canScan, router, user]);

  const eventsQuery = useQuery({
    queryKey: ["events", "all"],
    queryFn: getAllEvents,
    enabled: canScan,
  });

  const upcoming = (eventsQuery.data ?? [])
    .filter((event) => event.status === "PUBLISHED")
    .filter((event) => {
      const { closesAt } = windowOf(event);
      return closesAt > now && new Date(event.startAt).getTime() - now <= UPCOMING_HORIZON_MS;
    })
    .sort((a, b) => new Date(a.startAt).getTime() - new Date(b.startAt).getTime());

  if (!canScan) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-background px-5">
        <p role="status" aria-live="polite" className="text-sm text-muted-foreground">
          Checking access…
        </p>
      </main>
    );
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader />
      <main className="mx-auto max-w-4xl px-5 py-8 md:px-8">
        <p className="text-xs font-bold uppercase tracking-[0.15em] text-primary">Entrance</p>
        <h1 className="mt-2 font-heading text-3xl font-bold tracking-tight">Ticket check-in</h1>

        {selected ? (
          <Scanner event={selected} now={now} onBack={() => setSelected(null)} />
        ) : (
          <>
            <p className="mt-2 text-sm text-muted-foreground">
              Choose the event you are working at. Check-in opens 3 hours before an event starts and
              closes when it ends.
            </p>
            {eventsQuery.isPending ? (
              <div className="mt-6 grid gap-4 sm:grid-cols-2">
                {[0, 1].map((key) => (
                  <div key={key} className="h-36 animate-pulse rounded-2xl bg-muted" />
                ))}
              </div>
            ) : null}
            {eventsQuery.isError ? (
              <p role="alert" className="mt-6 text-sm text-destructive">
                {getErrorMessage(eventsQuery.error, "Could not load events.")}
              </p>
            ) : null}
            {eventsQuery.data && upcoming.length === 0 ? (
              <div className="mt-6 rounded-2xl border border-border bg-card p-8 text-center">
                <CalendarClock aria-hidden="true" className="mx-auto size-8 text-muted-foreground" />
                <p className="mt-3 font-semibold">No events in the next 7 days</p>
                <p className="mt-1 text-sm text-muted-foreground">Upcoming events will appear here.</p>
              </div>
            ) : null}
            <ul className="mt-6 grid gap-4 sm:grid-cols-2">
              {upcoming.map((event) => {
                const { opensAt } = windowOf(event);
                const open = now >= opensAt;
                return (
                  <li key={event.id}>
                    <button
                      type="button"
                      disabled={!open}
                      onClick={() => setSelected(event)}
                      className="flex h-full w-full flex-col rounded-2xl border border-border bg-card p-5 text-left shadow-sm transition hover:border-primary/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-70"
                    >
                      <span
                        className={`inline-flex w-fit rounded-full px-2.5 py-1 text-xs font-semibold ${
                          open ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"
                        }`}
                      >
                        {open ? "Check-in open" : `Opens ${dateTime.format(new Date(opensAt))}`}
                      </span>
                      <span className="mt-3 font-heading text-lg font-bold">{event.name}</span>
                      <span className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground">
                        <CalendarClock aria-hidden="true" className="size-4" />
                        {dateTime.format(new Date(event.startAt))} – {time.format(new Date(event.endAt))}
                      </span>
                      <span className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground">
                        <MapPin aria-hidden="true" className="size-4" />
                        {event.venue.name}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </>
        )}
      </main>
      <EventFooter />
    </div>
  );
}

function Scanner({ event, now, onBack }: { event: EventSummary; now: number; onBack: () => void }) {
  const user = useAuthStore((state) => state.user);
  const [code, setCode] = useState("");
  const [cameraOn, setCameraOn] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const [scans, setScans] = useState<ScanEntry[]>([]);
  const [pending, setPending] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const lastCode = useRef<{ code: string; at: number } | null>(null);
  const counter = useRef(0);
  const hasDetector = useSyncExternalStore(subscribeNoop, () => getBarcodeDetector() !== null, () => false);
  const closed = now > windowOf(event).closesAt;

  // Gửi mã và giữ máy quét chạy tiếp: không cần bấm "quét vé kế" ở cổng đông người
  const submit = useCallback((raw: string) => {
    const value = raw.trim();
    if (!value) return;
    const stamp = Date.now();
    if (lastCode.current && lastCode.current.code === value && stamp - lastCode.current.at < SAME_CODE_COOLDOWN_MS) {
      return;
    }
    lastCode.current = { code: value, at: stamp };
    const id = ++counter.current;
    setPending(true);
    checkInTicket(event.id, value)
      .then((outcome) => setScans((list) => [{ id, code: value, outcome, error: null }, ...list].slice(0, 6)))
      .catch((error: unknown) => setScans((list) => [{ id, code: value, outcome: null, error }, ...list].slice(0, 6)))
      .finally(() => {
        setPending(false);
        inputRef.current?.focus();
      });
  }, [event.id]);

  useEffect(() => {
    const Detector = getBarcodeDetector();
    if (!cameraOn || !Detector) return;
    let stream: MediaStream | null = null;
    let timer: number | undefined;
    let stopped = false;
    const detector = new Detector({ formats: ["qr_code"] });
    const scan = async () => {
      const video = videoRef.current;
      if (stopped || !video) return;
      try {
        const codes = video.readyState >= 2 ? await detector.detect(video) : [];
        const value = codes[0]?.rawValue?.trim();
        if (value && !stopped) submit(value);
      } catch {
        // Một khung hình lỗi không dừng việc quét
      }
      timer = window.setTimeout(() => void scan(), 250);
    };
    navigator.mediaDevices
      .getUserMedia({ video: { facingMode: "environment" } })
      .then((media) => {
        if (stopped) {
          media.getTracks().forEach((track) => track.stop());
          return;
        }
        stream = media;
        if (videoRef.current) {
          videoRef.current.srcObject = media;
          void videoRef.current.play();
        }
        void scan();
      })
      .catch(() => {
        setCameraError("Camera access was blocked. Allow camera access or type the code instead.");
        setCameraOn(false);
      });
    return () => {
      stopped = true;
      window.clearTimeout(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [cameraOn, submit]);

  const latest = scans[0];

  return (
    <div className="mt-4">
      <button
        type="button"
        onClick={onBack}
        className="inline-flex min-h-10 items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground"
      >
        <ArrowLeft aria-hidden="true" className="size-4" />
        All events
      </button>
      <section className="mt-3 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7">
        <h2 className="font-heading text-xl font-bold">{event.name}</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {event.venue.name} · {dateTime.format(new Date(event.startAt))}
        </p>
        {closed ? (
          <p role="alert" className="mt-4 rounded-lg bg-destructive/5 p-3 text-sm text-destructive">
            This event has ended. Check-in is closed.
          </p>
        ) : null}

        <form
          className="mt-6"
          onSubmit={(formEvent) => {
            formEvent.preventDefault();
            submit(code);
            setCode("");
          }}
        >
          <label htmlFor="ticket-code" className="text-sm font-semibold">
            Ticket code
          </label>
          <div className="mt-2 flex flex-col gap-3 sm:flex-row">
            <input
              ref={inputRef}
              id="ticket-code"
              value={code}
              onChange={(changeEvent) => setCode(changeEvent.target.value)}
              maxLength={64}
              autoComplete="off"
              autoFocus
              spellCheck={false}
              placeholder="Scan or paste the code"
              disabled={closed}
              className="min-h-11 flex-1 rounded-lg border border-border bg-background px-3 font-mono text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60"
            />
            <button
              type="submit"
              disabled={closed || !code.trim()}
              className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
            >
              {pending ? (
                <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
              ) : (
                <ScanLine aria-hidden="true" className="size-4" />
              )}
              Check in
            </button>
          </div>
        </form>

        {hasDetector ? (
          <div className="mt-5">
            <button
              type="button"
              onClick={() => {
                setCameraError(null);
                setCameraOn((on) => !on);
              }}
              disabled={closed}
              className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
            >
              {cameraOn ? <CameraOff aria-hidden="true" className="size-4" /> : <Camera aria-hidden="true" className="size-4" />}
              {cameraOn ? "Stop camera" : "Scan with camera"}
            </button>
            {cameraOn ? (
              <video
                ref={videoRef}
                muted
                playsInline
                aria-label="Camera preview for scanning QR codes"
                className="mt-4 aspect-video w-full rounded-xl bg-black object-cover"
              />
            ) : null}
          </div>
        ) : (
          <p className="mt-5 text-xs text-muted-foreground">
            Camera scanning is not supported in this browser. Use a USB/Bluetooth scanner or type the code.
          </p>
        )}
        {cameraError ? (
          <p role="alert" className="mt-3 text-sm text-destructive">
            {cameraError}
          </p>
        ) : null}
      </section>

      <div aria-live="assertive" className="mt-6">
        {latest ? <ScanResult entry={latest} currentUserId={user?.id} /> : null}
      </div>
      {scans.length > 1 ? (
        <section className="mt-6" aria-labelledby="recent-scans">
          <h3 id="recent-scans" className="text-sm font-semibold text-muted-foreground">
            Earlier scans
          </h3>
          <ul className="mt-2 divide-y divide-border rounded-xl border border-border bg-card text-sm">
            {scans.slice(1).map((entry) => (
              <li key={entry.id} className="flex items-center justify-between gap-3 px-4 py-2.5">
                <span className="truncate font-mono text-xs">{entry.code.slice(0, 12)}…</span>
                <span
                  className={
                    entry.outcome?.outcome === "CHECKED_IN" ? "font-semibold text-primary" : "font-semibold text-destructive"
                  }
                >
                  {entry.outcome?.outcome === "CHECKED_IN"
                    ? "Admitted"
                    : entry.outcome?.outcome === "ALREADY_CHECKED_IN"
                      ? "Already used"
                      : "Rejected"}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}

function ScanResult({ entry, currentUserId }: { entry: ScanEntry; currentUserId?: string }) {
  const scanTime = new Intl.DateTimeFormat("en-US", { dateStyle: "medium", timeStyle: "medium" });
  if (entry.outcome?.outcome === "CHECKED_IN") {
    const ticket = entry.outcome.ticket;
    return (
      <section className="flex items-start gap-3 rounded-2xl border border-primary/30 bg-accent p-5">
        <CheckCircle2 aria-hidden="true" className="mt-0.5 size-6 text-primary" />
        <div>
          <h2 className="font-heading text-lg font-bold">Valid ticket — let them in</h2>
          <p className="mt-1 text-sm">
            {ticket.ticketName}
            {ticket.seatLabel ? ` · ${ticket.seatLabel}` : ""}
          </p>
        </div>
      </section>
    );
  }
  if (entry.outcome?.outcome === "ALREADY_CHECKED_IN") {
    const ticket = entry.outcome.ticket;
    return (
      <section className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-5">
        <XCircle aria-hidden="true" className="mt-0.5 size-6 text-destructive" />
        <div>
          <h2 className="font-heading text-lg font-bold">Already checked in — do not admit</h2>
          <p className="mt-1 text-sm">
            {ticket.ticketName}
            {ticket.seatLabel ? ` · ${ticket.seatLabel}` : ""}
          </p>
          <p className="mt-1 text-sm text-muted-foreground">
            First scanned at {scanTime.format(new Date(ticket.checkedInAt))} by{" "}
            {ticket.checkedInBy === currentUserId ? "you" : `staff ID ${ticket.checkedInBy.slice(0, 8)}`}.
          </p>
        </div>
      </section>
    );
  }
  return (
    <section className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-5">
      <AlertCircle aria-hidden="true" className="mt-0.5 size-6 text-destructive" />
      <div>
        <h2 className="font-heading text-lg font-bold">Ticket not accepted</h2>
        <p className="mt-1 text-sm text-muted-foreground">
          {getErrorMessage(entry.error, "Could not check in this ticket. Try again.")}
        </p>
      </div>
    </section>
  );
}
