"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { getErrorMessage } from "@/lib/api/client";
import { cancelAdminEvent, getAdminEvent, updateAdminEvent } from "@/lib/api/admin-events";

function toLocalDateTime(value: string | null) {
  if (!value) return "";
  const date = new Date(value);
  const offset = date.getTimezoneOffset() * 60_000;
  return new Date(date.getTime() - offset).toISOString().slice(0, 16);
}

export default function EditAdminEventPage() {
  const { eventId } = useParams<{ eventId: string }>();
  const queryClient = useQueryClient();
  const [message, setMessage] = useState<string | null>(null);
  const [cancelDialogOpen, setCancelDialogOpen] = useState(false);
  const eventQuery = useQuery({ queryKey: ["admin", "event", eventId], queryFn: () => getAdminEvent(eventId) });
  const updateMutation = useMutation({
    mutationFn: (request: Parameters<typeof updateAdminEvent>[1]) => updateAdminEvent(eventId, request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "event", eventId] });
      setMessage("Changes saved.");
    },
  });
  const cancelMutation = useMutation({
    mutationFn: () => cancelAdminEvent(eventId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["admin", "event", eventId] });
      setMessage("Event cancelled.");
    },
  });

  async function save(formData: FormData) {
    const name = String(formData.get("name") ?? "").trim();
    if (!name) return;
    try {
      const request: Parameters<typeof updateAdminEvent>[1] = {
        name,
        description: String(formData.get("description") ?? "").trim(),
        bannerImageUrl: String(formData.get("bannerImageUrl") ?? "").trim() || undefined,
      };
      await updateMutation.mutateAsync(request);
    } catch {
      // Display the error below using the backend ApiResponse message.
    }
  }

  const event = eventQuery.data;
  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <Link href="/admin/events" className="inline-flex min-h-10 items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><ArrowLeft aria-hidden="true" className="size-4" />Back to events</Link>
      <div><p className="text-sm font-semibold text-primary">Admin · Events</p><h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">Edit event</h1></div>

      {eventQuery.isPending ? <p role="status" aria-live="polite" className="rounded-xl border border-border bg-card p-6 text-sm text-muted-foreground">Loading event…</p> : null}
      {eventQuery.isError ? <section className="space-y-3 rounded-xl border border-border bg-card p-6"><h2 className="font-semibold">Could not load event</h2><p role="alert" className="text-sm leading-6 text-muted-foreground">{getErrorMessage(eventQuery.error)} Draft events are hidden by the current details API, so this page cannot load them for editing.</p><Button variant="outline" onClick={() => eventQuery.refetch()}>Try again</Button></section> : null}

      {event ? <>
        <p className="rounded-xl border border-border bg-background px-4 py-3 text-sm leading-6 text-muted-foreground">Details are loaded from the public API, which currently returns only PUBLISHED or CANCELLED events. Venue and ticket type fields are shown for reference and cannot be edited here.</p>
        {message ? <p role="status" aria-live="polite" className="rounded-lg border border-primary/20 bg-primary/5 px-4 py-3 text-sm text-primary">{message}</p> : null}
        {updateMutation.isError ? <p role="alert" className="rounded-lg border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">{getErrorMessage(updateMutation.error)}</p> : null}
        {cancelMutation.isError ? <p role="alert" className="rounded-lg border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">{getErrorMessage(cancelMutation.error)}</p> : null}
        <form action={save} className="grid gap-5 rounded-2xl border border-border bg-card p-5 shadow-sm md:grid-cols-2 md:p-7">
          <div className="space-y-2 md:col-span-2"><Label htmlFor="edit-event-name">Event name</Label><Input id="edit-event-name" name="name" defaultValue={event.name} maxLength={200} required disabled={event.status === "CANCELLED"} /></div>
          <div className="space-y-2 md:col-span-2"><Label htmlFor="edit-event-description">Description</Label><textarea id="edit-event-description" name="description" defaultValue={event.description} maxLength={2000} rows={4} disabled={event.status === "CANCELLED"} className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-60" /></div>
          <div className="space-y-2"><Label htmlFor="edit-event-start">Starts</Label><Input id="edit-event-start" type="datetime-local" value={toLocalDateTime(event.startAt)} disabled readOnly /></div>
          <div className="space-y-2"><Label htmlFor="edit-event-end">Ends</Label><Input id="edit-event-end" type="datetime-local" value={toLocalDateTime(event.endAt)} disabled readOnly /></div>
          <div className="space-y-2 md:col-span-2"><Label htmlFor="edit-event-banner">Banner image URL</Label><Input id="edit-event-banner" name="bannerImageUrl" type="url" defaultValue={event.bannerImageUrl ?? ""} maxLength={500} disabled={event.status === "CANCELLED"} /></div>
          <div className="flex flex-col-reverse justify-between gap-3 border-t border-border pt-5 md:col-span-2 sm:flex-row">
            <p className="self-center text-sm text-muted-foreground">{event.status === "CANCELLED" ? "This event is cancelled and cannot be edited." : `Status: ${event.status === "PUBLISHED" ? "Published" : "Draft"} · ${event.venue.name}`}</p>
            <div className="flex gap-2">{event.status === "PUBLISHED" ? <Button type="button" variant="outline" disabled={cancelMutation.isPending} onClick={() => setCancelDialogOpen(true)}>Cancel event</Button> : null}<Button type="submit" disabled={event.status === "CANCELLED" || updateMutation.isPending}>{updateMutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : null}Save changes</Button></div>
          </div>
        </form>
        <section className="rounded-2xl border border-border bg-card p-5"><h2 className="font-heading font-semibold">Existing ticket types</h2>{event.ticketTypes.length ? <ul className="mt-3 divide-y divide-border">{event.ticketTypes.map((ticket) => <li key={ticket.id} className="flex justify-between gap-3 py-3 text-sm"><span>{ticket.name} · {ticket.category === "SEATED" ? "Reserved seating" : "General admission"}</span><span className="text-muted-foreground">{ticket.totalQuantity} tickets · ${ticket.price.toFixed(2)}</span></li>)}</ul> : <p className="mt-2 text-sm text-muted-foreground">No ticket types yet.</p>}</section>
        <Dialog open={cancelDialogOpen} onOpenChange={setCancelDialogOpen}>
          <DialogContent>
            <DialogHeader><DialogTitle>Cancel event?</DialogTitle><DialogDescription>This action cannot be undone. Buyers will see the event as cancelled.</DialogDescription></DialogHeader>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setCancelDialogOpen(false)}>Go back</Button>
              <Button type="button" variant="outline" className="border-destructive text-destructive hover:bg-destructive/10" disabled={cancelMutation.isPending} onClick={() => cancelMutation.mutate()}>{cancelMutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : null}Confirm cancellation</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </> : null}
    </div>
  );
}
