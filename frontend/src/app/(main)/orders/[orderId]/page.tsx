"use client";

import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  AlertCircle,
  ArrowLeft,
  CheckCircle2,
  Clock3,
  CreditCard,
  LoaderCircle,
  Minus,
  Plus,
  Ticket,
  Trash2,
  Undo2,
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
  getRefundQuote,
  getTicketQr,
  removeOrderItem,
  requestRefund,
  startCheckout,
  updateOrderItemQuantity,
} from "@/lib/api/orders";
import { getErrorMessage } from "@/lib/api/client";
import type { OrderTicket } from "@/types/orders";

const money = new Intl.NumberFormat("en-US", {
  style: "currency",
  currency: "USD",
});

const ticketTime = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
});

// Ảnh QR cần JWT nên không dùng <img src> trực tiếp: tải blob qua apiClient rồi tạo object URL.
// gcTime 0 để mỗi lần mount tải lại — object URL bị thu hồi khi unmount không được dùng lại từ cache.
function TicketQr({ orderId, ticket }: { orderId: string; ticket: OrderTicket }) {
  const qrQuery = useQuery({
    queryKey: ["ticket-qr", orderId, ticket.id],
    queryFn: async () => URL.createObjectURL(await getTicketQr(orderId, ticket.id)),
    staleTime: Infinity,
    gcTime: 0,
  });
  const url = qrQuery.data;
  useEffect(() => {
    return () => {
      if (url) URL.revokeObjectURL(url);
    };
  }, [url]);

  if (qrQuery.isError) {
    return (
      <p role="alert" className="text-xs text-muted-foreground">
        QR code unavailable. Show the ticket code below instead.
      </p>
    );
  }
  if (!url) {
    return (
      <div
        role="status"
        aria-label="Loading QR code"
        className="size-40 animate-pulse rounded-lg bg-muted"
      />
    );
  }
  return (
    // eslint-disable-next-line @next/next/no-img-element -- object URL cục bộ, next/image không tối ưu được
    <img
      src={url}
      alt={`QR code for ${ticket.ticketName}${ticket.seatLabel ? `, ${ticket.seatLabel}` : ""}`}
      width={160}
      height={160}
      className="size-40 rounded-lg border border-border bg-white p-1"
    />
  );
}

function TicketsPanel({
  orderId,
  tickets,
}: {
  orderId: string;
  tickets: OrderTicket[];
}) {
  return (
    <section
      aria-labelledby="tickets-title"
      className="mt-6 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7"
    >
      <h2 id="tickets-title" className="font-heading text-xl font-bold">
        Your tickets
      </h2>
      <p className="mt-1 text-sm text-muted-foreground">
        Show one QR code per person at the entrance. Each code can be scanned
        once.
      </p>
      <ul className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {tickets.map((ticket) => (
          <li
            key={ticket.id}
            className={`flex flex-col items-center gap-3 rounded-xl border p-4 text-center ${ticket.status === "VALID" ? "border-border" : "border-dashed border-border bg-muted/40"}`}
          >
            <div>
              <p className="font-semibold">{ticket.ticketName}</p>
              <p className="text-sm text-muted-foreground">
                {ticket.seatLabel ?? "General admission"}
              </p>
            </div>
            {ticket.status === "VALID" ? (
              <TicketQr orderId={orderId} ticket={ticket} />
            ) : (
              <p className="rounded-full bg-muted px-3 py-1 text-xs font-semibold">
                {ticket.status === "CHECKED_IN"
                  ? `Checked in${ticket.checkedInAt ? ` · ${ticketTime.format(new Date(ticket.checkedInAt))}` : ""}`
                  : "Cancelled"}
              </p>
            )}
            {ticket.status === "VALID" ? (
              <p className="break-all font-mono text-xs text-muted-foreground">
                {ticket.code}
              </p>
            ) : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

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
  // Stripe đưa buyer về /orders/{id}?payment=success|cancelled. Kết quả thật đến qua webhook →
  // Kafka → Order, nên tham số này chỉ để chọn thông báo và tăng nhịp refetch, không phải bằng chứng đã trả tiền.
  const paymentParam = useSearchParams().get("payment");
  const paymentReturn =
    paymentParam === "success" || paymentParam === "cancelled"
      ? paymentParam
      : null;
  const checkoutMutation = useMutation({
    mutationFn: () => startCheckout(orderId),
    // Rời trang sang Stripe Checkout; nút giữ trạng thái loading cho tới khi trình duyệt chuyển trang
    onSuccess: (session) => window.location.assign(session.checkoutUrl),
  });
  const [isRefundDialogOpen, setRefundDialogOpen] = useState(false);
  const refundQuoteQuery = useQuery({
    queryKey: ["refund-quote", orderId],
    queryFn: () => getRefundQuote(orderId),
    enabled: isRefundDialogOpen,
    // Mốc % đổi theo thời gian: luôn lấy lại khi mở hộp thoại
    staleTime: 0,
    gcTime: 0,
  });
  const refundMutation = useMutation({
    mutationFn: () => requestRefund(orderId),
    onSuccess: async (next) => {
      setRefundDialogOpen(false);
      await updateOrder(next);
      await queryClient.invalidateQueries({ queryKey: ["orders"] });
    },
  });
  const orderQuery = useQuery({
    queryKey: ["order", orderId],
    queryFn: () => getOrder(orderId),
    refetchInterval: (query) => {
      if (
        removeMutation.isPending ||
        quantityMutation.isPending ||
        confirmMutation.isPending ||
        cancelMutation.isPending ||
        refundMutation.isPending
      )
        return false;
      const status = query.state.data?.status;
      // Chờ webhook xác nhận thanh toán hoặc chờ Payment hoàn tiền: hỏi lại nhanh hơn
      if (paymentReturn === "success" && status === "PENDING_PAYMENT")
        return 2_000;
      if (status === "REFUND_PENDING") return 5_000;
      return 15_000;
    },
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
  const isPendingPayment = order?.status === "PENDING_PAYMENT";
  const isPaid = order?.status === "PAID" || order?.status === "TICKETS_ISSUED";
  const isRefund =
    order?.status === "REFUND_PENDING" || order?.status === "REFUNDED";
  const refund = order?.refund ?? null;
  // Deadline hold cũng là hạn thanh toán: quá hạn thì Order bị expire ở lần đọc kế tiếp
  const expired =
    order?.status === "EXPIRED" ||
    ((isCart || isPendingPayment) && now > 0 && remaining === 0);
  // Đơn đã trả tiền của event bị hủy đi theo luồng hoàn tiền, không dùng thông báo "holds released"
  const cancelled =
    !isPaid &&
    !isRefund &&
    (order?.status === "CANCELLED" || eventQuery.data?.status === "CANCELLED");
  const awaitingPaymentResult =
    isPendingPayment && paymentReturn === "success" && !expired;
  const hours = Math.floor(remaining / 3_600_000);
  const minutes = Math.floor((remaining % 3_600_000) / 60_000);
  const seconds = Math.floor((remaining % 60_000) / 1000);
  // Đơn đang/đã hoàn: vé đã chuyển sang CANCELLED nhưng vẫn cần hiển thị buyer đã mua gì
  const items =
    order?.items.filter((item) =>
      isRefund ? item.status === "CANCELLED" : item.status === "ACTIVE",
    ) ?? [];
  const isLoading = orderQuery.isPending;
  const error = orderQuery.error ?? eventQuery.error;
  const message =
    quantityMutation.error ??
    removeMutation.error ??
    confirmMutation.error ??
    cancelMutation.error ??
    checkoutMutation.error ??
    refundMutation.error;

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
                        ? "Complete your payment"
                        : isPaid
                          ? "Your tickets are paid"
                          : order.status === "REFUND_PENDING"
                            ? "Refund in progress"
                            : order.status === "REFUNDED"
                              ? "Order refunded"
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
              {(isCart || isPendingPayment) && !expired && !cancelled ? (
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
            {isPendingPayment && !expired && !cancelled ? (
              <section
                role="status"
                className="mt-6 flex items-start gap-3 rounded-xl border border-primary/20 bg-accent p-5"
              >
                {awaitingPaymentResult ? (
                  <LoaderCircle
                    aria-hidden="true"
                    className="mt-0.5 size-5 animate-spin text-primary"
                  />
                ) : (
                  <CheckCircle2
                    aria-hidden="true"
                    className="mt-0.5 size-5 text-primary"
                  />
                )}
                <div>
                  <h2 className="font-semibold">
                    {awaitingPaymentResult
                      ? "Confirming your payment"
                      : "Your cart is confirmed"}
                  </h2>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {awaitingPaymentResult ? (
                      "We are waiting for the payment provider to confirm. This page updates automatically."
                    ) : (
                      <>
                        {paymentReturn === "cancelled"
                          ? "Payment was not completed. "
                          : ""}
                        Pay before{" "}
                        {new Intl.DateTimeFormat("en-US", {
                          hour: "numeric",
                          minute: "2-digit",
                        }).format(new Date(order.holdExpiresAt))}{" "}
                        to keep your tickets. The hold is not extended.
                      </>
                    )}
                  </p>
                </div>
              </section>
            ) : null}
            {isPaid ? (
              <section
                role="status"
                className="mt-6 flex items-start gap-3 rounded-xl border border-primary/20 bg-accent p-5"
              >
                <CheckCircle2
                  aria-hidden="true"
                  className="mt-0.5 size-5 text-primary"
                />
                <div>
                  <h2 className="font-semibold">Payment successful</h2>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {order.paidAt ? (
                      <>
                        Paid on{" "}
                        {new Intl.DateTimeFormat("en-US", {
                          dateStyle: "medium",
                          timeStyle: "short",
                        }).format(new Date(order.paidAt))}
                        .{" "}
                      </>
                    ) : null}
                    Your tickets are ready below.
                  </p>
                </div>
              </section>
            ) : null}
            {refund ? (
              <section
                role="status"
                className="mt-6 flex items-start gap-3 rounded-xl border border-border bg-muted/50 p-5"
              >
                <Undo2
                  aria-hidden="true"
                  className="mt-0.5 size-5 text-muted-foreground"
                />
                <div>
                  <h2 className="font-semibold">
                    {refund.status === "COMPLETED"
                      ? `Refund of ${money.format(refund.amount)} completed`
                      : refund.status === "FAILED"
                        ? "Refund needs attention"
                        : `Refund of ${money.format(refund.amount)} is being processed`}
                  </h2>
                  <p className="mt-1 text-sm text-muted-foreground">
                    {refund.status === "FAILED"
                      ? "We could not complete this refund automatically. Our team has been notified and will follow up."
                      : refund.reason === "LATE_PAYMENT"
                        ? "Your payment arrived after the hold had ended, so the tickets were no longer reserved. The full amount is returned to your payment method."
                        : refund.reason === "EVENT_CANCELLED"
                          ? "This event was cancelled. The full amount is returned to your payment method."
                          : `You cancelled this order. ${refund.percent}% of the amount paid is returned to your payment method.`}
                  </p>
                </div>
              </section>
            ) : null}

            {order.tickets.length ? (
              <TicketsPanel orderId={order.id} tickets={order.tickets} />
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
                  <span className="text-muted-foreground">
                    {isPaid || isRefund ? "Total paid" : "Subtotal"}
                  </span>
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
                    Confirming locks your selection. You then pay before the
                    hold timer ends.
                  </p>
                ) : null}
                {isPendingPayment && !expired && !cancelled ? (
                  <>
                    <button
                      type="button"
                      onClick={() => checkoutMutation.mutate()}
                      disabled={
                        checkoutMutation.isPending ||
                        checkoutMutation.isSuccess ||
                        awaitingPaymentResult
                      }
                      className="mt-5 inline-flex min-h-12 w-full items-center justify-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground transition hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {checkoutMutation.isPending ||
                      checkoutMutation.isSuccess ? (
                        <>
                          <LoaderCircle
                            aria-hidden="true"
                            className="size-4 animate-spin"
                          />
                          Redirecting to payment…
                        </>
                      ) : (
                        <>
                          <CreditCard aria-hidden="true" className="size-4" />
                          Pay {money.format(order.totalAmount)}
                        </>
                      )}
                    </button>
                    <p className="mt-3 text-center text-xs text-muted-foreground">
                      Secure card payment is handled by Stripe.
                    </p>
                  </>
                ) : null}
                {isPaid ? (
                  <button
                    type="button"
                    onClick={() => setRefundDialogOpen(true)}
                    disabled={refundMutation.isPending}
                    className="mt-5 inline-flex min-h-11 w-full items-center justify-center gap-2 rounded-lg border border-border px-4 text-sm font-semibold text-destructive transition hover:bg-destructive/5 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <Undo2 aria-hidden="true" className="size-4" />
                    Cancel order and refund
                  </button>
                ) : null}
                {/* OR08 chỉ cho CART: backend từ chối hủy PENDING_PAYMENT vì buyer có thể đang trả tiền (D27) */}
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
      <Dialog
        open={isRefundDialogOpen}
        onOpenChange={(open) => {
          if (!refundMutation.isPending) setRefundDialogOpen(open);
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Cancel this order?</DialogTitle>
            <DialogDescription>
              Refunds are 100% up to 7 days before the event, 50% from 7 days
              to 24 hours before, and not available within 24 hours of the
              event.
            </DialogDescription>
          </DialogHeader>
          {refundQuoteQuery.isPending ? (
            <div
              role="status"
              aria-label="Loading refund amount"
              className="h-16 animate-pulse rounded-xl bg-muted"
            />
          ) : refundQuoteQuery.isError ? (
            <p role="alert" className="text-sm text-destructive">
              {getErrorMessage(
                refundQuoteQuery.error,
                "Could not load the refund amount.",
              )}
            </p>
          ) : refundQuoteQuery.data.refundable ? (
            <div className="rounded-xl bg-muted/60 p-4 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">
                  Refund ({refundQuoteQuery.data.percent}%)
                </span>
                <span className="font-semibold">
                  {money.format(refundQuoteQuery.data.amount)}
                </span>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">
                Your tickets are released immediately and this cannot be
                undone. The final amount is calculated when you confirm.
              </p>
            </div>
          ) : (
            <p role="status" className="text-sm text-muted-foreground">
              This order can no longer be refunded because the event starts in
              less than 24 hours.
            </p>
          )}
          <DialogFooter>
            <button
              type="button"
              onClick={() => setRefundDialogOpen(false)}
              disabled={refundMutation.isPending}
              className="inline-flex min-h-10 items-center justify-center rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted"
            >
              Keep order
            </button>
            <button
              type="button"
              onClick={() => refundMutation.mutate()}
              disabled={
                refundMutation.isPending || !refundQuoteQuery.data?.refundable
              }
              className="inline-flex min-h-10 items-center justify-center gap-2 rounded-lg bg-destructive px-4 text-sm font-semibold text-white disabled:opacity-60"
            >
              {refundMutation.isPending ? (
                <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
              ) : null}
              {refundMutation.isPending ? "Requesting…" : "Cancel and refund"}
            </button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
