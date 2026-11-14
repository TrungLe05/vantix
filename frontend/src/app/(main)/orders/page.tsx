"use client";

import Link from "next/link";
import { ArrowRight, ShoppingBag } from "lucide-react";
import { useQuery } from "@tanstack/react-query";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";
import { getOrders, getPurchasedOrders } from "@/lib/api/orders";
import { getErrorMessage } from "@/lib/api/client";
import { getEvent } from "@/lib/api/events";
import type { Order } from "@/types/orders";

const purchaseLabel: Partial<Record<Order["status"], string>> = {
  PAID: "Paid",
  TICKETS_ISSUED: "Paid",
  REFUND_PENDING: "Refund in progress",
  REFUNDED: "Refunded",
};

function CartCard({ order }: { order: Order }) {
  const eventQuery = useQuery({
    queryKey: ["event", order.eventId],
    queryFn: () => getEvent(order.eventId),
  });
  const purchased = order.status in purchaseLabel;
  // Đơn đang/đã hoàn: vé đã chuyển CANCELLED, vẫn đếm để buyer biết đơn gồm bao nhiêu vé
  const count = order.items
    .filter((item) =>
      order.status === "REFUND_PENDING" || order.status === "REFUNDED"
        ? item.status === "CANCELLED"
        : item.status === "ACTIVE",
    )
    .reduce((sum, item) => sum + item.quantity, 0);
  return (
    <Link
      href={`/orders/${order.id}`}
      className="group rounded-2xl border border-border bg-card p-5 shadow-sm transition hover:border-primary/40 hover:shadow-md sm:p-6"
    >
      <div className="flex items-start justify-between gap-5">
        <div className="min-w-0">
          <p className="text-xs font-semibold uppercase tracking-wide text-primary">
            {purchaseLabel[order.status] ??
              (order.status === "CART" ? "Held tickets" : "Payment pending")}
          </p>
          <h2 className="mt-2 truncate font-heading text-lg font-bold">
            {eventQuery.data?.name ??
              (eventQuery.isPending ? "Loading event…" : "Event cart")}
          </h2>
          <p className="mt-1 text-sm text-muted-foreground">
            {count} {count === 1 ? "ticket" : "tickets"} ·{" "}
            {purchased
              ? "Paid "
              : order.status === "CART"
                ? "Hold expires "
                : "Pay before "}
            {new Intl.DateTimeFormat("en-US", {
              dateStyle: "medium",
              timeStyle: "short",
            }).format(
              new Date(
                purchased && order.paidAt ? order.paidAt : order.holdExpiresAt,
              ),
            )}
          </p>
        </div>
        <span className="shrink-0 text-sm font-bold">
          {new Intl.NumberFormat("en-US", {
            style: "currency",
            currency: order.currency,
          }).format(order.totalAmount)}
        </span>
      </div>
      <div className="mt-5 flex items-center justify-between border-t border-border pt-4 text-sm">
        <span className="text-muted-foreground">
          {purchased ? "Order details and refund" : "One cart per event"}
        </span>
        <span className="inline-flex items-center gap-2 font-semibold text-primary">
          {purchased ? "View order" : "Open cart"}{" "}
          <ArrowRight
            aria-hidden="true"
            className="size-4 transition-transform group-hover:translate-x-1"
          />
        </span>
      </div>
    </Link>
  );
}

export default function OrdersPage() {
  const query = useQuery({ queryKey: ["orders"], queryFn: getOrders });
  const activeOrders = query.data ?? [];
  const purchasesQuery = useQuery({
    queryKey: ["orders", "purchased"],
    queryFn: getPurchasedOrders,
  });
  const purchases = purchasesQuery.data ?? [];
  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader />
      <main className="mx-auto max-w-5xl px-5 py-10 md:px-8">
        <p className="text-xs font-bold uppercase tracking-[0.15em] text-primary">
          Buyer account
        </p>
        <h1 className="mt-2 font-heading text-3xl font-bold tracking-tight">
          Your event carts
        </h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Each event has its own cart and 10-minute hold window.
        </p>
        {query.isPending ? (
          <div
            role="status"
            aria-label="Loading carts"
            className="mt-8 space-y-4"
          >
            <div className="h-36 animate-pulse rounded-2xl bg-muted" />
            <div className="h-36 animate-pulse rounded-2xl bg-muted" />
          </div>
        ) : null}
        {query.isError ? (
          <section
            role="alert"
            className="mt-8 rounded-2xl border border-destructive/30 p-6"
          >
            <h2 className="font-semibold">Could not load your carts</h2>
            <p className="mt-2 text-sm text-muted-foreground">
              {getErrorMessage(query.error)}
            </p>
            <button
              type="button"
              onClick={() => void query.refetch()}
              className="mt-4 min-h-10 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
            >
              Try again
            </button>
          </section>
        ) : null}
        {query.isSuccess && activeOrders.length === 0 ? (
          <section className="mt-8 rounded-2xl border border-dashed border-border bg-card px-6 py-14 text-center">
            <ShoppingBag
              aria-hidden="true"
              className="mx-auto size-8 text-muted-foreground"
            />
            <h2 className="mt-4 font-heading text-lg font-bold">
              No active carts
            </h2>
            <p className="mt-2 text-sm text-muted-foreground">
              Your selected tickets will appear here after a successful hold.
            </p>
            <Link
              href="/"
              className="mt-5 inline-flex min-h-11 items-center rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
            >
              Explore events
            </Link>
          </section>
        ) : null}
        {activeOrders.length ? (
          <div className="mt-8 grid gap-4">
            {activeOrders.map((order) => (
              <CartCard key={order.id} order={order} />
            ))}
          </div>
        ) : null}
        <h2 className="mt-12 font-heading text-xl font-bold tracking-tight">
          Purchases
        </h2>
        {purchasesQuery.isPending ? (
          <div
            role="status"
            aria-label="Loading purchases"
            className="mt-4 h-36 animate-pulse rounded-2xl bg-muted"
          />
        ) : null}
        {purchasesQuery.isError ? (
          <section
            role="alert"
            className="mt-4 rounded-2xl border border-destructive/30 p-6"
          >
            <h3 className="font-semibold">Could not load your purchases</h3>
            <p className="mt-2 text-sm text-muted-foreground">
              {getErrorMessage(purchasesQuery.error)}
            </p>
            <button
              type="button"
              onClick={() => void purchasesQuery.refetch()}
              className="mt-4 min-h-10 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
            >
              Try again
            </button>
          </section>
        ) : null}
        {purchasesQuery.isSuccess && purchases.length === 0 ? (
          <p className="mt-3 text-sm text-muted-foreground">
            Paid orders will appear here.
          </p>
        ) : null}
        {purchases.length ? (
          <div className="mt-4 grid gap-4">
            {purchases.map((order) => (
              <CartCard key={order.id} order={order} />
            ))}
          </div>
        ) : null}
      </main>
      <EventFooter />
    </div>
  );
}
