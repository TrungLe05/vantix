"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  CalendarDays,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  EllipsisVertical,
  Eye,
  Plus,
  Pencil,
  Search,
  WifiOff,
} from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { getEvents } from "@/lib/api/events";
import {
  adminEventMocks,
  type AdminEventStatus,
} from "@/lib/mocks/admin-events";

type DateFilter = "any" | "this-week" | "this-month" | `month:${string}`;

const pageSize = 5;
const statusTabs: { value: "ALL" | AdminEventStatus; label: string }[] = [
  { value: "ALL", label: "All" },
  { value: "DRAFT", label: "Draft" },
  { value: "PUBLISHED", label: "Published" },
  { value: "CANCELLED", label: "Cancelled" },
];

const statusLabel: Record<AdminEventStatus, string> = {
  DRAFT: "Draft",
  PUBLISHED: "Published",
  CANCELLED: "Cancelled",
};
const statusClass: Record<AdminEventStatus, string> = {
  DRAFT: "border-border bg-muted text-muted-foreground",
  PUBLISHED: "border-primary/20 bg-primary/5 text-primary",
  CANCELLED: "border-destructive/20 bg-destructive/5 text-destructive",
};

type DashboardEvent = {
  id: string;
  name: string;
  venueName: string;
  venueArea: string;
  startAt: string;
  ticketSummary: string;
  status: AdminEventStatus;
  salesWindow: string;
  isMock: boolean;
};

function formatEventDate(value: string) {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}

function formatMonth(value: string) {
  const [year, month] = value.split("-").map(Number);
  return new Intl.DateTimeFormat("en-US", {
    month: "long",
    year: "numeric",
  }).format(new Date(year, month - 1, 1));
}

function matchesDateFilter(value: string, dateFilter: DateFilter) {
  const date = new Date(value);
  if (dateFilter === "any") return true;
  if (dateFilter.startsWith("month:")) {
    return value.slice(0, 7) === dateFilter.slice("month:".length);
  }

  const today = new Date();
  const start = new Date(
    today.getFullYear(),
    today.getMonth(),
    today.getDate(),
  );
  if (dateFilter === "this-week") {
    const daysSinceMonday = (start.getDay() + 6) % 7;
    start.setDate(start.getDate() - daysSinceMonday);
    const end = new Date(start);
    end.setDate(end.getDate() + 6);
    end.setHours(23, 59, 59, 999);
    return date >= start && date <= end;
  }

  return (
    date.getFullYear() === today.getFullYear() &&
    date.getMonth() === today.getMonth()
  );
}

export default function AdminEventsPage() {
  const router = useRouter();
  const [status, setStatus] =
    useState<(typeof statusTabs)[number]["value"]>("ALL");
  const [venue, setVenue] = useState("ALL");
  const [date, setDate] = useState<DateFilter>("any");
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const publishedQuery = useQuery({
    queryKey: ["admin", "published-events"],
    queryFn: () => getEvents(0, 100),
  });

  const events = useMemo<DashboardEvent[]>(() => {
    const published = (publishedQuery.data?.content ?? []).map((event) => ({
      id: event.id,
      name: event.name,
      venueName: event.venue.name,
      venueArea: event.venue.city,
      startAt: event.startAt,
      ticketSummary:
        event.minPrice === null
          ? "Price unavailable"
          : `From $${event.minPrice.toFixed(2)}`,
      status: "PUBLISHED" as const,
      salesWindow: "Not provided",
      isMock: false,
    }));
    const fixtures = adminEventMocks
      .filter((event) => event.status !== "PUBLISHED")
      .map((event) => ({
        id: event.id,
        name: event.name,
        venueName: event.venueName,
        venueArea: event.venueArea,
        startAt: event.startAt,
        ticketSummary: event.ticketTypeCount
          ? `${event.ticketTypeCount} sample ticket types`
          : "None",
        status: event.status,
        salesWindow: event.salesWindow,
        isMock: true,
      }));
    return [...published, ...fixtures];
  }, [publishedQuery.data]);

  const filteredEvents = useMemo(() => {
    const query = search.trim().toLocaleLowerCase("vi");
    return events.filter((event) => {
      const matchesStatus = status === "ALL" || event.status === status;
      const matchesVenue = venue === "ALL" || event.venueName === venue;
      const matchesDate = matchesDateFilter(event.startAt, date);
      const matchesSearch =
        !query ||
        [event.name, event.venueName, event.venueArea].some((value) =>
          value.toLocaleLowerCase("vi").includes(query),
        );
      return matchesStatus && matchesVenue && matchesDate && matchesSearch;
    });
  }, [date, events, search, status, venue]);

  const monthOptions = [
    ...new Set(events.map((event) => event.startAt.slice(0, 7))),
  ]
    .filter(Boolean)
    .sort((first, second) => second.localeCompare(first));

  const dateLabel =
    date === "any"
      ? "Any date"
      : date === "this-week"
        ? "This week"
        : date === "this-month"
          ? "This month"
          : formatMonth(date.slice("month:".length));

  const pageCount = Math.ceil(filteredEvents.length / pageSize);
  const currentPage = Math.min(page, Math.max(pageCount - 1, 0));
  const pageEvents = filteredEvents.slice(
    currentPage * pageSize,
    currentPage * pageSize + pageSize,
  );

  function updateFilter<T>(setter: (value: T) => void, value: T) {
    setter(value);
    setPage(0);
  }

  return (
    <div className="mx-auto w-full max-w-7xl space-y-6">
      <div className="flex flex-col justify-between gap-4 sm:flex-row sm:items-end">
        <div>
          <h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">
            Events
          </h1>
          <p className="mt-2 text-sm text-muted-foreground">
            Manage event schedules, ticket allocations, and publishing states.
          </p>
        </div>
        <Button asChild>
          <Link href="/admin/events/new">
            <Plus aria-hidden="true" />
            Create event
          </Link>
        </Button>
      </div>

      {publishedQuery.isError ? (
        <div
          role="alert"
          className="flex flex-col gap-3 rounded-2xl border border-destructive/20 bg-destructive/5 p-4 text-sm sm:flex-row sm:items-center sm:justify-between"
        >
          <div className="flex items-start gap-3 text-destructive">
            <WifiOff aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
            <div>
              <p className="font-semibold">Could not load live events</p>
              <p className="mt-1 text-destructive/80">
                {publishedQuery.error instanceof Error
                  ? publishedQuery.error.message
                  : "Check your connection and try again."}
              </p>
            </div>
          </div>
          <Button
            variant="outline"
            size="sm"
            onClick={() => publishedQuery.refetch()}
          >
            Retry
          </Button>
        </div>
      ) : null}

      <section aria-label="Event filters" className="space-y-4">
        <div
          role="tablist"
          aria-label="Filter events by status"
          className="flex gap-6 border-b border-border px-1"
        >
          {statusTabs.map((tab) => {
            const count =
              tab.value === "ALL"
                ? events.length
                : events.filter((event) => event.status === tab.value).length;
            const selected = status === tab.value;
            return (
              <button
                key={tab.value}
                type="button"
                role="tab"
                aria-selected={selected}
                onClick={() => updateFilter(setStatus, tab.value)}
                className={`-mb-px inline-flex min-h-12 shrink-0 items-center gap-2 border-b-2 px-1 text-sm font-semibold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${selected ? "border-primary text-primary" : "border-transparent text-muted-foreground hover:text-foreground"}`}
              >
                {tab.label}
                <span className="rounded-full bg-muted px-2 py-0.5 text-xs tabular-nums text-muted-foreground">
                  {count}
                </span>
              </button>
            );
          })}
        </div>

        <div className="grid gap-3 rounded-2xl border border-border bg-card p-3 shadow-sm sm:grid-cols-2 lg:grid-cols-[minmax(240px,1fr)_minmax(180px,220px)_minmax(180px,220px)]">
          <label className="relative">
            <span className="sr-only">Search events or venues</span>
            <Search
              aria-hidden="true"
              className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground"
            />
            <Input
              type="search"
              name="event-search"
              autoComplete="off"
              placeholder="Search events or venues…"
              value={search}
              onChange={(event) => updateFilter(setSearch, event.target.value)}
              className="h-11 border-0 bg-muted/50 pl-9 shadow-none"
            />
          </label>
          <DropdownMenu>
            <DropdownMenuTrigger aria-label="Filter by venue" className="inline-flex h-11 items-center justify-between gap-3 rounded-xl border border-input bg-background px-3 text-sm font-medium hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <span className="truncate">{venue === "ALL" ? "All venues" : venue}</span><ChevronDown aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" className="w-64 rounded-xl p-1.5 shadow-lg">
              {["ALL", ...new Set(events.map((event) => event.venueName))].map((name) => <DropdownMenuItem key={name} onClick={() => updateFilter(setVenue, name)} className={`min-h-10 rounded-lg ${venue === name ? "bg-accent font-semibold text-accent-foreground" : ""}`}>{name === "ALL" ? "All venues" : name}</DropdownMenuItem>)}
            </DropdownMenuContent>
          </DropdownMenu>
          <DropdownMenu>
            <DropdownMenuTrigger className="inline-flex h-11 items-center justify-between gap-3 rounded-xl border border-input bg-background px-3 text-sm font-medium hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <span className="inline-flex items-center gap-2">
                <CalendarDays
                  aria-hidden="true"
                  className="size-4 text-muted-foreground"
                />
                {dateLabel}
              </span>
              <ChevronDown
                aria-hidden="true"
                className="size-4 text-muted-foreground"
              />
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56">
              {(
                [
                  ["any", "Any date"],
                  ["this-week", "This week"],
                  ["this-month", "This month"],
                ] as const
              ).map(([value, label]) => (
                <DropdownMenuItem
                  key={value}
                  onClick={() => updateFilter(setDate, value)}
                >
                  {label}
                </DropdownMenuItem>
              ))}
              {monthOptions.length ? (
                <div className="my-1 border-t border-border pt-1">
                  {monthOptions.map((month) => (
                    <DropdownMenuItem
                      key={month}
                      onClick={() => updateFilter(setDate, `month:${month}`)}
                    >
                      {formatMonth(month)}
                    </DropdownMenuItem>
                  ))}
                </div>
              ) : null}
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </section>

      <section
        className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm"
        aria-labelledby="admin-events-title"
      >
        <div className="flex flex-col gap-1 border-b border-border px-4 py-4 sm:flex-row sm:items-center sm:justify-between md:px-5">
          <div>
            <h2
              id="admin-events-title"
              className="font-heading text-lg font-semibold"
            >
              {status === "ALL"
                ? "All events"
                : `${statusLabel[status]} events`}
            </h2>
            <p className="mt-1 text-sm text-muted-foreground">
              {publishedQuery.isPending
                ? "Loading…"
                : `${filteredEvents.length} events found`}
            </p>
          </div>
          <p className="text-xs text-muted-foreground">
            Event details and ticket pricing
          </p>
        </div>

        {publishedQuery.isPending ? (
          <div role="status" aria-live="polite" className="space-y-3 p-5">
            <span className="sr-only">Loading events…</span>
            {[0, 1, 2].map((i) => (
              <div key={i} className="h-12 animate-pulse rounded-lg bg-muted" />
            ))}
          </div>
        ) : pageEvents.length === 0 ? (
          <div className="flex flex-col items-center px-5 py-14 text-center">
            <span className="flex size-12 items-center justify-center rounded-full bg-accent text-primary">
              <CalendarDays aria-hidden="true" className="size-5" />
            </span>
            <h3 className="mt-4 font-heading text-lg font-semibold">
              No events found
            </h3>
            <p className="mt-2 max-w-sm text-sm leading-6 text-muted-foreground">
              Try changing your search or filters to find an event.
            </p>
            <Button
              variant="outline"
              className="mt-5"
              onClick={() => {
                setStatus("ALL");
                setVenue("ALL");
                setDate("any");
                setSearch("");
                setPage(0);
              }}
            >
              Reset filters
            </Button>
          </div>
        ) : (
          <div className="max-h-[600px] overflow-auto">
            <table className="w-full min-w-[920px] border-collapse text-left text-sm">
              <thead className="sticky top-0 z-10 bg-muted/90 text-xs uppercase tracking-wide text-muted-foreground backdrop-blur">
                <tr>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Event
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Date &amp; time
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Price / ticket
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Status
                  </th>
                  <th scope="col" className="px-5 py-3 font-semibold">
                    Sales window
                  </th>
                  <th
                    scope="col"
                    className="px-5 py-3 text-right font-semibold"
                  >
                    Actions
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {pageEvents.map((event) => (
                  <tr key={event.id} className="align-middle hover:bg-muted/30">
                    <th scope="row" className="max-w-72 px-5 py-4">
                      <span
                        className="block truncate font-semibold"
                        title={event.name}
                      >
                        {event.name}
                      </span>
                      <span
                        className="mt-1 block truncate text-xs font-normal text-muted-foreground"
                        title={`${event.venueName} · ${event.venueArea}`}
                      >
                        {event.venueName} · {event.venueArea}
                      </span>
                    </th>
                    <td className="whitespace-nowrap px-5 py-4 text-muted-foreground">
                      <time dateTime={event.startAt}>
                        {formatEventDate(event.startAt)}
                      </time>
                    </td>
                    <td className="px-5 py-4 text-muted-foreground">
                      {event.ticketSummary}
                    </td>
                    <td className="px-5 py-4">
                      <span
                        className={`inline-flex rounded-full border px-2.5 py-1 text-xs font-semibold ${statusClass[event.status]}`}
                      >
                        {statusLabel[event.status]}
                      </span>
                    </td>
                    <td className="px-5 py-4 text-xs text-muted-foreground">
                      {event.salesWindow}
                    </td>
                    <td className="px-5 py-4 text-right">
                      <DropdownMenu>
                        <DropdownMenuTrigger className="inline-flex size-9 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                          <EllipsisVertical aria-hidden="true" className="size-5" />
                          <span className="sr-only">
                            Actions for {event.name}
                          </span>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-40">
                          <DropdownMenuItem
                            disabled={event.isMock}
                            onClick={() =>
                              router.push(`/admin/events/${event.id}`)
                            }
                          >
                            <Eye aria-hidden="true" />
                            View
                          </DropdownMenuItem>
                          <DropdownMenuItem
                            disabled={event.isMock}
                            onClick={() =>
                              router.push(`/admin/events/${event.id}/edit`)
                            }
                          >
                            <Pencil aria-hidden="true" />
                            Edit
                          </DropdownMenuItem>
                        </DropdownMenuContent>
                      </DropdownMenu>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {filteredEvents.length > pageSize ? (
          <div className="flex flex-col gap-3 border-t border-border px-4 py-3 sm:flex-row sm:items-center sm:justify-between md:px-5">
            <p className="text-xs text-muted-foreground">
              Showing {currentPage * pageSize + 1}–
              {Math.min((currentPage + 1) * pageSize, filteredEvents.length)} of{" "}
              {filteredEvents.length}
            </p>
            <div className="flex items-center justify-end gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={currentPage === 0}
                onClick={() => setPage(currentPage - 1)}
              >
                <ChevronLeft aria-hidden="true" />
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={currentPage >= pageCount - 1}
                onClick={() => setPage(currentPage + 1)}
              >
                Next
                <ChevronRight aria-hidden="true" />
              </Button>
            </div>
          </div>
        ) : null}
      </section>
    </div>
  );
}
