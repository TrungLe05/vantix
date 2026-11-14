"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  Bell,
  CheckCheck,
  Clock3,
  LayoutDashboard,
  ScanLine,
  ShoppingBag,
  UserRound,
  X,
} from "lucide-react";
import { useAuthStore } from "@/store/auth-store";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { getOrders } from "@/lib/api/orders";
import {
  getNotifications,
  getUnreadNotificationCount,
  markAllNotificationsRead,
  markNotificationRead,
  openNotificationSocket,
} from "@/lib/api/notifications";

const notificationTime = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
});

export function EventHeader({ showSignUp = false }: { showSignUp?: boolean }) {
  const user = useAuthStore((state) => state.user);
  const [drawer, setDrawer] = useState<"cart" | "notifications" | null>(null);
  const [now, setNow] = useState(0);
  const cartQuery = useQuery({
    queryKey: ["orders"],
    queryFn: getOrders,
    enabled: drawer === "cart",
    refetchInterval: drawer === "cart" ? 15_000 : false,
  });

  const queryClient = useQueryClient();
  const isBuyer = user?.role === "BUYER";
  const unreadQuery = useQuery({
    queryKey: ["notifications", "unread-count"],
    queryFn: getUnreadNotificationCount,
    enabled: isBuyer,
    // Lưới an toàn khi WebSocket rớt: vẫn cập nhật badge sau tối đa 1 phút
    refetchInterval: 60_000,
  });
  const notificationsQuery = useQuery({
    queryKey: ["notifications", "list"],
    queryFn: () => getNotifications(0, 30),
    enabled: isBuyer && drawer === "notifications",
  });
  const refreshNotifications = () =>
    queryClient.invalidateQueries({ queryKey: ["notifications"] });
  const markReadMutation = useMutation({
    mutationFn: markNotificationRead,
    onSuccess: refreshNotifications,
  });
  const markAllMutation = useMutation({
    mutationFn: markAllNotificationsRead,
    onSuccess: refreshNotifications,
  });

  // Kênh realtime cho buyer: nhận thông báo mới thì làm mới badge/danh sách và cả đơn hàng liên quan
  // (vé vừa phát hành hiện ngay trên trang order mà không chờ refetch định kỳ). Rớt kết nối thì nối lại
  // với độ trễ tăng dần; mỗi lần nối lấy ticket mới vì ticket chỉ dùng một lần.
  useEffect(() => {
    if (!isBuyer) return;
    let socket: WebSocket | null = null;
    let retryTimer: number | undefined;
    let attempt = 0;
    let stopped = false;
    const connect = async () => {
      try {
        socket = await openNotificationSocket(({ notification }) => {
          void queryClient.invalidateQueries({ queryKey: ["notifications"] });
          if (notification.orderId) {
            void queryClient.invalidateQueries({
              queryKey: ["order", notification.orderId],
            });
            void queryClient.invalidateQueries({ queryKey: ["orders"] });
          }
        });
        if (stopped) {
          socket.close();
          return;
        }
        socket.addEventListener("open", () => {
          attempt = 0;
        });
        socket.addEventListener("close", scheduleReconnect);
      } catch {
        scheduleReconnect();
      }
    };
    const scheduleReconnect = () => {
      if (stopped) return;
      attempt += 1;
      retryTimer = window.setTimeout(
        () => void connect(),
        Math.min(30_000, 1_000 * 2 ** attempt),
      );
    };
    void connect();
    return () => {
      stopped = true;
      window.clearTimeout(retryTimer);
      socket?.close();
    };
  }, [isBuyer, queryClient]);

  const unreadCount = unreadQuery.data ?? 0;

  useEffect(() => {
    if (drawer !== "cart") return;
    const tick = () => setNow(Date.now());
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [drawer]);

  return (
    <header className="sticky top-0 z-40 h-16 border-b border-border bg-background">
      <div className="mx-auto flex h-full w-full max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-8">
          <Link
            href="/"
            className="flex items-center gap-1.5 font-heading text-xl font-bold tracking-tight"
          >
            Vantix
            <span
              aria-hidden="true"
              className="inline-block size-1.5 rounded-full bg-primary"
            />
          </Link>
          <nav
            aria-label="Main navigation"
            className="hidden h-full items-center md:flex"
          >
            <Link
              href="/"
              aria-current="page"
              className="border-b-2 border-primary py-5.25 text-sm font-semibold text-primary"
            >
              Discover
            </Link>
          </nav>
        </div>
        {user ? (
          <div className="flex min-w-0 items-center gap-2 sm:gap-3">
            {user.role === "ADMIN" || user.role === "STAFF" ? (
              <Link
                href="/check-in"
                className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-border px-3 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <ScanLine aria-hidden="true" className="size-4 text-primary" />
                <span>Check-in</span>
              </Link>
            ) : null}
            {user.role === "ADMIN" ? (
              <Link
                href="/admin/events"
                className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-border px-3 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                <LayoutDashboard
                  aria-hidden="true"
                  className="size-4 text-primary"
                />
                <span>Dashboard</span>
              </Link>
            ) : null}
            {user.role === "BUYER" ? (
              <>
                <button
                  type="button"
                  onClick={() => setDrawer("cart")}
                  aria-label="Open shopping cart"
                  title="Shopping cart"
                  className="relative inline-flex size-10 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <ShoppingBag aria-hidden="true" className="size-5" />
                </button>
                <button
                  type="button"
                  onClick={() => setDrawer("notifications")}
                  aria-label={
                    unreadCount > 0
                      ? `Open notifications, ${unreadCount} unread`
                      : "Open notifications"
                  }
                  title="Notifications"
                  className="relative inline-flex size-10 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <Bell aria-hidden="true" className="size-5" />
                  {unreadCount > 0 ? (
                    <span
                      aria-hidden="true"
                      className="absolute right-1 top-1 inline-flex min-w-4 items-center justify-center rounded-full bg-primary px-1 text-[10px] font-bold leading-4 text-primary-foreground"
                    >
                      {unreadCount > 9 ? "9+" : unreadCount}
                    </span>
                  ) : null}
                </button>
              </>
            ) : null}
            <Link
              href="/profile"
              className="inline-flex size-10 items-center justify-center rounded-lg text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label="Open account profile"
              title={user.fullName}
            >
              <UserRound aria-hidden="true" className="size-5 text-primary" />
            </Link>
          </div>
        ) : (
          <div className="flex items-center gap-2 sm:gap-3">
            <Link
              href="/login"
              className="inline-flex min-h-10 items-center rounded-lg border border-border bg-background px-4 text-sm font-medium transition hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              Sign in
            </Link>
            {showSignUp ? (
              <Link
                href="/register"
                className="inline-flex min-h-10 items-center rounded-lg bg-primary px-4 text-sm font-medium text-primary-foreground transition hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                Sign up
              </Link>
            ) : null}
          </div>
        )}
      </div>
      {drawer ? (
        <div
          className="fixed inset-0 z-50 flex justify-end"
          role="presentation"
        >
          <button
            type="button"
            aria-label="Close panel"
            onClick={() => setDrawer(null)}
            className="absolute inset-0 bg-foreground/40 backdrop-blur-sm"
          />
          <aside
            role="dialog"
            aria-modal="true"
            aria-labelledby="buyer-drawer-title"
            className="relative flex h-full w-full max-w-md animate-in slide-in-from-right flex-col border-l border-border bg-background shadow-2xl duration-300"
          >
            <div className="flex items-center justify-between border-b border-border px-6 py-5">
              <div>
                <h2
                  id="buyer-drawer-title"
                  className="font-heading text-lg font-semibold"
                >
                  {drawer === "cart" ? "Your cart" : "Notifications"}
                </h2>
              </div>
              <button
                type="button"
                onClick={() => setDrawer(null)}
                aria-label="Close"
                className="rounded-lg p-2 text-muted-foreground hover:bg-muted"
              >
                <X className="size-5" aria-hidden="true" />
              </button>
            </div>
            {drawer === "cart" ? (
              <div className="flex flex-1 flex-col p-6">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  Your event carts
                </p>
                {cartQuery.isPending ? (
                  <div
                    role="status"
                    aria-label="Loading carts"
                    className="mt-4 space-y-3"
                  >
                    <div className="h-24 animate-pulse rounded-xl bg-muted" />
                    <div className="h-24 animate-pulse rounded-xl bg-muted" />
                  </div>
                ) : null}
                {cartQuery.isError ? (
                  <p
                    role="alert"
                    className="mt-4 rounded-xl border border-destructive/30 p-4 text-sm text-muted-foreground"
                  >
                    Could not load your carts. Close this panel and try again.
                  </p>
                ) : null}
                {cartQuery.data?.length === 0 ? (
                  <div className="mt-4 rounded-xl border border-dashed border-border p-5 text-center">
                    <ShoppingBag
                      aria-hidden="true"
                      className="mx-auto size-6 text-muted-foreground"
                    />
                    <p className="mt-3 text-sm font-semibold">
                      Your cart is empty
                    </p>
                    <p className="mt-1 text-xs text-muted-foreground">
                      Choose tickets from an event to reserve them for 10
                      minutes.
                    </p>
                  </div>
                ) : null}
                <div className="mt-4 space-y-3 overflow-y-auto">
                  {cartQuery.data?.map((order) => {
                    const remaining = Math.max(
                      0,
                      new Date(order.holdExpiresAt).getTime() - now,
                    );
                    const minutes = Math.floor(remaining / 60_000);
                    const seconds = Math.floor((remaining % 60_000) / 1000);
                    const isUrgent = remaining > 0 && remaining <= 60_000;
                    return (
                      <Link
                        key={order.id}
                        href={`/orders/${order.id}`}
                        onClick={() => setDrawer(null)}
                        className="block rounded-xl border border-border bg-card p-4 shadow-sm transition hover:border-primary/40 hover:bg-muted/40"
                      >
                        <div className="flex items-start justify-between gap-3">
                          <div>
                            <h3 className="font-semibold">Event cart</h3>
                            <p className="mt-1 text-sm text-muted-foreground">
                              {order.items.reduce(
                                (sum, item) => sum + item.quantity,
                                0,
                              )}{" "}
                              tickets ·{" "}
                              {order.status === "CART"
                                ? "Cart"
                                : "Payment pending"}
                            </p>
                            <p
                              role="timer"
                              aria-label={
                                remaining > 0
                                  ? `Hold expires in ${minutes} minutes and ${seconds} seconds`
                                  : "Ticket hold expired"
                              }
                              className={`mt-2 inline-flex items-center gap-1.5 text-xs font-semibold ${isUrgent ? "text-destructive" : "text-muted-foreground"}`}
                            >
                              <Clock3 aria-hidden="true" className="size-3.5" />
                              {remaining > 0
                                ? `Hold expires in ${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`
                                : "Hold expired"}
                            </p>
                          </div>
                          <span className="text-sm font-semibold">
                            {new Intl.NumberFormat("en-US", {
                              style: "currency",
                              currency: order.currency,
                            }).format(order.totalAmount)}
                          </span>
                        </div>
                      </Link>
                    );
                  })}
                </div>
                <Link
                  href="/orders"
                  onClick={() => setDrawer(null)}
                  className="mt-auto inline-flex min-h-12 items-center justify-center rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground"
                >
                  View all carts
                </Link>
              </div>
            ) : (
              <div className="flex min-h-0 flex-1 flex-col p-6">
                {unreadCount > 0 ? (
                  <button
                    type="button"
                    onClick={() => markAllMutation.mutate()}
                    disabled={markAllMutation.isPending}
                    className="inline-flex min-h-9 items-center gap-2 self-end rounded-lg px-3 text-sm font-semibold text-primary hover:bg-muted disabled:opacity-50"
                  >
                    <CheckCheck aria-hidden="true" className="size-4" />
                    Mark all as read
                  </button>
                ) : null}
                {notificationsQuery.isPending ? (
                  <div
                    role="status"
                    aria-label="Loading notifications"
                    className="mt-4 space-y-3"
                  >
                    <div className="h-20 animate-pulse rounded-xl bg-muted" />
                    <div className="h-20 animate-pulse rounded-xl bg-muted" />
                  </div>
                ) : null}
                {notificationsQuery.isError ? (
                  <p
                    role="alert"
                    className="mt-4 rounded-xl border border-destructive/30 p-4 text-sm text-muted-foreground"
                  >
                    Could not load your notifications. Close this panel and try
                    again.
                  </p>
                ) : null}
                {notificationsQuery.data?.length === 0 ? (
                  <div className="flex flex-1 flex-col items-center justify-center text-center">
                    <Bell
                      aria-hidden="true"
                      className="size-7 text-muted-foreground"
                    />
                    <p className="mt-3 text-sm font-semibold">
                      No notifications
                    </p>
                    <p className="mt-1 text-sm text-muted-foreground">
                      You’re all caught up.
                    </p>
                  </div>
                ) : null}
                <ul className="mt-3 space-y-3 overflow-y-auto">
                  {notificationsQuery.data?.map((notification) => (
                    <li key={notification.id}>
                      <Link
                        href={notification.link ?? "/orders"}
                        onClick={() => {
                          if (!notification.read)
                            markReadMutation.mutate(notification.id);
                          setDrawer(null);
                        }}
                        className={`block rounded-xl border p-4 transition hover:border-primary/40 hover:bg-muted/40 ${notification.read ? "border-border bg-card" : "border-primary/30 bg-accent"}`}
                      >
                        <div className="flex items-start justify-between gap-3">
                          <h3 className="font-semibold">
                            {notification.title}
                          </h3>
                          {notification.read ? null : (
                            <span className="mt-1.5 size-2 shrink-0 rounded-full bg-primary">
                              <span className="sr-only">Unread</span>
                            </span>
                          )}
                        </div>
                        <p className="mt-1 text-sm text-muted-foreground">
                          {notification.body}
                        </p>
                        <p className="mt-2 text-xs text-muted-foreground">
                          <time dateTime={notification.createdAt}>
                            {notificationTime.format(
                              new Date(notification.createdAt),
                            )}
                          </time>
                        </p>
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </aside>
        </div>
      ) : null}
    </header>
  );
}

export function EventFooter() {
  return (
    <footer className="border-t border-border px-5 py-7 text-sm text-muted-foreground">
      <div className="mx-auto flex max-w-7xl flex-wrap justify-between gap-3">
        <span>© 2026 Vantix</span>
        <nav aria-label="Footer links" className="flex gap-5">
          <a href="#about" className="hover:text-foreground">
            About
          </a>
          <a href="#help" className="hover:text-foreground">
            Help
          </a>
          <a href="#terms" className="hover:text-foreground">
            Terms
          </a>
          <a href="#privacy" className="hover:text-foreground">
            Privacy
          </a>
        </nav>
      </div>
    </footer>
  );
}

export function EventDate({
  value,
  className = "",
}: {
  value: string;
  className?: string;
}) {
  const date = new Date(value);
  return (
    <time className={className} dateTime={value}>
      {new Intl.DateTimeFormat("en-US", {
        weekday: "short",
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      }).format(date)}
    </time>
  );
}
