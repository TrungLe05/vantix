"use client";

import Link from "next/link";
import { Suspense, useMemo, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import { useQueries } from "@tanstack/react-query";
import { ArrowLeft, Check, Info, Ticket } from "lucide-react";
import { EventDate, EventFooter, EventHeader } from "@/components/events/EventHeader";
import { getEvent, getSeatMap } from "@/lib/api/events";
import { getErrorMessage } from "@/lib/api/client";
import type { Seat } from "@/types/events";

const currency = new Intl.NumberFormat("vi-VN", { style: "currency", currency: "VND", maximumFractionDigits: 0 });

function SeatMapContent() {
  const params = useParams<{ eventId: string }>();
  const searchParams = useSearchParams();
  const [eventQuery, seatQuery] = useQueries({ queries: [
    { queryKey: ["event", params.eventId], queryFn: () => getEvent(params.eventId) },
    { queryKey: ["seat-map", params.eventId], queryFn: () => getSeatMap(params.eventId) },
  ] });
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set());
  const [checkoutMessage, setCheckoutMessage] = useState(false);
  const event = eventQuery.data;
  const seatMap = seatQuery.data;

  const grouped = useMemo(() => {
    const sections = new Map<string, Map<string, Seat[]>>();
    for (const seat of seatMap?.seats ?? []) {
      if (!seat.ticketType) continue;
      if (!sections.has(seat.section)) sections.set(seat.section, new Map());
      const rows = sections.get(seat.section)!;
      if (!rows.has(seat.rowLabel)) rows.set(seat.rowLabel, []);
      rows.get(seat.rowLabel)!.push(seat);
    }
    for (const rows of sections.values()) for (const seats of rows.values()) seats.sort((a, b) => a.seatNumber.localeCompare(b.seatNumber, undefined, { numeric: true }));
    return sections;
  }, [seatMap]);

  const selectedSeats = (seatMap?.seats ?? []).filter((seat) => selectedIds.has(seat.id));
  const gaSelections = (searchParams.get("ga") ?? "").split(",").flatMap((value) => {
    const [ticketId, count] = value.split(":");
    const ticket = event?.ticketTypes.find((item) => item.id === ticketId && item.category === "GENERAL_ADMISSION");
    return ticket && Number(count) > 0 ? [{ ticket, count: Number(count) }] : [];
  });
  const gaCount = gaSelections.reduce((sum, item) => sum + item.count, 0);
  const total = selectedSeats.reduce((sum, seat) => sum + (seat.ticketType?.price ?? 0), 0) + gaSelections.reduce((sum, item) => sum + item.ticket.price * item.count, 0);
  const selectedCount = selectedSeats.length + gaSelections.reduce((sum, item) => sum + item.count, 0);
  const toggleSeat = (seatId: string) => setSelectedIds((current) => {
    const next = new Set(current);
    if (next.has(seatId)) next.delete(seatId);
    else if (next.size + gaCount < 8) next.add(seatId);
    return next;
  });
  const isPending = eventQuery.isPending || seatQuery.isPending;
  const error = eventQuery.isError ? eventQuery.error : seatQuery.error;

  return <div className="min-h-screen bg-background text-foreground">
    <EventHeader />
    <main className="mx-auto max-w-7xl px-5 py-8 md:px-8">
      <Link href={`/events/${params.eventId}`} className="inline-flex items-center gap-2 text-sm font-semibold text-muted-foreground hover:text-foreground"><ArrowLeft aria-hidden="true" className="size-4" />Quay lại sự kiện</Link>
      {isPending ? <div className="mt-8 h-[520px] animate-pulse rounded-xl bg-muted" /> : null}
      {error ? <div role="alert" className="mt-8 rounded-xl border border-destructive/30 p-6"><p className="font-semibold">Không tải được sơ đồ ghế</p><p className="mt-2 text-sm text-muted-foreground">{getErrorMessage(error)}</p></div> : null}
      {event && seatMap ? <>
        <div className="mt-6"><p className="text-xs font-bold tracking-[0.15em] text-primary">{event.name}</p><h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">Chọn chỗ ngồi</h1><p className="mt-2 text-sm text-muted-foreground"><EventDate value={event.startAt} /> · {event.venue.name}</p></div>
        <p className="mt-5 inline-flex items-center gap-2 rounded-full bg-muted px-3 py-2 text-xs font-medium text-muted-foreground"><Info aria-hidden="true" className="size-4" />Chọn tối đa 8 ghế. Ghế được xác nhận ở bước thanh toán.</p>
        <div className="mt-7 grid items-start gap-7 lg:grid-cols-[minmax(0,1fr)_340px]">
          <section aria-label="Sơ đồ ghế" className="min-w-0 rounded-xl border border-border bg-card p-4 sm:p-7">
            <div className="mx-auto mb-9 max-w-2xl rounded-b-[50%] border-t-4 border-primary/50 bg-primary/5 py-3 text-center text-xs font-bold tracking-[0.25em] text-muted-foreground">SÂN KHẤU</div>
            {grouped.size === 0 ? <p className="py-10 text-center text-sm text-muted-foreground">Sơ đồ ghế hiện chưa có chỗ khả dụng.</p> : <div className="space-y-8">
              {[...grouped.entries()].map(([section, rows]) => {
                const sectionSeats = [...rows.values()].flat();
                const ticket = sectionSeats[0]?.ticketType;
                return <section key={section} aria-label={`Khu ${section}`}><div className="mb-4 flex flex-wrap items-center justify-between gap-2 border-b border-border pb-3"><div><h2 className="font-semibold">{section}</h2><p className="text-xs text-muted-foreground">{currency.format(ticket?.price ?? 0)} / ghế</p></div><span className="text-xs text-muted-foreground">{ticket?.name}</span></div>
                  <div className="space-y-3">{[...rows.entries()].map(([row, seats]) => <div key={row} className="flex items-center gap-3"><span className="w-5 text-center text-xs font-semibold text-muted-foreground">{row}</span><div className="flex flex-wrap gap-2">{seats.map((seat) => { const selected = selectedIds.has(seat.id); return <button key={seat.id} type="button" aria-pressed={selected} aria-label={`Ghế ${section} ${seat.displayLabel}${selected ? ", đã chọn" : ""}`} onClick={() => toggleSeat(seat.id)} className={`flex size-9 items-center justify-center rounded-md border text-[10px] font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected ? "border-primary bg-primary text-primary-foreground" : "border-border bg-background hover:border-primary hover:bg-accent"}`} title={`${seat.displayLabel} · ${currency.format(seat.ticketType?.price ?? 0)}`}>{selected ? <Check aria-hidden="true" className="size-4" /> : seat.seatNumber}</button>; })}</div></div>)}</div>
                </section>;
              })}
            </div>}
            <div className="mt-8 flex flex-wrap gap-5 border-t border-border pt-5 text-xs text-muted-foreground"><span className="inline-flex items-center gap-2"><span className="size-3 rounded-sm border border-border bg-background" />Còn trống</span><span className="inline-flex items-center gap-2"><span className="size-3 rounded-sm bg-primary" />Đang chọn</span></div>
          </section>
          <aside className="rounded-xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-6"><h2 className="font-heading text-xl font-bold">Vé của bạn</h2>
            <div className="mt-5 space-y-4">{selectedSeats.map((seat) => <div key={seat.id} className="flex justify-between gap-3 text-sm"><span>{seat.ticketType?.name} · {seat.displayLabel}</span><span className="font-semibold">{currency.format(seat.ticketType?.price ?? 0)}</span></div>)}{gaSelections.map(({ ticket, count }) => <div key={ticket.id} className="flex justify-between gap-3 text-sm"><span>{ticket.name} × {count}</span><span className="font-semibold">{currency.format(ticket.price * count)}</span></div>)}{selectedCount === 0 ? <div className="rounded-lg bg-muted p-4 text-sm text-muted-foreground">Chọn ghế để xem tóm tắt vé tại đây.</div> : null}</div>
            <div className="mt-5 flex justify-between border-t border-border pt-4 text-sm"><span className="font-semibold">Tạm tính · {selectedCount} vé</span><span className="font-bold">{currency.format(total)}</span></div>
            <p className="mt-2 text-xs text-muted-foreground">Phí dịch vụ được tính ở bước thanh toán.</p>
            <button type="button" onClick={() => setCheckoutMessage(true)} disabled={selectedCount === 0} className="mt-5 min-h-12 w-full rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90 disabled:opacity-50">Tiếp tục</button>
            {checkoutMessage ? <p role="status" className="mt-3 text-sm text-muted-foreground">Thanh toán sẽ sớm khả dụng. Lựa chọn hiện tại chưa được giữ chỗ.</p> : null}
            <Link href={`/events/${params.eventId}`} className="mt-4 flex items-center justify-center gap-2 text-xs font-semibold text-muted-foreground hover:text-foreground"><Ticket aria-hidden="true" className="size-4" />Xem thông tin sự kiện</Link>
          </aside>
        </div>
      </> : null}
    </main>
    <EventFooter />
  </div>;
}

export default function SeatMapPage() {
  return <Suspense fallback={<div className="min-h-screen animate-pulse bg-muted" />}><SeatMapContent /></Suspense>;
}
