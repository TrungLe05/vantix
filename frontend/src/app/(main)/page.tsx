"use client";

import Link from "next/link";
import Image from "next/image";
import { useMemo, useState } from "react";
import { format } from "date-fns";
import { useInfiniteQuery } from "@tanstack/react-query";
import { ArrowRight, CalendarDays, MapPin, Music2, Search } from "lucide-react";
import {
  EventDate,
  EventFooter,
  EventHeader,
} from "@/components/events/EventHeader";
import { getEvents } from "@/lib/api/events";
import { getErrorMessage } from "@/lib/api/client";

const currency = new Intl.NumberFormat("vi-VN", {
  style: "currency",
  currency: "VND",
  maximumFractionDigits: 0,
});

export default function DiscoveryPage() {
  const [search, setSearch] = useState("");
  const [city, setCity] = useState("");
  const [dateFilter, setDateFilter] = useState("");
  const {
    data,
    isPending,
    isError,
    error,
    fetchNextPage,
    hasNextPage,
    isFetchingNextPage,
  } = useInfiniteQuery({
    queryKey: ["events"],
    queryFn: ({ pageParam }) => getEvents(pageParam, 12),
    initialPageParam: 0,
    getNextPageParam: (lastPage) =>
      lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined,
  });

  const events = useMemo(() => {
    // PR07 currently pages without city/date filters; these filters apply to the pages loaded so far.
    const term = search.trim().toLocaleLowerCase("vi");
    const selectedCity = city.trim().toLocaleLowerCase("vi");
    return (data?.pages.flatMap((page) => page.content) ?? []).filter(
      (event) =>
        (!term ||
          event.name.toLocaleLowerCase("vi").includes(term) ||
          event.venue.name.toLocaleLowerCase("vi").includes(term)) &&
        (!selectedCity ||
          event.venue.city.toLocaleLowerCase("vi").includes(selectedCity)) &&
        (!dateFilter ||
          format(new Date(event.startAt), "yyyy-MM-dd") === dateFilter),
    );
  }, [city, data, dateFilter, search]);

  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader />
      <main className="mx-auto w-full max-w-7xl px-5 pb-16 pt-12 md:px-8 md:pt-16">
        <section className="max-w-3xl">
          <p className="mb-3 text-sm font-semibold tracking-[0.16em] text-primary">
            KHÁM PHÁ ĐỊA PHƯƠNG
          </p>
          <h1 className="font-heading text-4xl font-bold tracking-tight text-balance md:text-5xl">
            Lên kế hoạch cho những khoảnh khắc đáng nhớ.
          </h1>
          <p className="mt-5 max-w-2xl text-base leading-7 text-muted-foreground md:text-lg">
            Tìm những buổi gặp gỡ thú vị, sự kiện trực tiếp và những người bạn
            mới quanh bạn.
          </p>
        </section>

        <section
          aria-label="Tìm sự kiện"
          className="mt-10 grid gap-3 rounded-xl border border-border bg-card p-3 shadow-sm md:mt-12 md:grid-cols-[1.5fr_1fr_1fr_auto] md:p-4"
        >
          <label className="flex min-h-11 items-center gap-3 rounded-lg border border-input px-4 focus-within:ring-2 focus-within:ring-ring/40">
            <Search
              aria-hidden="true"
              className="size-4 shrink-0 text-muted-foreground"
            />
            <span className="sr-only">Tìm sự kiện</span>
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              className="w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              placeholder="Tên sự kiện, nghệ sĩ hoặc địa điểm"
            />
          </label>
          <label className="flex min-h-11 items-center gap-3 rounded-lg bg-muted px-4">
            <MapPin
              aria-hidden="true"
              className="size-4 shrink-0 text-primary"
            />
            <span className="sr-only">Thành phố</span>
            <input
              value={city}
              onChange={(event) => setCity(event.target.value)}
              className="w-full bg-transparent text-sm outline-none placeholder:text-muted-foreground"
              placeholder="Mọi thành phố"
            />
          </label>
          <label className="flex min-h-11 items-center gap-3 rounded-lg bg-muted px-4 text-sm text-muted-foreground">
            <CalendarDays
              aria-hidden="true"
              className="size-4 shrink-0 text-primary"
            />
            <span className="sr-only">Ngày diễn ra</span>
            <input
              type="date"
              value={dateFilter}
              onChange={(event) => setDateFilter(event.target.value)}
              className="w-full min-w-0 bg-transparent text-sm text-foreground outline-none"
              aria-label="Ngày diễn ra"
            />
          </label>
          <a
            href="#events"
            className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-5 text-sm font-semibold text-primary-foreground shadow-sm hover:opacity-90"
          >
            <CalendarDays aria-hidden="true" className="size-4" />
            Khám phá
          </a>
        </section>

        <section id="events" className="mt-12 scroll-mt-6">
          <div className="mb-6 flex items-end justify-between gap-4">
            <div>
              <h2 className="font-heading text-2xl font-bold tracking-tight">
                Đang diễn ra gần bạn
              </h2>
              <p className="mt-1 text-sm text-muted-foreground">
                Gợi ý sự kiện sắp tới dành cho bạn
              </p>
            </div>
            <span className="hidden text-sm font-medium text-primary sm:block">
              Sự kiện sắp tới{" "}
              <ArrowRight aria-hidden="true" className="ml-1 inline size-4" />
            </span>
          </div>

          {isPending ? (
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 6 }, (_, index) => (
                <div
                  key={index}
                  className="h-80 animate-pulse rounded-xl bg-muted"
                />
              ))}
            </div>
          ) : null}
          {isError ? (
            <div
              role="alert"
              className="rounded-xl border border-destructive/30 bg-destructive/5 p-6 text-sm"
            >
              <p className="font-semibold text-destructive">
                Không tải được danh sách sự kiện
              </p>
              <p className="mt-1 text-muted-foreground">
                {getErrorMessage(error)}
              </p>
            </div>
          ) : null}
          {!isPending && !isError && events.length === 0 ? (
            <div className="rounded-xl border border-dashed border-border px-6 py-16 text-center">
              <Music2
                aria-hidden="true"
                className="mx-auto size-8 text-muted-foreground"
              />
              <h3 className="mt-4 font-semibold">
                Chưa tìm thấy sự kiện phù hợp
              </h3>
              <p className="mt-1 text-sm text-muted-foreground">
                Thử từ khóa hoặc thành phố khác nhé.
              </p>
            </div>
          ) : null}
          {events.length > 0 ? (
            <div className="grid gap-6 sm:grid-cols-2 lg:grid-cols-3">
              {events.map((event) => (
                <Link
                  key={event.id}
                  href={`/events/${event.id}`}
                  className="group overflow-hidden rounded-xl border border-border bg-card transition-shadow hover:shadow-md focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <div className="relative aspect-[1.65/1] overflow-hidden bg-gradient-to-br from-primary/90 via-primary/70 to-accent">
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
                      <div className="flex size-full items-center justify-center text-primary-foreground">
                        <Music2
                          aria-hidden="true"
                          className="size-12 opacity-80"
                        />
                      </div>
                    )}
                    {event.minPrice !== null ? (
                      <span className="absolute bottom-3 left-3 rounded-full bg-background/95 px-3 py-1.5 text-xs font-bold text-foreground">
                        Từ {currency.format(event.minPrice)}
                      </span>
                    ) : null}
                  </div>
                  <div className="p-5">
                    <p className="text-sm font-semibold text-primary">
                      <EventDate value={event.startAt} />
                    </p>
                    <h3 className="mt-2 line-clamp-2 font-heading text-lg font-bold leading-snug">
                      {event.name}
                    </h3>
                    <p className="mt-3 flex items-center gap-2 text-sm text-muted-foreground">
                      <MapPin aria-hidden="true" className="size-4 shrink-0" />
                      <span className="truncate">
                        {event.venue.name} · {event.venue.city}
                      </span>
                    </p>
                  </div>
                </Link>
              ))}
            </div>
          ) : null}
          {hasNextPage ? (
            <div className="mt-9 text-center">
              <button
                type="button"
                onClick={() => fetchNextPage()}
                disabled={isFetchingNextPage}
                className="min-h-11 rounded-lg border border-border px-6 text-sm font-semibold hover:bg-muted disabled:opacity-60"
              >
                {isFetchingNextPage ? "Đang tải…" : "Xem thêm sự kiện"}
              </button>
            </div>
          ) : null}
        </section>
      </main>
      <EventFooter />
    </div>
  );
}
