"use client";

import Link from "next/link";
import Image from "next/image";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  CalendarDays,
  ChevronDown,
  CircleAlert,
  MapPin,
  Music2,
  RefreshCw,
  RefreshCwIcon,
  Search,
} from "lucide-react";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import {
  EventDate,
  EventFooter,
  EventHeader,
} from "@/components/events/EventHeader";
import { getAllEvents } from "@/lib/api/events";
import { getErrorMessage } from "@/lib/api/client";

const currency = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
  maximumFractionDigits: 2,
});

const cities = [
  { value: "Hồ Chí Minh", label: "Ho Chi Minh City" },
  { value: "Hà Nội", label: "Hanoi" },
  { value: "Đà Nẵng", label: "Da Nang" },
  { value: "Cần Thơ", label: "Can Tho" },
  { value: "Hải Phòng", label: "Hai Phong" },
  { value: "Huế", label: "Hue" },
  { value: "Nha Trang", label: "Nha Trang" },
  { value: "Đà Lạt", label: "Da Lat" },
];

function normalizeSearch(value: string) {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .replace(/[đĐ]/g, (letter) => (letter === "đ" ? "d" : "D"))
    .toLocaleLowerCase("vi")
    .trim();
}

function localDateString(date: Date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

export default function DiscoveryPage() {
  const [search, setSearch] = useState("");
  const [city, setCity] = useState("Hồ Chí Minh");
  const [dateFilter, setDateFilter] = useState("any");
  const [sortOrder, setSortOrder] = useState("soonest");
  const [visibleCount, setVisibleCount] = useState(12);
  const today = localDateString(new Date());
  const {
    data: allEvents = [],
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ["events", "discovery"],
    queryFn: getAllEvents,
  });

  const events = useMemo(() => {
    const searchTerms = normalizeSearch(search).split(/\s+/).filter(Boolean);
    const selectedCity = normalizeSearch(city);
    const todayDate = new Date(`${today}T00:00:00`);
    const rangeEnd = new Date(todayDate);
    if (dateFilter === "this-week") {
      const daysUntilSunday = (7 - todayDate.getDay()) % 7;
      rangeEnd.setDate(rangeEnd.getDate() + daysUntilSunday);
    } else if (dateFilter === "this-month") {
      rangeEnd.setMonth(rangeEnd.getMonth() + 1, 0);
    }
    const rangeEndDate =
      dateFilter === "any" ? null : localDateString(rangeEnd);
    const filteredEvents = allEvents.filter((event) => {
      // PR07 has no separate artist field, so artist names only match when included in the event name.
      const searchableText = normalizeSearch(
        `${event.name} ${event.venue.name} ${event.venue.city}`,
      );
      const eventCity = normalizeSearch(event.venue.city);
      const eventDate = localDateString(new Date(event.startAt));
      return (
        searchTerms.every((term) => searchableText.includes(term)) &&
        (!selectedCity || eventCity.includes(selectedCity)) &&
        eventDate >= today &&
        (!rangeEndDate || eventDate <= rangeEndDate)
      );
    });
    return [...filteredEvents].sort((first, second) => {
      const dateDifference =
        new Date(first.startAt).getTime() - new Date(second.startAt).getTime();
      return sortOrder === "newest" ? -dateDifference : dateDifference;
    });
  }, [allEvents, city, dateFilter, search, sortOrder, today]);

  const visibleEvents = events.slice(0, visibleCount);

  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader showSignUp />
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 lg:px-8">
        <section className="mb-8">
          <h1 className="font-heading text-3xl font-semibold tracking-tight text-balance">
            Discover events
          </h1>
          <p className="mt-1 text-sm text-muted-foreground sm:text-base">
            Find concerts, conferences, and sports near you.
          </p>
        </section>

        <section
          aria-label="Search and filter events"
          className="mb-8 flex flex-col gap-3 border-b border-border pb-6 md:flex-row md:items-center md:justify-between"
        >
          <div className="flex w-full max-w-4xl flex-col gap-3 sm:flex-row sm:items-center">
            <label className="relative w-full md:w-[360px]">
              <Search
                aria-hidden="true"
                className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
              />
              <span className="sr-only">Search events</span>
              <input
                type="search"
                autoComplete="off"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="min-h-10 w-full rounded-lg border border-input bg-background py-2 pl-10 pr-4 text-sm outline-none transition focus:border-primary focus:ring-2 focus:ring-primary/20"
                placeholder="Event, artist, or venue name"
              />
            </label>
            <DropdownMenu>
              <DropdownMenuTrigger aria-label="Filter by city" className="inline-flex min-h-10 w-full items-center justify-between gap-3 rounded-xl border border-input bg-card px-3 text-sm font-medium shadow-sm transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:w-48"><span className="inline-flex min-w-0 items-center gap-2"><MapPin aria-hidden="true" className="size-4 shrink-0 text-primary" /><span className="truncate">{cities.find((item) => item.value === city)?.label ?? "All cities"}</span></span><ChevronDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" /></DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="w-56 rounded-xl p-1.5 shadow-lg">
                {[{ value: "", label: "All cities" }, ...cities].map((item) => <DropdownMenuItem key={item.value || "all"} onClick={() => { setCity(item.value); setVisibleCount(12); }} className={`min-h-10 rounded-lg ${city === item.value ? "bg-accent font-semibold text-accent-foreground" : ""}`}>{item.label}</DropdownMenuItem>)}
              </DropdownMenuContent>
            </DropdownMenu>
            <DropdownMenu>
              <DropdownMenuTrigger aria-label="Filter by date" className="inline-flex min-h-10 w-full items-center justify-between gap-3 rounded-xl border border-input bg-card px-3 text-sm font-medium shadow-sm transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:w-44"><span className="inline-flex items-center gap-2"><CalendarDays aria-hidden="true" className="size-4 text-primary" />{dateFilter === "any" ? "Any date" : dateFilter === "this-week" ? "This week" : "This month"}</span><ChevronDown aria-hidden="true" className="size-4 text-muted-foreground" /></DropdownMenuTrigger>
              <DropdownMenuContent align="start" className="w-44 rounded-xl p-1.5 shadow-lg">
                {([["any", "Any date"], ["this-week", "This week"], ["this-month", "This month"]] as const).map(([value, label]) => <DropdownMenuItem key={value} onClick={() => { setDateFilter(value); setVisibleCount(12); }} className={`min-h-10 rounded-lg ${dateFilter === value ? "bg-accent font-semibold text-accent-foreground" : ""}`}>{label}</DropdownMenuItem>)}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
          <DropdownMenu>
            <DropdownMenuTrigger aria-label="Sort events" className="inline-flex min-h-10 w-full items-center justify-between gap-3 rounded-xl border border-input bg-card px-3 text-sm font-medium shadow-sm transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring sm:ml-auto sm:w-40">
              {sortOrder === "soonest" ? "Soonest" : "Newest"}<ChevronDown aria-hidden="true" className="size-4 text-muted-foreground" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-40 rounded-xl p-1.5 shadow-lg">
              {[ ["soonest", "Soonest"], ["newest", "Newest"] ].map(([value, label]) => <DropdownMenuItem key={value} onClick={() => setSortOrder(value)} className={`min-h-10 rounded-lg ${sortOrder === value ? "bg-accent font-semibold text-accent-foreground" : ""}`}>{label}</DropdownMenuItem>)}
            </DropdownMenuContent>
          </DropdownMenu>
        </section>
        <section id="events" className="scroll-mt-6">
          {isPending ? (
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 6 }, (_, index) => (
                <div
                  key={index}
                  className="animate-pulse overflow-hidden rounded-xl border border-border bg-card"
                >
                  <div className="aspect-video bg-muted" />
                  <div className="space-y-3 p-5">
                    <div className="h-3 w-28 rounded bg-muted" />
                    <div className="h-5 w-3/4 rounded bg-muted" />
                    <div className="h-3 w-1/2 rounded bg-muted" />
                    <div className="mt-4 border-t border-border/60 pt-4">
                      <div className="h-4 w-20 rounded bg-muted" />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : null}
          {isError ? (
            <div
              role="alert"
              className="mx-auto flex max-w-2xl flex-col items-center rounded-xl border border-destructive/20 bg-destructive/5 p-6 text-center"
            >
              <span className="mb-3 flex size-10 items-center justify-center rounded-full bg-destructive/10 text-destructive">
                <CircleAlert aria-hidden="true" className="size-5" />
              </span>
              <p className="font-semibold">Could not load events</p>
              <p className="mt-1 text-sm text-muted-foreground">
                {getErrorMessage(error)}
              </p>
              <button
                type="button"
                onClick={() => void refetch()}
                className="mt-4 inline-flex min-h-10 items-center gap-2 rounded-lg bg-destructive px-4 text-sm font-semibold text-destructive-foreground hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <RefreshCw aria-hidden="true" className="size-4" />
                Try again
              </button>
            </div>
          ) : null}
          {!isPending && !isError && events.length === 0 ? (
            <div className="mx-auto flex max-w-sm flex-col items-center py-16 text-center">
              <span className="mb-4 flex size-12 items-center justify-center rounded-full bg-muted">
                <CalendarDays
                  aria-hidden="true"
                  className="size-6 text-muted-foreground"
                />
              </span>
              <h3 className="font-heading text-lg font-semibold">
                No events found
              </h3>
              <p className="mt-1 text-sm text-muted-foreground">
                Try changing your filters or search keywords to find other
                gatherings.
              </p>
              <button
                type="button"
                onClick={() => {
                  setSearch("");
                  setCity("");
                  setDateFilter("any");
                  setVisibleCount(12);
                }}
                className="mt-5 min-h-10 px-5 rounded-lg text-sm font-medium border border-border bg-background text-foreground hover:bg-muted transition cursor-pointer flex items-center gap-2"
              >
                <RefreshCwIcon size={14} strokeWidth={1.8} />
                Clear filters
              </button>
            </div>
          ) : null}
          {!isPending && !isError && events.length > 0 ? (
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {visibleEvents.map((event) => (
                <Link
                  key={event.id}
                  href={`/events/${event.id}`}
                  className="group flex flex-col overflow-hidden rounded-xl border border-border bg-card transition-all duration-200 hover:-translate-y-1 hover:border-primary/40 hover:shadow-lg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <div className="relative aspect-video w-full overflow-hidden border-b border-border bg-secondary">
                    {event.bannerImageUrl ? (
                      <Image
                        src={event.bannerImageUrl}
                        alt=""
                        fill
                        unoptimized
                        sizes="(max-width: 640px) 100vw, (max-width: 1024px) 50vw, 33vw"
                        className="object-cover transition-transform duration-300 group-hover:scale-105"
                      />
                    ) : (
                      <div className="flex size-full flex-col items-center justify-center gap-2 text-muted-foreground/70 transition-transform duration-300 group-hover:scale-105">
                        <span className="flex size-10 items-center justify-center rounded-full border border-border bg-background shadow-sm">
                          <Music2
                            aria-hidden="true"
                            className="size-5 text-primary"
                          />
                        </span>
                        <span className="text-[10px] font-semibold uppercase tracking-wider text-secondary-foreground">
                          Event
                        </span>
                      </div>
                    )}
                  </div>
                  <div className="flex flex-1 flex-col p-5">
                    <p className="text-xs font-semibold uppercase tracking-wider text-primary">
                      <EventDate value={event.startAt} />
                    </p>
                    <h3 className="mt-1.5 line-clamp-2 flex-1 font-heading text-base font-semibold leading-snug">
                      {event.name}
                    </h3>
                    <p className="mt-2 flex items-center gap-1.5 text-xs text-muted-foreground">
                      <MapPin aria-hidden="true" className="size-4 shrink-0" />
                      <span className="truncate">
                        {event.venue.name} · {event.venue.city}
                      </span>
                    </p>
                    <div className="mt-4 flex items-center justify-between border-t border-border/60 pt-3">
                      <span className="text-sm font-semibold">
                        {event.minPrice === null
                          ? "Price to be announced"
                          : event.minPrice === 0
                            ? "Free"
                            : `From ${currency.format(event.minPrice)}`}
                      </span>
                    </div>
                  </div>
                </Link>
              ))}
            </div>
          ) : null}
          {events.length > visibleCount ? (
            <div className="mt-12 flex flex-col items-center justify-center gap-2">
              <button
                type="button"
                onClick={() => setVisibleCount((count) => count + 12)}
                className="min-h-10 rounded-lg border border-border bg-background px-5 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Load more
              </button>
              <span className="text-xs text-muted-foreground">
                Showing {Math.min(visibleCount, events.length)} of{" "}
                {events.length} events
              </span>
            </div>
          ) : null}
        </section>
      </main>
      <EventFooter />
    </div>
  );
}
