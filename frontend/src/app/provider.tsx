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
        // Không có cookie hợp
        // lệ (chưa đăng nhập, hoặc đã hết hạn) — coi như khách vãng lai, không phải lỗi
      })
      .finally(() => setHydrated());
  }, [setHydrated, setSession]);

  // Chặn render children cho tới khi biết chắc trạng thái đăng nhập — tránh nháy UI
  // "chưa đăng nhập" rồi mới nhảy sang "đã đăng nhập" ngay sau đó
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
