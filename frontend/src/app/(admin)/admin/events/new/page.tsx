"use client";

import Link from "next/link";
import { useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Check, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { getErrorMessage } from "@/lib/api/client";
import {
  addSeats,
  createAdminEvent,
  createSeatMap,
  createTicketType,
  publishAdminEvent,
  type AdminEvent,
  type AdminSeat,
} from "@/lib/api/admin-events";
import { getVenues } from "@/lib/api/venues";

const steps = ["Details", "Seats & tickets", "Publish"];

function toIso(value: string) {
  return value ? new Date(value).toISOString() : undefined;
}

export default function NewAdminEventPage() {
  const venuesQuery = useQuery({ queryKey: ["admin", "venues"], queryFn: getVenues });
  const [step, setStep] = useState(0);
  const [event, setEvent] = useState<AdminEvent | null>(null);
  const [createdSeats, setCreatedSeats] = useState<AdminSeat[]>([]);
  const [seatMapId, setSeatMapId] = useState<string | null>(null);
  const [createdTicketCount, setCreatedTicketCount] = useState(0);
  const [selectedSeatIds, setSelectedSeatIds] = useState<string[]>([]);
  const [assignedSeatIds, setAssignedSeatIds] = useState<string[]>([]);
  const [published, setPublished] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [venueId, setVenueId] = useState("");
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [startAt, setStartAt] = useState("");
  const [endAt, setEndAt] = useState("");
  const [salesStartAt, setSalesStartAt] = useState("");
  const [salesEndAt, setSalesEndAt] = useState("");
  const [bannerImageUrl, setBannerImageUrl] = useState("");
  const [category, setCategory] = useState<"GENERAL_ADMISSION" | "SEATED">("GENERAL_ADMISSION");
  const [section, setSection] = useState("A");
  const [rowStart, setRowStart] = useState("A");
  const [rowCount, setRowCount] = useState(3);
  const [seatsPerRow, setSeatsPerRow] = useState(8);
  const [ticketName, setTicketName] = useState("");
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");

  const createEventMutation = useMutation({ mutationFn: createAdminEvent });
  const publishMutation = useMutation({ mutationFn: publishAdminEvent });

  async function submitEvent(formData: FormData) {
    setError(null);
    const venue = String(formData.get("venueId") ?? "");
    const eventName = String(formData.get("name") ?? "").trim();
    const start = String(formData.get("startAt") ?? "");
    const end = String(formData.get("endAt") ?? "");
    if (!venue || !eventName || !start || !end || new Date(end) <= new Date(start)) {
      setError("Choose a venue, enter a name and valid times, and make sure the end time is after the start time.");
      return;
    }
    try {
      const result = await createEventMutation.mutateAsync({
        venueId: venue,
        name: eventName,
        description: String(formData.get("description") ?? "").trim() || undefined,
        startAt: toIso(start)!,
        endAt: toIso(end)!,
        salesStartAt: toIso(String(formData.get("salesStartAt") ?? "")),
        salesEndAt: toIso(String(formData.get("salesEndAt") ?? "")),
        bannerImageUrl: String(formData.get("bannerImageUrl") ?? "").trim() || undefined,
      });
      setEvent(result);
      setStep(1);
    } catch (cause) {
      setError(getErrorMessage(cause));
    }
  }

  async function prepareTicketStep(formData: FormData) {
    setError(null);
    if (!event) return;
    try {
      if (category === "SEATED" && createdSeats.length === 0) {
        const count = Number(formData.get("rowCount"));
        const perRow = Number(formData.get("seatsPerRow"));
        const firstRow = String(formData.get("rowStart") ?? "A").trim().toUpperCase();
        const sectionName = String(formData.get("section") ?? "A").trim();
        if (!/^[A-Z]$/.test(firstRow) || !sectionName || count < 1 || perRow < 1 || count * perRow > 2000) {
          setError("Enter a starting row from A–Z and a valid seat count (up to 2,000 seats).");
          return;
        }
        const seats = Array.from({ length: count }, (_, rowIndex) =>
          Array.from({ length: perRow }, (_, seatIndex) => ({
            section: sectionName,
            rowLabel: String.fromCharCode(firstRow.charCodeAt(0) + rowIndex),
            seatNumber: String(seatIndex + 1),
          })),
        ).flat();
        if (firstRow.charCodeAt(0) + count - 1 > 90) {
          setError("The row range cannot extend beyond row Z.");
          return;
        }
        const map = seatMapId
          ? { id: seatMapId }
          : await createSeatMap(event.id, `${event.name} — Seat map`);
        setSeatMapId(map.id);
        const result = await addSeats(event.id, seats);
        setCreatedSeats(result.seats);
      }
      setStep(2);
    } catch (cause) {
      setError(getErrorMessage(cause));
    }
  }

  async function submitTicket(formData: FormData) {
    setError(null);
    if (!event) return;
    const ticketLabel = String(formData.get("ticketName") ?? "").trim();
    const ticketPrice = Number(formData.get("price"));
    if (!ticketLabel || !Number.isFinite(ticketPrice) || ticketPrice < 0) {
      setError("Enter a ticket type name and a valid price of 0 or more.");
      return;
    }
    if (category === "GENERAL_ADMISSION") {
      const totalQuantity = Number(formData.get("quantity"));
      if (!Number.isInteger(totalQuantity) || totalQuantity < 1) {
        setError("Ticket quantity must be a positive whole number.");
        return;
      }
    }
    if (category === "SEATED" && selectedSeatIds.length === 0) {
      setError("Select at least one seat for this ticket type.");
      return;
    }
    try {
      await createTicketType(event.id, category === "SEATED"
        ? { category, name: ticketLabel, price: ticketPrice, seatIds: selectedSeatIds }
        : { category, name: ticketLabel, price: ticketPrice, totalQuantity: Number(formData.get("quantity")) });
      if (category === "SEATED") setAssignedSeatIds((ids) => [...ids, ...selectedSeatIds]);
      setCreatedTicketCount((count) => count + 1);
      setTicketName("");
      setPrice("");
      setQuantity("");
      setSelectedSeatIds([]);
    } catch (cause) {
      setError(getErrorMessage(cause));
    }
  }

  async function publishEvent() {
    if (!event) return;
    setError(null);
    try {
      const result = await publishMutation.mutateAsync(event.id);
      setEvent(result);
      setPublished(true);
    } catch (cause) {
      setError(getErrorMessage(cause));
    }
  }

  return (
    <div className="mx-auto w-full max-w-4xl space-y-6">
      <div>
        <Link href="/admin/events" className="inline-flex min-h-10 items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
          <ArrowLeft aria-hidden="true" className="size-4" /> Back to events
        </Link>
        <p className="mt-5 text-sm font-semibold text-primary">Admin · Events</p>
        <h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">Create event</h1>
      </div>

      <ol aria-label="Event creation steps" className="grid grid-cols-3 gap-2">
        {steps.map((label, index) => (
          <li key={label} aria-current={step === index ? "step" : undefined} className={`rounded-xl border px-3 py-3 text-center text-sm font-semibold ${step === index ? "border-primary bg-primary/5 text-primary" : step > index ? "border-primary/20 text-primary" : "border-border text-muted-foreground"}`}>
            <span className="mr-2 inline-flex size-6 items-center justify-center rounded-full bg-muted text-xs">{step > index ? <Check aria-hidden="true" className="size-4" /> : index + 1}</span>
            {label}
          </li>
        ))}
      </ol>

      {error ? <p role="alert" aria-live="polite" className="rounded-xl border border-destructive/20 bg-destructive/5 px-4 py-3 text-sm text-destructive">{error}</p> : null}

      <section className="space-y-6 rounded-2xl border border-border bg-card p-5 shadow-sm md:p-7" aria-labelledby="wizard-step-title">
        <div>
          <h2 id="wizard-step-title" className="font-heading text-xl font-semibold">{steps[step]}</h2>
          <p className="mt-1 text-sm text-muted-foreground">{step === 0 ? "Enter the event details. The event will be saved as a draft." : step === 1 ? "Choose general admission or create a seat map by row." : "Add one or more ticket types. For reserved seating, assign seats to each type."}</p>
        </div>

        {step === 0 ? (
          <form action={submitEvent} className="grid gap-5 md:grid-cols-2">
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="event-name">Event name</Label>
              <Input id="event-name" name="name" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} placeholder="For example: Summer music night" />
            </div>
            <div className="space-y-2 md:col-span-2">
              <Label htmlFor="event-venue">Venue</Label>
              <select id="event-venue" name="venueId" required value={venueId} onChange={(e) => setVenueId(e.target.value)} className="h-12 w-full rounded-lg border border-input bg-background px-3 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                <option value="">Select a venue</option>
                {(venuesQuery.data ?? []).map((venue) => <option key={venue.id} value={venue.id}>{venue.name} · {venue.city}</option>)}
              </select>
              {venuesQuery.isError ? <p role="alert" className="text-sm text-destructive">{getErrorMessage(venuesQuery.error, "Could not load venues.")}</p> : null}
              {venuesQuery.data?.length === 0 ? <p className="text-sm text-muted-foreground">No venues yet. <Link className="text-primary underline" href="/admin/venues">Create a venue</Link> before continuing.</p> : null}
            </div>
            <div className="space-y-2 md:col-span-2"><Label htmlFor="event-description">Description</Label><textarea id="event-description" name="description" maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} rows={4} className="w-full rounded-lg border border-input bg-background px-3 py-2 text-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" /></div>
            <div className="space-y-2"><Label htmlFor="event-start">Starts</Label><Input id="event-start" name="startAt" type="datetime-local" required value={startAt} onChange={(e) => setStartAt(e.target.value)} /></div>
            <div className="space-y-2"><Label htmlFor="event-end">Ends</Label><Input id="event-end" name="endAt" type="datetime-local" required value={endAt} onChange={(e) => setEndAt(e.target.value)} /></div>
            <div className="space-y-2"><Label htmlFor="sales-start">Sales start (optional)</Label><Input id="sales-start" name="salesStartAt" type="datetime-local" value={salesStartAt} onChange={(e) => setSalesStartAt(e.target.value)} /></div>
            <div className="space-y-2"><Label htmlFor="sales-end">Sales end (optional)</Label><Input id="sales-end" name="salesEndAt" type="datetime-local" value={salesEndAt} onChange={(e) => setSalesEndAt(e.target.value)} /></div>
            <div className="space-y-2 md:col-span-2"><Label htmlFor="event-banner">Banner image URL (optional)</Label><Input id="event-banner" name="bannerImageUrl" type="url" maxLength={500} value={bannerImageUrl} onChange={(e) => setBannerImageUrl(e.target.value)} placeholder="https://…" /></div>
            <div className="flex justify-end md:col-span-2"><Button type="submit" disabled={createEventMutation.isPending || venuesQuery.isPending || venuesQuery.data?.length === 0}>{createEventMutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : null}Save draft and continue<ArrowRight aria-hidden="true" /></Button></div>
          </form>
        ) : null}

        {step === 1 ? (
          <form action={prepareTicketStep} className="space-y-5">
            <fieldset className="grid gap-3 sm:grid-cols-2"><legend className="mb-2 text-sm font-semibold">Ticket type</legend>
              {([ ["GENERAL_ADMISSION", "General admission", "Fixed ticket quantity, no seat selection."], ["SEATED", "Reserved seating", "Create a seat map by section, row, and seat number."] ] as const).map(([value, label, hint]) => <label key={value} className={`cursor-pointer rounded-xl border p-4 ${category === value ? "border-primary bg-primary/5" : "border-border"}`}><input className="mr-2 accent-primary" type="radio" name="category-choice" checked={category === value} onChange={() => setCategory(value)} /><span className="font-semibold">{label}</span><span className="mt-1 block pl-6 text-sm text-muted-foreground">{hint}</span></label>)}
            </fieldset>
            {category === "SEATED" ? <div className="grid gap-4 rounded-xl border border-border p-4 sm:grid-cols-2">
              <p className="text-sm leading-6 text-muted-foreground sm:col-span-2">The generator creates consecutive lettered rows. The backend allows up to 2,000 seats per request.</p>
              <div className="space-y-2"><Label htmlFor="seat-section">Section</Label><Input id="seat-section" name="section" required maxLength={50} value={section} onChange={(e) => setSection(e.target.value)} /></div>
              <div className="space-y-2"><Label htmlFor="seat-row-start">First row (A–Z)</Label><Input id="seat-row-start" name="rowStart" required maxLength={1} value={rowStart} onChange={(e) => setRowStart(e.target.value)} /></div>
              <div className="space-y-2"><Label htmlFor="seat-row-count">Number of rows</Label><Input id="seat-row-count" name="rowCount" type="number" min={1} max={26} required value={rowCount} onChange={(e) => setRowCount(Number(e.target.value))} /></div>
              <div className="space-y-2"><Label htmlFor="seat-count">Seats per row</Label><Input id="seat-count" name="seatsPerRow" type="number" min={1} max={2000} required value={seatsPerRow} onChange={(e) => setSeatsPerRow(Number(e.target.value))} /></div>
              <p className="text-sm font-medium sm:col-span-2">This will create {rowCount * seatsPerRow} seats: rows {rowStart || "A"}–{String.fromCharCode((rowStart || "A").toUpperCase().charCodeAt(0) + Math.max(0, rowCount - 1))}, {seatsPerRow} seats per row.</p>
            </div> : <p className="rounded-xl bg-muted/60 p-4 text-sm text-muted-foreground">Skip the seat map; you can set the capacity in the ticket type.</p>}
            <div className="flex flex-col-reverse justify-between gap-3 sm:flex-row"><Button type="button" variant="outline" onClick={() => setStep(0)}>Back</Button><Button type="submit">Continue<ArrowRight aria-hidden="true" /></Button></div>
          </form>
        ) : null}

        {step === 2 ? (
          <div className="space-y-5">
            <form action={submitTicket} className="grid gap-5 md:grid-cols-2">
              {category === "SEATED" ? <fieldset className="space-y-2 rounded-lg bg-muted/50 p-3 md:col-span-2"><legend className="px-1 text-sm font-semibold">Assign seats to this ticket type</legend><p className="mb-2 text-sm text-muted-foreground">Selected {selectedSeatIds.length} / {createdSeats.length - assignedSeatIds.length} available seats.</p><div className="grid max-h-56 grid-cols-2 gap-2 overflow-y-auto sm:grid-cols-4">{createdSeats.filter((seat) => !assignedSeatIds.includes(seat.id)).map((seat) => <label key={seat.id} className="flex min-h-10 items-center gap-2 rounded-md border border-border bg-background px-2 text-sm"><input type="checkbox" checked={selectedSeatIds.includes(seat.id)} onChange={(e) => setSelectedSeatIds((ids) => e.target.checked ? [...ids, seat.id] : ids.filter((id) => id !== seat.id))} className="accent-primary" />{seat.displayLabel}</label>)}</div>{createdSeats.length === assignedSeatIds.length ? <p className="text-sm text-muted-foreground">All seats have been assigned.</p> : null}</fieldset> : null}
              <div className="space-y-2 md:col-span-2"><Label htmlFor="ticket-name">Ticket type name</Label><Input id="ticket-name" name="ticketName" required maxLength={200} value={ticketName} onChange={(e) => setTicketName(e.target.value)} placeholder="For example: Standard admission" /></div>
              <div className="space-y-2"><Label htmlFor="ticket-price">Price (USD)</Label><Input id="ticket-price" name="price" type="number" min="0" step="0.01" required value={price} onChange={(e) => setPrice(e.target.value)} /></div>
              {category === "GENERAL_ADMISSION" ? <div className="space-y-2"><Label htmlFor="ticket-quantity">Quantity</Label><Input id="ticket-quantity" name="quantity" type="number" min="1" step="1" required value={quantity} onChange={(e) => setQuantity(e.target.value)} /></div> : null}
              <div className="flex flex-col-reverse justify-between gap-3 md:col-span-2 sm:flex-row"><Button type="button" variant="outline" onClick={() => setStep(1)}>Back</Button><Button type="submit">Create ticket type<ArrowRight aria-hidden="true" /></Button></div>
            </form>
            {createdTicketCount > 0 ? <div className="space-y-3 rounded-xl border border-primary/20 bg-primary/5 p-4"><p className="font-semibold text-primary">{createdTicketCount} ticket types created. Add more above or publish the event.</p><div className="flex justify-end"><Button type="button" onClick={publishEvent} disabled={publishMutation.isPending || published}>{publishMutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : published ? <Check aria-hidden="true" /> : null}{published ? "Published" : "Publish event"}</Button></div></div> : null}
            {published ? <p role="status" aria-live="polite" className="rounded-xl bg-primary/5 p-4 text-sm font-medium">Event published successfully. <Link href="/admin/events" className="underline">Back to event list</Link></p> : null}
            <p className="text-xs leading-5 text-muted-foreground">If publishing fails, the API returns the missing requirements. Previously saved steps remain on the backend.</p>
          </div>
        ) : null}
      </section>
    </div>
  );
}
