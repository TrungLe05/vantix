"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ArrowLeft, CalendarDays, MapPin, Pencil } from "lucide-react";
import { Button } from "@/components/ui/button";
import { getErrorMessage } from "@/lib/api/client";
import { getAdminEvent } from "@/lib/api/admin-events";

function formatDate(value: string) {
  return new Intl.DateTimeFormat("en-US", { dateStyle: "long", timeStyle: "short" }).format(new Date(value));
}

export default function AdminEventDetailPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const eventQuery = useQuery({ queryKey: ["admin", "event", eventId], queryFn: () => getAdminEvent(eventId) });
  const event = eventQuery.data;

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <Link href="/admin/events" className="inline-flex min-h-10 items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><ArrowLeft aria-hidden="true" className="size-4" />Back to events</Link>
      {eventQuery.isPending ? <p role="status" aria-live="polite" className="rounded-xl border border-border bg-card p-6 text-sm text-muted-foreground">Loading event details…</p> : null}
      {eventQuery.isError ? <section className="space-y-3 rounded-xl border border-border bg-card p-6"><h1 className="font-heading text-xl font-semibold">Could not load event</h1><p role="alert" className="text-sm text-muted-foreground">{getErrorMessage(eventQuery.error)} Event details are currently available only for PUBLISHED or CANCELLED events.</p><Button variant="outline" onClick={() => eventQuery.refetch()}>Try again</Button></section> : null}
      {event ? <>
        <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end"><div><p className="text-sm font-semibold text-primary">Admin · Event</p><h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">{event.name}</h1><span className="mt-3 inline-flex rounded-full border border-primary/20 bg-primary/5 px-3 py-1 text-xs font-semibold text-primary">{event.status === "PUBLISHED" ? "Published" : "Cancelled"}</span></div><Button asChild><Link href={`/admin/events/${event.id}/edit`}><Pencil aria-hidden="true" />Edit</Link></Button></div>
        <section className="grid gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm sm:grid-cols-2 md:p-7" aria-label="Event information">
          <div className="flex gap-3"><CalendarDays aria-hidden="true" className="mt-0.5 size-5 text-primary" /><div><p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Date and time</p><p className="mt-1 font-medium">{formatDate(event.startAt)}</p><p className="text-sm text-muted-foreground">to {formatDate(event.endAt)}</p></div></div>
          <div className="flex gap-3"><MapPin aria-hidden="true" className="mt-0.5 size-5 text-primary" /><div><p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Venue</p><p className="mt-1 font-medium">{event.venue.name}</p><p className="text-sm text-muted-foreground">{event.venue.address}, {event.venue.city}</p></div></div>
          {event.salesStartAt || event.salesEndAt ? <div className="sm:col-span-2"><p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Sales window</p><p className="mt-1 text-sm">{event.salesStartAt ? formatDate(event.salesStartAt) : "Not set"} — {event.salesEndAt ? formatDate(event.salesEndAt) : "Not set"}</p></div> : null}
          <div className="sm:col-span-2"><p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Description</p><p className="mt-2 whitespace-pre-wrap text-sm leading-6">{event.description || "No description."}</p></div>
        </section>
        <section className="rounded-2xl border border-border bg-card p-5 shadow-sm md:p-7"><h2 className="font-heading text-lg font-semibold">Ticket types</h2>{event.ticketTypes.length ? <ul className="mt-3 divide-y divide-border">{event.ticketTypes.map((ticket) => <li key={ticket.id} className="flex flex-wrap justify-between gap-3 py-3 text-sm"><span><span className="font-semibold">{ticket.name}</span><span className="ml-2 text-muted-foreground">{ticket.category === "SEATED" ? "Reserved seating" : "General admission"}</span></span><span className="text-muted-foreground">${ticket.price.toFixed(2)} · {ticket.totalQuantity} tickets</span></li>)}</ul> : <p className="mt-2 text-sm text-muted-foreground">No ticket types yet.</p>}</section>
      </> : null}
    </div>
  );
}
