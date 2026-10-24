"use client";

import { useState } from "react";
import Link from "next/link";
import { LogOut, Search, UserRound } from "lucide-react";
import { logout } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth-store";

export function EventHeader() {
  const user = useAuthStore((state) => state.user);
  const clearSession = useAuthStore((state) => state.clear);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState(false);

  async function handleLogout() {
    setIsLoggingOut(true);
    setLogoutError(false);
    try {
      await logout();
      clearSession();
    } catch {
      setLogoutError(true);
    } finally {
      setIsLoggingOut(false);
    }
  }

  return (
    <header className="border-b border-border bg-background">
      <div className="mx-auto flex w-full max-w-7xl items-center gap-4 px-5 py-4 md:px-8">
        <Link href="/" className="font-heading text-2xl font-bold tracking-tight">Vantix<span className="text-primary">.</span></Link>
        <div className="hidden flex-1 justify-center md:flex">
          <Link href="/#events" className="flex w-full max-w-md items-center gap-3 rounded-xl border border-input px-4 py-3 text-sm text-muted-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"><Search aria-hidden="true" className="size-4" /><span>Tìm sự kiện</span></Link>
        </div>
        {user ? (
          <div className="ml-auto flex min-w-0 items-center gap-3">
            <Link
              href="/profile"
              className="inline-flex max-w-48 items-center gap-2 truncate rounded-lg px-2 py-2 text-sm font-semibold hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              aria-label="Mở hồ sơ tài khoản"
              title={user.fullName}
            >
              <UserRound aria-hidden="true" className="size-4 shrink-0 text-primary" />
              <span className="hidden truncate sm:inline">Tài khoản</span>
            </Link>
            <button
              type="button"
              onClick={handleLogout}
              disabled={isLoggingOut}
              className="inline-flex min-h-10 items-center gap-2 rounded-lg border border-border px-3 text-sm font-semibold hover:bg-muted disabled:cursor-wait disabled:opacity-60"
            >
              <LogOut aria-hidden="true" className="size-4" />
              <span>{isLoggingOut ? "Đang đăng xuất…" : "Đăng xuất"}</span>
            </button>
          </div>
        ) : (
          <Link href="/login" className="ml-auto rounded-lg border border-border px-4 py-2.5 text-sm font-semibold hover:bg-muted">
            Đăng nhập
          </Link>
        )}
      </div>
      {logoutError ? (
        <p role="alert" className="mx-auto max-w-7xl px-5 pb-3 text-right text-xs text-destructive md:px-8">
          Không thể đăng xuất. Vui lòng thử lại.
        </p>
      ) : null}
    </header>
  );
}

export function EventFooter() {
  return <footer className="border-t border-border px-5 py-7 text-sm text-muted-foreground"><div className="mx-auto flex max-w-7xl flex-wrap justify-between gap-3"><span>© 2026 Vantix</span><nav aria-label="Liên kết chân trang" className="flex gap-5"><a href="#about" className="hover:text-foreground">Giới thiệu</a><a href="#help" className="hover:text-foreground">Trợ giúp</a><a href="#terms" className="hover:text-foreground">Điều khoản</a><a href="#privacy" className="hover:text-foreground">Quyền riêng tư</a></nav></div></footer>;
}

export function EventDate({ value, className = "" }: { value: string; className?: string }) {
  const date = new Date(value);
  return <time className={className} dateTime={value}>{new Intl.DateTimeFormat("vi-VN", { weekday: "short", day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }).format(date)}</time>;
}
