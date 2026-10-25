// src/app/providers.tsx
"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ACCESS_DENIED_EVENT,
  SERVER_ERROR_EVENT,
  apiClient,
} from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";

function ApiErrorRedirector() {
  const router = useRouter();

  useEffect(() => {
    const redirectToForbidden = () => router.replace("/403");
    const redirectToServerError = () => router.replace("/500");
    window.addEventListener(ACCESS_DENIED_EVENT, redirectToForbidden);
    window.addEventListener(SERVER_ERROR_EVENT, redirectToServerError);
    return () => {
      window.removeEventListener(ACCESS_DENIED_EVENT, redirectToForbidden);
      window.removeEventListener(SERVER_ERROR_EVENT, redirectToServerError);
    };
  }, [router]);

  return null;
}

function AuthHydrator({ children }: { children: React.ReactNode }) {
  const { isHydrated, setHydrated, setSession } = useAuthStore();

  useEffect(() => {
    apiClient
      .post("/auth/refresh")
      .then((res) => {
        const { auth, user } = res.data.result;
        setSession(auth.accessToken, user);
      })
      .catch(() => {
        // Missing or expired cookies mean the visitor is signed out, not that the app failed.
      })
      .finally(() => setHydrated());
  }, [setHydrated, setSession]);

  // Wait for the auth state before rendering children to avoid a signed-out flash before refresh completes.
  if (!isHydrated) return null;

  return <>{children}</>;
}

export function Providers({ children }: { children: React.ReactNode }) {
  const [queryClient] = useState(() => new QueryClient());
  return (
    <QueryClientProvider client={queryClient}>
      <ApiErrorRedirector />
      <AuthHydrator>{children}</AuthHydrator>
    </QueryClientProvider>
  );
}
