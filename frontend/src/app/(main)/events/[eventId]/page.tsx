"use client";

import Link from "next/link";
import Image from "next/image";
import { useParams, useRouter } from "next/navigation";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  CalendarDays,
  MapPin,
  Minus,
  Plus,
  Ticket,
} from "lucide-react";
import {
  EventDate,
  EventFooter,
  EventHeader,
} from "@/components/events/EventHeader";
import { getEvent } from "@/lib/api/events";
import { getErrorMessage } from "@/lib/api/client";
import type { TicketType } from "@/types/events";

const currency = new Intl.NumberFormat("vi-VN", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
});

export default function EventDetailPage() {
  const params = useParams<{ eventId: string }>();
  const router = useRouter();
  const {
    data: event,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ["event", params.eventId],
    queryFn: () => getEvent(params.eventId),
  });
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const [checkoutMessage, setCheckoutMessage] = useState(false);

  const changeQuantity = (ticket: TicketType, delta: number) =>
    setQuantities((current) => ({
      ...current,
      [ticket.id]: Math.max(
        0,
        Math.min(ticket.totalQuantity, (current[ticket.id] ?? 0) + delta),
      ),
    }));
  const gaTickets =
    event?.ticketTypes.filter(
      (ticket) => ticket.category === "GENERAL_ADMISSION",
    ) ?? [];
  const selectedGaCount = gaTickets.reduce(
    (sum, ticket) => sum + (quantities[ticket.id] ?? 0),
    0,
  );
  const goToSeats = () => {
    const ga = gaTickets
      .filter((ticket) => quantities[ticket.id])
      .map((ticket) => `${ticket.id}:${quantities[ticket.id]}`)
      .join(",");
    router.push(
      `/events/${params.eventId}/seat-map${ga ? `?ga=${encodeURIComponent(ga)}` : ""}`,
    );
  };

  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader />
      <main className="mx-auto max-w-7xl px-5 py-8 md:px-8 md:py-12">
        <Link
          href="/"
          className="inline-flex items-center gap-2 text-sm font-semibold text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
          Tất cả sự kiện
        </Link>
        {isPending ? (
          <div className="mt-8 grid gap-8 lg:grid-cols-[1fr_360px]">
            <div className="h-[420px] animate-pulse rounded-xl bg-muted" />
            <div className="h-96 animate-pulse rounded-xl bg-muted" />
          </div>
        ) : null}
        {isError ? (
          <div
            role="alert"
            className="mt-8 rounded-xl border border-destructive/30 p-6"
          >
            <h1 className="font-heading text-xl font-bold">
              Không tải được sự kiện
            </h1>
            <p className="mt-2 text-sm text-muted-foreground">
              {getErrorMessage(error)}
            </p>
            <Link
              href="/"
              className="mt-4 inline-block text-sm font-semibold text-primary"
            >
              Quay lại khám phá
            </Link>
          </div>
        ) : null}
        {event ? (
          <>
            {event.status === "CANCELLED" ? (
              <div
                role="status"
                className="mt-6 rounded-xl border border-destructive/30 bg-destructive/5 p-4"
              >
                <p className="font-semibold text-destructive">
                  Sự kiện đã bị hủy
                </p>
                <p className="mt-1 text-sm text-muted-foreground">
                  Đơn vị tổ chức đang xử lý hoàn tiền cho người tham dự.
                </p>
              </div>
            ) : null}
            <div className="mt-6 grid items-start gap-8 lg:grid-cols-[minmax(0,1fr)_360px] xl:gap-12">
              <article>
                <div className="relative aspect-[1.8/1] overflow-hidden rounded-xl bg-gradient-to-br from-primary/90 via-primary/70 to-accent">
                  {event.bannerImageUrl ? (
                    <Image
                      src={event.bannerImageUrl}
                      alt=""
                      fill
                      unoptimized
                      sizes="(max-width: 1024px) 100vw, 70vw"
                      className="object-cover"
                    />
                  ) : (
                    <div className="flex size-full items-center justify-center">
                      <Ticket
                        aria-hidden="true"
                        className="size-16 text-primary-foreground/80"
                      />
                    </div>
                  )}
                  <span className="absolute left-4 top-4 rounded-full bg-background/90 px-3 py-1.5 text-xs font-bold tracking-wide text-primary">
                    SỰ KIỆN TRỰC TIẾP
                  </span>
                </div>
                <h1 className="mt-7 font-heading text-3xl font-bold tracking-tight text-balance md:text-4xl">
                  {event.name}
                </h1>
                <div className="mt-5 grid gap-3 sm:grid-cols-2">
                  <div className="flex gap-3 rounded-xl bg-muted p-4">
                    <CalendarDays
                      aria-hidden="true"
                      className="mt-0.5 size-5 shrink-0 text-primary"
                    />
                    <div>
                      <p className="text-sm font-semibold">
                        <EventDate value={event.startAt} />
                      </p>
                      <p className="mt-1 text-sm text-muted-foreground">
                        Kết thúc <EventDate value={event.endAt} />
                      </p>
                    </div>
                  </div>
                  <div className="flex gap-3 rounded-xl bg-muted p-4">
                    <MapPin
                      aria-hidden="true"
                      className="mt-0.5 size-5 shrink-0 text-primary"
                    />
                    <div>
                      <p className="text-sm font-semibold">
                        {event.venue.name}
                      </p>
                      <p className="mt-1 text-sm text-muted-foreground">
                        {event.venue.address ? `${event.venue.address} · ` : ""}
                        {event.venue.city}
                      </p>
                    </div>
                  </div>
                </div>
                <section className="mt-9">
                  <h2 className="font-heading text-xl font-bold">
                    Về sự kiện này
                  </h2>
                  <p className="mt-3 whitespace-pre-line text-[15px] leading-7 text-muted-foreground">
                    {event.description ||
                      "Thông tin chi tiết sẽ được cập nhật sớm."}
                  </p>
                </section>
                <section className="mt-8 rounded-xl border border-border p-5">
                  <h2 className="font-heading font-bold">Địa điểm</h2>
                  <p className="mt-2 font-semibold">{event.venue.name}</p>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {event.venue.address ? `${event.venue.address} · ` : ""}
                    {event.venue.city}
                  </p>
                </section>
              </article>

              <aside className="rounded-xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-6">
                <h2 className="font-heading text-xl font-bold">
                  Chọn trải nghiệm
                </h2>
                <div className="mt-5 divide-y divide-border">
                  {event.ticketTypes.map((ticket) => (
                    <div key={ticket.id} className="py-4 first:pt-0 last:pb-0">
                      <div className="flex items-start justify-between gap-3">
                        <div>
                          <p className="font-semibold">{ticket.name}</p>
                          <p className="mt-1 text-sm text-muted-foreground">
                            {ticket.category === "SEATED"
                              ? "Chọn ghế cụ thể"
                              : "Vé vào cửa chung"}
                          </p>
                        </div>
                        <span className="shrink-0 text-sm font-bold">
                          {currency.format(ticket.price)}
                        </span>
                      </div>
                      {ticket.category === "GENERAL_ADMISSION" ? (
                        <div className="mt-3 flex items-center justify-between">
                          <span className="text-xs text-muted-foreground">
                            Số lượng
                          </span>
                          <div className="flex items-center gap-3">
                            <button
                              type="button"
                              aria-label={`Giảm số lượng ${ticket.name}`}
                              disabled={
                                (quantities[ticket.id] ?? 0) === 0 ||
                                event.status === "CANCELLED"
                              }
                              onClick={() => changeQuantity(ticket, -1)}
                              className="flex size-9 items-center justify-center rounded-lg border border-border hover:bg-muted disabled:opacity-40"
                            >
                              <Minus className="size-4" />
                            </button>
                            <output
                              aria-label={`Số vé ${ticket.name}`}
                              className="min-w-5 text-center text-sm font-semibold"
                            >
                              {quantities[ticket.id] ?? 0}
                            </output>
                            <button
                              type="button"
                              aria-label={`Tăng số lượng ${ticket.name}`}
                              disabled={
                                (quantities[ticket.id] ?? 0) >=
                                  ticket.totalQuantity ||
                                event.status === "CANCELLED"
                              }
                              onClick={() => changeQuantity(ticket, 1)}
                              className="flex size-9 items-center justify-center rounded-lg border border-border hover:bg-muted disabled:opacity-40"
                            >
                              <Plus className="size-4" />
                            </button>
                          </div>
                        </div>
                      ) : null}
                    </div>
                  ))}
                </div>
                {event.status === "CANCELLED" ? (
                  <button
                    type="button"
                    disabled
                    className="mt-6 min-h-12 w-full rounded-lg bg-muted px-4 text-sm font-semibold text-muted-foreground"
                  >
                    Không thể đặt vé
                  </button>
                ) : event.hasSeatMap ? (
                  <button
                    type="button"
                    onClick={goToSeats}
                    className="mt-6 min-h-12 w-full rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90"
                  >
                    Chọn chỗ ngồi
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => setCheckoutMessage(true)}
                    disabled={selectedGaCount === 0}
                    className="mt-6 min-h-12 w-full rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90 disabled:opacity-50"
                  >
                    Tiếp tục · {selectedGaCount} vé
                  </button>
                )}
                {checkoutMessage ? (
                  <p
                    role="status"
                    className="mt-3 text-sm text-muted-foreground"
                  >
                    Thanh toán sẽ sớm khả dụng. Vé bạn chọn vẫn được giữ trên
                    trang này.
                  </p>
                ) : null}
              </aside>
            </div>
          </>
        ) : null}
      </main>
      <EventFooter />
    </div>
  );
}
