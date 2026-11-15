"use client";

import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { useRouter } from "next/navigation";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  AlertCircle,
  Camera,
  CameraOff,
  CheckCircle2,
  LoaderCircle,
  ScanLine,
  XCircle,
} from "lucide-react";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";
import { getAllEvents } from "@/lib/api/events";
import { checkInTicket, type CheckInOutcome } from "@/lib/api/orders";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";

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

const scanTime = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "medium",
});

export default function CheckInPage() {
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const canScan = user?.role === "ADMIN" || user?.role === "STAFF";
  const [eventId, setEventId] = useState("");
  const [code, setCode] = useState("");
  const [cameraOn, setCameraOn] = useState(false);
  const [cameraError, setCameraError] = useState<string | null>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const hasDetector = useSyncExternalStore(
    subscribeNoop,
    () => getBarcodeDetector() !== null,
    () => false,
  );

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
  const checkInMutation = useMutation({
    mutationFn: (scanned: string) => checkInTicket(eventId, scanned),
  });
  const { mutate: submitCode, isPending: isSubmitting } = checkInMutation;
  const result: CheckInOutcome | undefined = checkInMutation.data;
  // Đang hiện kết quả thì dừng quét để một mã không bị gửi liên tục khi còn trong khung hình
  const awaitingNext = checkInMutation.isSuccess || checkInMutation.isError;

  useEffect(() => {
    const Detector = getBarcodeDetector();
    if (!cameraOn || !Detector || !eventId || awaitingNext) return;
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
        if (value && !stopped) {
          setCode(value);
          submitCode(value);
          return;
        }
      } catch {
        // Một khung hình lỗi không dừng việc quét
      }
      timer = window.setTimeout(() => void scan(), 300);
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
        setCameraError(
          "Camera access was blocked. Allow camera access or type the code instead.",
        );
        setCameraOn(false);
      });
    return () => {
      stopped = true;
      window.clearTimeout(timer);
      stream?.getTracks().forEach((track) => track.stop());
    };
  }, [awaitingNext, cameraOn, eventId, submitCode]);

  const nextTicket = () => {
    checkInMutation.reset();
    setCode("");
  };

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
      <main className="mx-auto max-w-3xl px-5 py-8 md:px-8">
        <p className="text-xs font-bold uppercase tracking-[0.15em] text-primary">
          Entrance
        </p>
        <h1 className="mt-2 font-heading text-3xl font-bold tracking-tight">
          Ticket check-in
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Choose the event you are working at, then scan or type each ticket
          code. A ticket can only be checked in once.
        </p>

        <section className="mt-7 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7">
          <label htmlFor="check-in-event" className="text-sm font-semibold">
            Event
          </label>
          {eventsQuery.isError ? (
            <p role="alert" className="mt-2 text-sm text-destructive">
              {getErrorMessage(eventsQuery.error, "Could not load events.")}
            </p>
          ) : null}
          <select
            id="check-in-event"
            value={eventId}
            onChange={(event) => {
              setEventId(event.target.value);
              nextTicket();
            }}
            disabled={eventsQuery.isPending}
            className="mt-2 block min-h-11 w-full rounded-lg border border-border bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60"
          >
            <option value="">
              {eventsQuery.isPending ? "Loading events…" : "Select an event"}
            </option>
            {eventsQuery.data
              ?.filter((event) => event.status === "PUBLISHED")
              .map((event) => (
                <option key={event.id} value={event.id}>
                  {event.name} · {event.venue.name}
                </option>
              ))}
          </select>

          <form
            className="mt-6"
            onSubmit={(event) => {
              event.preventDefault();
              if (eventId && code.trim()) submitCode(code.trim());
            }}
          >
            <label htmlFor="ticket-code" className="text-sm font-semibold">
              Ticket code
            </label>
            <div className="mt-2 flex flex-col gap-3 sm:flex-row">
              <input
                id="ticket-code"
                value={code}
                onChange={(event) => setCode(event.target.value)}
                maxLength={64}
                autoComplete="off"
                spellCheck={false}
                placeholder="Scan or paste the code"
                disabled={!eventId || isSubmitting || awaitingNext}
                className="min-h-11 flex-1 rounded-lg border border-border bg-background px-3 font-mono text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60"
              />
              <button
                type="submit"
                disabled={!eventId || !code.trim() || isSubmitting || awaitingNext}
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {isSubmitting ? (
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
                disabled={!eventId}
                className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted disabled:cursor-not-allowed disabled:opacity-50"
              >
                {cameraOn ? (
                  <CameraOff aria-hidden="true" className="size-4" />
                ) : (
                  <Camera aria-hidden="true" className="size-4" />
                )}
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
              Camera scanning is not supported in this browser. Use a USB/Bluetooth
              scanner or type the code.
            </p>
          )}
          {cameraError ? (
            <p role="alert" className="mt-3 text-sm text-destructive">
              {cameraError}
            </p>
          ) : null}
        </section>

        <div aria-live="assertive" className="mt-6">
          {result?.outcome === "CHECKED_IN" ? (
            <section className="flex items-start gap-3 rounded-2xl border border-primary/30 bg-accent p-5">
              <CheckCircle2 aria-hidden="true" className="mt-0.5 size-6 text-primary" />
              <div>
                <h2 className="font-heading text-lg font-bold">Valid ticket — let them in</h2>
                <p className="mt-1 text-sm">
                  {result.ticket.ticketName}
                  {result.ticket.seatLabel ? ` · ${result.ticket.seatLabel}` : ""}
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  Checked in at {scanTime.format(new Date(result.ticket.checkedInAt))}
                </p>
              </div>
            </section>
          ) : null}
          {result?.outcome === "ALREADY_CHECKED_IN" ? (
            <section className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-5">
              <XCircle aria-hidden="true" className="mt-0.5 size-6 text-destructive" />
              <div>
                <h2 className="font-heading text-lg font-bold">Already checked in — do not admit</h2>
                <p className="mt-1 text-sm">
                  {result.ticket.ticketName}
                  {result.ticket.seatLabel ? ` · ${result.ticket.seatLabel}` : ""}
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  First scanned at {scanTime.format(new Date(result.ticket.checkedInAt))} by{" "}
                  {result.ticket.checkedInBy === user?.id
                    ? "you"
                    : `staff ID ${result.ticket.checkedInBy.slice(0, 8)}`}
                  .
                </p>
              </div>
            </section>
          ) : null}
          {checkInMutation.isError ? (
            <section className="flex items-start gap-3 rounded-2xl border border-destructive/40 bg-destructive/5 p-5">
              <AlertCircle aria-hidden="true" className="mt-0.5 size-6 text-destructive" />
              <div>
                <h2 className="font-heading text-lg font-bold">Ticket not accepted</h2>
                <p className="mt-1 text-sm text-muted-foreground">
                  {getErrorMessage(
                    checkInMutation.error,
                    "Could not check in this ticket. Try again.",
                  )}
                </p>
              </div>
            </section>
          ) : null}
          {awaitingNext ? (
            <button
              type="button"
              onClick={nextTicket}
              autoFocus
              className="mt-4 inline-flex min-h-11 w-full items-center justify-center rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90"
            >
              Scan next ticket
            </button>
          ) : null}
        </div>
      </main>
      <EventFooter />
    </div>
  );
}
