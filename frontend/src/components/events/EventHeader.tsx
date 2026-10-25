"use client";

import { useState } from "react";
import Link from "next/link";
import { Bell, LayoutDashboard, ShoppingBag, UserRound, X } from "lucide-react";
import { useAuthStore } from "@/store/auth-store";

export function EventHeader({ showSignUp = false }: { showSignUp?: boolean }) {
  const user = useAuthStore((state) => state.user);
  const [drawer, setDrawer] = useState<"cart" | "notifications" | null>(null);

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
              className="border-b-2 border-primary py-[21px] text-sm font-semibold text-primary"
            >
              Discover
            </Link>
          </nav>
        </div>
        {user ? (
          <div className="flex min-w-0 items-center gap-2 sm:gap-3">
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
                <button type="button" onClick={() => setDrawer("cart")} aria-label="Open shopping cart" title="Shopping cart" className="relative inline-flex size-10 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
                  <ShoppingBag aria-hidden="true" className="size-5" />
                  <span className="absolute right-1 top-1 size-2 rounded-full bg-primary" />
                </button>
                <button type="button" onClick={() => setDrawer("notifications")} aria-label="Open notifications" title="Notifications" className="inline-flex size-10 items-center justify-center rounded-lg text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><Bell aria-hidden="true" className="size-5" /></button>
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
              Log in
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
        <div className="fixed inset-0 z-50 flex justify-end" role="presentation">
          <button type="button" aria-label="Close panel" onClick={() => setDrawer(null)} className="absolute inset-0 bg-foreground/40 backdrop-blur-sm" />
          <aside role="dialog" aria-modal="true" aria-labelledby="buyer-drawer-title" className="relative flex h-full w-full max-w-md animate-in slide-in-from-right flex-col border-l border-border bg-background shadow-2xl duration-300">
            <div className="flex items-center justify-between border-b border-border px-6 py-5"><div><h2 id="buyer-drawer-title" className="font-heading text-lg font-semibold">{drawer === "cart" ? "Your cart" : "Notifications"}</h2><p className="mt-1 text-xs text-muted-foreground">Preview data · checkout is not available yet</p></div><button type="button" onClick={() => setDrawer(null)} aria-label="Close" className="rounded-lg p-2 text-muted-foreground hover:bg-muted"><X className="size-5" aria-hidden="true" /></button></div>
            {drawer === "cart" ? (
              <div className="flex flex-1 flex-col p-6">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Sample cart item</p>
                <article className="mt-4 rounded-xl border border-border bg-card p-4 shadow-sm">
                  <h3 className="font-semibold">Design After Dark</h3>
                  <p className="mt-1 text-sm text-muted-foreground">General admission · Wed, Nov 19</p>
                  <div className="mt-4 flex items-center justify-between text-sm"><span>2 tickets</span><span className="font-semibold">$48.00</span></div>
                </article>
                <button type="button" disabled className="mt-auto min-h-12 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground opacity-60">Checkout coming soon</button>
              </div>
            ) : (
              <div className="flex-1 space-y-3 overflow-y-auto p-6">
                <p className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Sample notifications</p>
                <article className="rounded-xl border border-border bg-card p-4"><p className="font-semibold">Sales are open</p><p className="mt-1 text-sm leading-5 text-muted-foreground">Tickets for Design After Dark are now available.</p><time className="mt-3 block text-xs text-muted-foreground">Today · 9:00 AM</time></article>
                <article className="rounded-xl border border-border bg-card p-4"><p className="font-semibold">New event near you</p><p className="mt-1 text-sm leading-5 text-muted-foreground">A new live music event was added in your city.</p><time className="mt-3 block text-xs text-muted-foreground">Yesterday</time></article>
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
