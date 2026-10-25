"use client";

import Link from "next/link";
import { ArrowLeft, Home, RefreshCw } from "lucide-react";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";

type ErrorKind = "not-found" | "forbidden" | "server";

const errorContent: Record<
  ErrorKind,
  { code: string; title: string; description: string }
> = {
  "not-found": {
    code: "404",
    title: "Page not found",
    description: "The page you are looking for does not exist or has been moved.",
  },
  forbidden: {
    code: "403",
    title: "You cannot access this page",
    description: "This area is available to administrator accounts only.",
  },
  server: {
    code: "500",
    title: "Something went wrong",
    description: "The system encountered an error. Please try again in a few minutes.",
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

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <a href="#error-content" className="sr-only z-50 rounded-lg bg-background px-4 py-3 focus:not-sr-only focus:fixed focus:left-4 focus:top-4 focus-visible:ring-2 focus-visible:ring-ring">
        Skip navigation
      </a>
      <EventHeader showSignUp />
      <main id="error-content" className="flex flex-1 items-center justify-center px-5 py-16 text-center">
        <section className="flex w-full max-w-xl flex-col items-center">
          <p className="font-heading text-8xl font-bold leading-none tracking-tight text-border sm:text-9xl">
            {content.code}
          </p>
          <h1 className="mt-5 font-heading text-2xl font-bold tracking-tight sm:text-3xl">
            {content.title}
          </h1>
          <p className="mt-3 max-w-md text-pretty text-sm leading-6 text-muted-foreground sm:text-base">
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
                Try again
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
              Back to Discover
            </Link>
          </div>
        </section>
      </main>
      <EventFooter />
    </div>
  );
}
