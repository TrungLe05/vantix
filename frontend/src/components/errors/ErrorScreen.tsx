"use client";

import Link from "next/link";
import { ArrowLeft, CircleAlert, Home, RefreshCw, ShieldX } from "lucide-react";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";

type ErrorKind = "not-found" | "forbidden" | "server";

const errorContent: Record<
  ErrorKind,
  { code: string; title: string; description: string; icon: typeof CircleAlert }
> = {
  "not-found": {
    code: "404",
    title: "Không tìm thấy trang",
    description: "Trang bạn đang tìm không tồn tại hoặc đã được chuyển đi.",
    icon: CircleAlert,
  },
  forbidden: {
    code: "403",
    title: "Bạn không thể truy cập trang này",
    description: "Khu vực này chỉ dành cho tài khoản có quyền quản trị.",
    icon: ShieldX,
  },
  server: {
    code: "500",
    title: "Đã xảy ra sự cố",
    description: "Hệ thống đang gặp lỗi. Vui lòng thử lại sau ít phút.",
    icon: CircleAlert,
  },
};

export function ErrorScreen({
  kind,
  onRetry,
  showRetry = false,
}: {
  kind: ErrorKind;
  onRetry?: () => void;
  showRetry?: boolean;
}) {
  const content = errorContent[kind];
  const Icon = content.icon;

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <EventHeader />
      <main className="flex flex-1 items-center justify-center px-5 py-16 text-center">
        <section className="flex w-full max-w-xl flex-col items-center">
          <div className="flex size-20 items-center justify-center rounded-full bg-muted text-primary">
            <Icon aria-hidden="true" className="size-9" />
          </div>
          <p className="mt-7 font-heading text-7xl font-bold tracking-tight text-primary sm:text-8xl">
            {content.code}
          </p>
          <h1 className="mt-5 font-heading text-2xl font-bold tracking-tight sm:text-3xl">
            {content.title}
          </h1>
          <p className="mt-3 max-w-md text-sm leading-6 text-muted-foreground sm:text-base">
            {content.description}
          </p>
          <div className="mt-8 flex w-full flex-col justify-center gap-3 sm:w-auto sm:flex-row">
            {showRetry ? (
              <button
                type="button"
                onClick={onRetry ?? (() => window.location.reload())}
                className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-primary px-6 text-sm font-semibold text-primary-foreground shadow-sm hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                <RefreshCw aria-hidden="true" className="size-4" />
                Thử tải lại
              </button>
            ) : null}
            <Link
              href="/"
              className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg border border-border bg-background px-6 text-sm font-semibold hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            >
              {kind === "not-found" ? (
                <ArrowLeft aria-hidden="true" className="size-4" />
              ) : (
                <Home aria-hidden="true" className="size-4" />
              )}
              Về trang khám phá
            </Link>
          </div>
        </section>
      </main>
      <EventFooter />
    </div>
  );
}
