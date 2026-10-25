"use client";

import { useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { CalendarDays, ChevronDown, LogOut, MapPin } from "lucide-react";
import { logout } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth-store";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

const adminLinks = [
  { href: "/admin/venues", label: "Venues", icon: MapPin },
  { href: "/admin/events", label: "Events", icon: CalendarDays },
] as const;

export default function AdminLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const user = useAuthStore((state) => state.user);
  const clearSession = useAuthStore((state) => state.clear);
  const [logoutError, setLogoutError] = useState(false);
  const [isLoggingOut, setIsLoggingOut] = useState(false);
  const initials = user?.fullName.trim().split(/\s+/).slice(-2).map((part) => part[0]).join("").toLocaleUpperCase("en") || "AD";

  useEffect(() => {
    if (!user) {
      router.replace("/login");
    } else if (user.role !== "ADMIN") {
      router.replace("/403");
    }
  }, [router, user]);

  async function handleLogout() {
    setLogoutError(false);
    setIsLoggingOut(true);
    try {
      await logout();
      clearSession();
      router.replace("/login");
    } catch {
      setLogoutError(true);
    } finally {
      setIsLoggingOut(false);
    }
  }

  if (user?.role !== "ADMIN") {
    return (
      <main className="flex min-h-screen items-center justify-center bg-background px-5">
        <p
          role="status"
          aria-live="polite"
          className="text-sm text-muted-foreground"
        >
          Checking access…
        </p>
      </main>
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <a
        href="#admin-content"
        className="sr-only z-60 rounded-lg bg-background px-4 py-3 focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus-visible:ring-2 focus-visible:ring-ring"
      >
        Skip navigation
      </a>
      <header className="sticky top-0 z-40 flex h-16 items-center justify-between border-b border-border bg-background px-5 md:px-8">
        <div className="flex items-center gap-8">
          <Link
            href="/"
            className="font-heading text-xl font-bold tracking-tight"
          >
            Vantix
            <span className="ml-2 bg-primary/10 text-primary text-xs font-semibold px-2 py-0.5 rounded-full">
              Admin
            </span>
          </Link>
          <nav
            aria-label="Main navigation"
            className="hidden items-center gap-5 md:flex"
          >
            <Link
              href="/"
              className="text-sm text-muted-foreground hover:text-foreground"
            >
              Discover
            </Link>
            <Link
              href="/admin/events"
              className="text-sm font-semibold text-primary border-b-primary py-2 border-b-2"
            >
              Admin
            </Link>
          </nav>
        </div>
        <div className="flex items-center gap-2 sm:gap-4">
          <DropdownMenu>
            <DropdownMenuTrigger className="inline-flex min-h-10 items-center gap-2 rounded-full py-1 pl-1 pr-2.5 text-sm font-medium hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
              <span aria-hidden="true" className="flex size-8 items-center justify-center rounded-full bg-primary/15 text-xs font-bold text-primary">{initials}</span>
              <span className="hidden max-w-40 truncate sm:inline">{user.fullName}</span>
              <ChevronDown aria-hidden="true" className="size-4 text-muted-foreground" />
              <span className="sr-only">Open account menu</span>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="end" className="w-56 rounded-xl p-1.5 shadow-lg">
              <DropdownMenuItem onClick={() => router.push("/profile")} className="min-h-10 rounded-lg">My profile</DropdownMenuItem>
              <DropdownMenuItem onClick={handleLogout} disabled={isLoggingOut} className="min-h-10 rounded-lg text-destructive focus:text-destructive"><LogOut aria-hidden="true" className="size-4" />{isLoggingOut ? "Signing out…" : "Sign out"}</DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </header>
      {logoutError ? (
        <p
          role="alert"
          aria-live="polite"
          className="border-b border-destructive/20 bg-destructive/5 px-5 py-3 text-center text-sm text-destructive"
        >
          Could not sign out. Please try again.
        </p>
      ) : null}
      <div className="flex flex-1 flex-col lg:flex-row">
        <aside className="shrink-0 border-b border-border bg-background p-3 lg:w-60 lg:border-b-0 lg:border-r lg:p-4">
          <p className="mb-3 hidden px-3 text-xs font-bold uppercase tracking-wider text-muted-foreground lg:block">
            Admin
          </p>
          <nav
            aria-label="Admin menu"
            className="flex gap-1 overflow-x-auto lg:flex-col"
          >
            {adminLinks.map(({ href, label, icon: Icon }) => {
              const isActive =
                pathname === href || pathname.startsWith(`${href}/`);
              return (
                <Link
                  key={href}
                  href={href}
                  aria-current={isActive ? "page" : undefined}
                  className={`inline-flex min-h-11 shrink-0 items-center gap-3 rounded-lg px-3 text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
                    isActive
                      ? "bg-accent text-accent-foreground"
                      : "text-muted-foreground hover:bg-muted hover:text-foreground"
                  }`}
                >
                  <Icon aria-hidden="true" className="size-4" />
                  {label}
                </Link>
              );
            })}
          </nav>
        </aside>
        <main
          id="admin-content"
          className="min-w-0 flex-1 bg-muted/50 p-5 md:p-8"
        >
          {children}
        </main>
      </div>
    </div>
  );
}
