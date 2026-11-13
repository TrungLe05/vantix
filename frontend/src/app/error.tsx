"use client";

import { ErrorScreen } from "@/components/errors/ErrorScreen";

export default function Error({
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return <ErrorScreen kind="server" onRetry={reset} showRetry />;
}
