"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Clock3,
  LoaderCircle,
  Minus,
  Plus,
  Ticket,
  Trash2,
} from "lucide-react";
import {
  EventDate,
  EventFooter,
  EventHeader,
} from "@/components/events/EventHeader";
import { getEvent } from "@/lib/api/events";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  cancelOrder,
  confirmOrder,
  getAvailability,
  getOrder,
  removeOrderItem,
  updateOrderItemQuantity,
} from "@/lib/api/orders";
import { getErrorMessage } from "@/lib/api/client";

const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
});

function CartSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading cart"
      className="mt-8 grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]"
    >
      <div className="space-y-4 rounded-2xl border border-border bg-card p-6">
        <div className="h-7 w-48 animate-pulse rounded bg-muted" />
        <div className="h-20 animate-pulse rounded-xl bg-muted" />
        <div className="h-20 animate-pulse rounded-xl bg-muted" />
      </div>
      <div className="h-64 animate-pulse rounded-2xl border border-border bg-muted" />
      <span className="sr-only">Loading your tickets</span>
    </div>
  );
}

export default function OrderCartPage() {
  const { orderId } = useParams<{ orderId: string }>();
  const router = useRouter();
  const queryClient = useQueryClient();
  const [now, setNow] = useState(0);
  const updateOrder = async (next: Awaited<ReturnType<typeof getOrder>>) => {
    await queryClient.cancelQueries({
      queryKey: ["order", orderId],
      exact: true,
    });
    queryClient.setQueryData(["order", orderId], next);
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: ["order", orderId],
        exact: true,
      }),
      queryClient.invalidateQueries({ queryKey: ["orders"], exact: true }),
      queryClient.invalidateQueries({
        queryKey: ["availability", next.eventId],
        exact: true,
      }),
    ]);
  };
  const removeMutation = useMutation({
    mutationFn: (itemId: string) => removeOrderItem(orderId, itemId),
    onMutate: async () => {
      await queryClient.cancelQueries({
        queryKey: ["order", orderId],
        exact: true,
      });
    },
    onSuccess: updateOrder,
  });
  const quantityMutation = useMutation({
    mutationFn: ({ itemId, quantity }: { itemId: string; quantity: number }) =>
      updateOrderItemQuantity(orderId, itemId, quantity),
    onMutate: async () => {
      await queryClient.cancelQueries({
        queryKey: ["order", orderId],
        exact: true,
      });
    },
    onSuccess: updateOrder,
    onError: () => {
      const eventId = queryClient.getQueryData<{ eventId: string }>([
        "order",
        orderId,
      ])?.eventId;
      if (eventId) {
        void queryClient.invalidateQueries({
          queryKey: ["availability", eventId],
          exact: true,
        });
      }
    },
  });
  const confirmMutation = useMutation({
    mutationFn: () => confirmOrder(orderId),
    onSuccess: updateOrder,
  });
  const [isCancelDialogOpen, setCancelDialogOpen] = useState(false);
  const cancelMutation = useMutation({
    mutationFn: () => cancelOrder(orderId),
    onSuccess: async (next) => {
      setCancelDialogOpen(false);
      await updateOrder(next);
    },
  });
  const orderQuery = useQuery({
    queryKey: ["order", orderId],
    queryFn: () => getOrder(orderId),
    refetchInterval:
      removeMutation.isPending ||
      quantityMutation.isPending ||
      confirmMutation.isPending ||
      cancelMutation.isPending
        ? false
        : 15_000,
  });
  const order = orderQuery.data;
  const eventQuery = useQuery({
    queryKey: ["event", order?.eventId],
    queryFn: () => getEvent(order!.eventId),
    enabled: Boolean(order?.eventId),
  });
  const availabilityQuery = useQuery({
    queryKey: ["availability", order?.eventId],
    queryFn: () => getAvailability(order!.eventId),
    enabled: Boolean(order?.eventId),
    refetchInterval: 15_000,
  });

  useEffect(() => {
    const tick = () => setNow(Date.now());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, []);

  const remaining = order
    ? Math.max(0, new Date(order.holdExpiresAt).getTime() - now)
    : 0;
  const isCart = order?.status === "CART";
  const expired =
    order?.status === "EXPIRED" || (isCart && now > 0 && remaining === 0);
  const cancelled =
    order?.status === "CANCELLED" || eventQuery.data?.status === "CANCELLED";
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  const seconds = Math.floor((remaining % 60_000) / 1000);
  const items = order?.items.filter((item) => item.status === "ACTIVE") ?? [];
  const isLoading = orderQuery.isPending;
  const error = orderQuery.error ?? eventQuery.error;
  const message =
    quantityMutation.error ??
    removeMutation.error ??
    confirmMutation.error ??
    cancelMutation.error;

  return (
    <div className="min-h-screen bg-background text-foreground">
      <EventHeader />
      <main className="mx-auto max-w-7xl px-5 py-8 md:px-8">
        <Link
          href={order ? `/events/${order.eventId}` : "/"}
          className="inline-flex min-h-10 items-center gap-2 text-sm font-semibold text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
          Back to event
        </Link>
        {isLoading ? <CartSkeleton /> : null}
        {error ? (
          <section
            role="alert"
            className="mt-8 rounded-2xl border border-destructive/30 bg-card p-7"
          >
            <div className="flex items-start gap-3">
              <AlertCircle
                aria-hidden="true"
                className="mt-0.5 size-5 text-destructive"
              />
              <div>
                <h1 className="font-heading text-xl font-bold">
                  Could not load your cart
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  {getErrorMessage(
                    error,
                    "This cart may no longer be available.",
                  )}
                </p>
                <button
                  type="button"
                  onClick={() => void orderQuery.refetch()}
                  className="mt-5 min-h-10 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
                >
                  Try again
                </button>
              </div>
            </div>
          </section>
        ) : null}
        {order && !error ? (
          <>
            <div className="mt-7 flex flex-wrap items-end justify-between gap-4">
              <div>
                <p className="text-xs font-bold uppercase tracking-[0.15em] text-primary">
                  Your tickets
                </p>
                <h1 className="mt-2 font-heading text-3xl font-bold tracking-tight">
                  {cancelled
                    ? "Event cancelled"
                    : expired
                      ? "Your hold has expired"
                      : order.status === "PENDING_PAYMENT"
                        ? "Cart confirmed"
                        : "Review your cart"}
                </h1>
                <p className="mt-2 text-sm text-muted-foreground">
                  {eventQuery.data ? (
                    <>
                      {eventQuery.data.name} ·{" "}
                      <EventDate value={eventQuery.data.startAt} />
                    </>
                  ) : (
                    "Loading event details…"
                  )}
                </p>
              </div>
              {isCart && !expired && !cancelled ? (
                <div
                  role="timer"
                  aria-live="polite"
                  className={`inline-flex items-center gap-2 rounded-full px-4 py-2 text-sm font-semibold ${remaining < 60_000 ? "bg-destructive/10 text-destructive" : "bg-accent text-accent-foreground"}`}
                >
                  <Clock3 aria-hidden="true" className="size-4" />
                  {hours > 0 ? `${hours}:` : ""}
                  {String(minutes).padStart(2, "0")}:
                  {String(seconds).padStart(2, "0")} left
                </div>
              ) : null}
            </div>

            {message ? (
              <div
                role="alert"
                className="mt-5 flex items-start gap-3 rounded-xl border border-destructive/30 bg-destructive/5 p-4 text-sm"
              >
                <AlertCircle
                  aria-hidden="true"
                  className="mt-0.5 size-4 shrink-0 text-destructive"
                />
                <p>
                  {getErrorMessage(
                    message,
                    "Your selection is no longer available. Refresh and choose again.",
                  )}
                </p>
              </div>
            ) : null}
            {expired || cancelled ? (
              <section
                role="status"
                className="mt-6 flex items-start gap-3 rounded-xl border border-border bg-muted/50 p-5"
              >
                <AlertCircle
                  aria-hidden="true"
                  className="mt-0.5 size-5 text-muted-foreground"
                />
                <div>
                  <h2 className="font-semibold">
                    {cancelled
                      ? "This event was cancelled"
                      : "The 10-minute hold window ended"}
                  </h2>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {cancelled
                      ? "Your holds have been released. You can return to discover other events."
                      : "The tickets were released and may no longer be available."}
                  </p>
                  <Link
                    href={cancelled ? "/" : `/events/${order.eventId}`}
                    className="mt-4 inline-flex min-h-10 items-center rounded-lg border border-border bg-background px-4 text-sm font-semibold hover:bg-muted"
                  >
                    {cancelled ? "Explore events" : "Choose tickets again"}
                  </Link>
                </div>
              </section>
            ) : null}
            {order.status === "PENDING_PAYMENT" ? (
              <section
                role="status"
                className="mt-6 flex items-start gap-3 rounded-xl border border-primary/20 bg-accent p-5"
              >
                <CheckCircle2
                  aria-hidden="true"
                  className="mt-0.5 size-5 text-primary"
                />
                <div>
                  <h2 className="font-semibold">Your cart is confirmed</h2>
                  <p className="mt-1 text-sm text-muted-foreground">
                    Payment will be available in the next phase. Your tickets
                    remain held until{" "}
                    {new Intl.DateTimeFormat("en-US", {
                      hour: "numeric",
                      minute: "2-digit",
                    }).format(new Date(order.holdExpiresAt))}
                    .
                  </p>
                </div>
              </section>
            ) : null}

            <div className="mt-6 grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
              <section
                aria-labelledby="cart-items-title"
                className="rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7"
              >
                <div className="flex items-center justify-between border-b border-border pb-5">
                  <div>
                    <h2
                      id="cart-items-title"
                      className="font-heading text-xl font-bold"
                    >
                      Ticket selection
                    </h2>
                    <p className="mt-1 text-sm text-muted-foreground">
                      One cart per event · prices are held for this cart
                    </p>
                  </div>
                  <span className="rounded-full bg-muted px-3 py-1 text-xs font-semibold">
                    {items.reduce((sum, item) => sum + item.quantity, 0)}{" "}
                    tickets
                  </span>
                </div>
                {items.length ? (
                  <ul className="divide-y divide-border">
                    {items.map((item) => (
                      <li
                        key={item.id}
                        className="flex flex-wrap items-center justify-between gap-4 py-5"
                      >
                        <div className="flex min-w-0 items-start gap-3">
                          <span className="mt-0.5 flex size-10 shrink-0 items-center justify-center rounded-xl bg-accent text-primary">
                            <Ticket aria-hidden="true" className="size-5" />
                          </span>
                          <div className="min-w-0">
                            <p className="font-semibold">{item.ticketName}</p>
                            <p className="mt-1 text-sm text-muted-foreground">
                              {item.seatLabel ??
                                `${item.category === "GENERAL_ADMISSION" ? "General admission" : "Reserved seat"} · ${item.quantity} ${item.quantity === 1 ? "ticket" : "tickets"}`}
                            </p>
                            {item.seatLabel ? (
                              <p className="mt-1 text-xs text-muted-foreground">
                                {item.category === "SEATED"
                                  ? "Reserved seat"
                                  : "Ticket"}
                              </p>
                            ) : null}
                          </div>
                        </div>
                        <div className="ml-auto flex items-center gap-3">
                          <span className="font-semibold">
                            {money.format(item.subtotal)}
                          </span>
                          {isCart && !expired && !cancelled ? (
                            <>
                              {item.category === "GENERAL_ADMISSION" ? (
                                <div
                                  className="inline-flex items-center gap-2 rounded-lg border border-border p-1"
                                  aria-label={`${item.ticketName} quantity`}
                                >
                                  <button
                                    type="button"
                                    onClick={() =>
                                      quantityMutation.mutate({
                                        itemId: item.id,
                                        quantity: item.quantity - 1,
                                      })
                                    }
                                    disabled={
                                      item.quantity <= 1 ||
                                      quantityMutation.isPending ||
                                      removeMutation.isPending ||
                                      confirmMutation.isPending ||
                                      expired ||
                                      cancelled
                                    }
                                    aria-label={`Decrease ${item.ticketName} quantity`}
                                    className="inline-flex size-8 items-center justify-center rounded-md hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
                                  >
                                    <Minus
                                      aria-hidden="true"
                                      className="size-4"
                                    />
                                  </button>
                                  <span
                                    aria-live="polite"
                                    className="min-w-6 text-center text-sm font-semibold tabular-nums"
                                  >
                                    {quantityMutation.isPending &&
                                    quantityMutation.variables?.itemId ===
                                      item.id ? (
                                      <LoaderCircle
                                        aria-label="Updating quantity"
                                        className="mx-auto size-4 animate-spin text-primary"
                                      />
                                    ) : (
                                      item.quantity
                                    )}
                                  </span>
                                  <button
                                    type="button"
                                    onClick={() =>
                                      quantityMutation.mutate({
                                        itemId: item.id,
                                        quantity: item.quantity + 1,
                                      })
                                    }
                                    disabled={
                                      quantityMutation.isPending ||
                                      removeMutation.isPending ||
                                      confirmMutation.isPending ||
                                      expired ||
                                      cancelled ||
                                      availabilityQuery.data?.ticketTypes.find(
                                        (ticketType) =>
                                          ticketType.ticketTypeId ===
                                          item.ticketTypeId,
                                      )?.availableQuantity === 0
                                    }
                                    aria-label={`Increase ${item.ticketName} quantity`}
                                    title={
                                      availabilityQuery.data?.ticketTypes.find(
                                        (ticketType) =>
                                          ticketType.ticketTypeId ===
                                          item.ticketTypeId,
                                      )?.availableQuantity === 0
                                        ? "No more tickets available"
                                        : undefined
                                    }
                                    className="inline-flex size-8 items-center justify-center rounded-md hover:bg-muted disabled:cursor-not-allowed disabled:opacity-40"
                                  >
                                    <Plus
                                      aria-hidden="true"
                                      className="size-4"
                                    />
                                  </button>
                                </div>
                              ) : null}
                              <button
                                type="button"
                                onClick={() => removeMutation.mutate(item.id)}
                                disabled={
                                  removeMutation.isPending ||
                                  quantityMutation.isPending ||
                                  confirmMutation.isPending
                                }
                                aria-label={`Remove ${item.ticketName}`}
                                className="inline-flex size-9 items-center justify-center rounded-lg text-muted-foreground hover:bg-muted hover:text-destructive disabled:opacity-50"
                              >
                                <Trash2 aria-hidden="true" className="size-4" />
                              </button>
                            </>
                          ) : null}
                        </div>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <div className="py-12 text-center">
                    <Ticket
                      aria-hidden="true"
                      className="mx-auto size-8 text-muted-foreground"
                    />
                    <h3 className="mt-3 font-semibold">
                      No tickets in this cart
                    </h3>
                    <p className="mt-1 text-sm text-muted-foreground">
                      Choose tickets from the event page to get started.
                    </p>
                    <Link
                      href={`/events/${order.eventId}`}
                      className="mt-4 inline-flex min-h-10 items-center rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
                    >
                      Browse tickets
                    </Link>
                  </div>
                )}
              </section>
              <aside className="rounded-2xl border border-border bg-card p-5 shadow-sm lg:sticky lg:top-24">
                <h2 className="font-heading text-lg font-bold">
                  Order summary
                </h2>
                {eventQuery.data ? (
                  <div className="mt-4 rounded-xl bg-muted/60 p-4">
                    <p className="font-semibold leading-5">
                      {eventQuery.data.name}
                    </p>
                    <p className="mt-2 text-xs text-muted-foreground">
                      <EventDate value={eventQuery.data.startAt} />
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      {eventQuery.data.venue.name}
                    </p>
                  </div>
                ) : (
                  <div className="mt-4 h-20 animate-pulse rounded-xl bg-muted" />
                )}
                <div className="mt-5 flex justify-between border-t border-border pt-4 text-sm">
                  <span className="text-muted-foreground">Subtotal</span>
                  <span className="font-semibold">
                    {money.format(order.totalAmount)}
                  </span>
                </div>
                <p className="mt-2 text-xs leading-5 text-muted-foreground">
                  Any applicable fees are shown before payment.
                </p>
                {isCart && !expired && !cancelled ? (
                  <button
                    type="button"
                    onClick={() => confirmMutation.mutate()}
                    disabled={
                      !items.length ||
                      confirmMutation.isPending ||
                      removeMutation.isPending ||
                      quantityMutation.isPending ||
                      remaining === 0
                    }
                    className="mt-5 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {confirmMutation.isPending ? (
                      <>
                        <LoaderCircle
                          aria-hidden="true"
                          className="size-4 animate-spin"
                        />
                        Confirming…
                      </>
                    ) : (
                      "Confirm cart"
                    )}
                  </button>
                ) : null}
                {isCart ? (
                  <p className="mt-3 text-center text-xs text-muted-foreground">
                    Confirming keeps your tickets held. Payment will follow in
                    the next phase.
                  </p>
                ) : null}
                {/* OR08 chỉ cho CART: hủy khi PENDING_PAYMENT cần phối hợp với Payment, để tới P4 (D26) */}
                {isCart && !expired && !cancelled ? (
                  <button
                    type="button"
                    onClick={() => setCancelDialogOpen(true)}
                    disabled={
                      cancelMutation.isPending ||
                      confirmMutation.isPending ||
                      removeMutation.isPending ||
                      quantityMutation.isPending
                    }
                    className="mt-4 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-lg border border-border px-4 text-sm font-semibold text-destructive transition hover:bg-destructive/5 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Trash2 aria-hidden="true" className="size-4" />
                    Cancel cart
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={() => router.push("/")}
                  className="mt-4 flex min-h-10 w-full items-center justify-center gap-2 text-sm font-semibold text-muted-foreground hover:text-foreground"
                >
                  <Minus aria-hidden="true" className="size-4" />
                  Continue exploring events
                </button>
              </aside>
            </div>
          </>
        ) : null}
      </main>
      <EventFooter />
      <Dialog
        open={isCancelDialogOpen}
        onOpenChange={(open) => {
          if (!cancelMutation.isPending) setCancelDialogOpen(open);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Cancel this cart?</DialogTitle>
            <DialogDescription>
              All tickets in this cart are released right away so other buyers
              can get them. This cannot be undone.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <button
              type="button"
              onClick={() => setCancelDialogOpen(false)}
              disabled={cancelMutation.isPending}
              className="inline-flex min-h-10 items-center justify-center rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted"
            >
              Keep cart
            </button>
            <button
              type="button"
              onClick={() => cancelMutation.mutate()}
              disabled={cancelMutation.isPending}
              className="inline-flex min-h-10 items-center justify-center gap-2 rounded-lg bg-destructive px-4 text-sm font-semibold text-white disabled:opacity-60"
            >
              {cancelMutation.isPending ? (
                <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
              ) : null}
              {cancelMutation.isPending ? "Cancelling…" : "Cancel cart"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
