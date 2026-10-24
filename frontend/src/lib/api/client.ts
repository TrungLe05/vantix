// src/lib/api/client.ts
import axios, { type InternalAxiosRequestConfig } from "axios";
import { useAuthStore } from "@/store/auth-store";

interface RetryableConfig extends InternalAxiosRequestConfig {
  _retried?: boolean;
}

export const ACCESS_DENIED_EVENT = "vantix:access-denied";
export const SERVER_ERROR_EVENT = "vantix:server-error";

export const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
  withCredentials: true,
});

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  if (!refreshPromise) {
    refreshPromise = apiClient
      .post("/auth/refresh")
      .then((res) => {
        const { auth, user } = res.data.result;
        useAuthStore.getState().setSession(auth.accessToken, user);
        return auth.accessToken as string;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

apiClient.interceptors.response.use(
  (res) => res,
  async (error) => {
    const config = error.config as RetryableConfig;
    const errorCode = error.response?.data?.code;

    if (errorCode === 1004 && typeof window !== "undefined") {
      window.dispatchEvent(new Event(ACCESS_DENIED_EVENT));
    }
    if (
      typeof window !== "undefined" &&
      (errorCode === 1000 || (error.response?.status ?? 0) >= 500)
    ) {
      window.dispatchEvent(new Event(SERVER_ERROR_EVENT));
    }

    if (errorCode === 9003 && !config._retried) {
      config._retried = true;
      try {
        const newToken = await refreshAccessToken();
        config.headers.Authorization = `Bearer ${newToken}`;
        return apiClient.request(config);
      } catch {
        useAuthStore.getState().clear();
      }
    }

    return Promise.reject(error);
  },
);

/** Đọc message lỗi theo đúng convention mục 8 của P0 — luôn ưu tiên message backend trả về. */
export function getErrorMessage(
  error: unknown,
  fallback = "Đã có lỗi xảy ra, vui lòng thử lại",
): string {
  if (axios.isAxiosError(error) && error.response?.data?.message) {
    return error.response.data.message as string;
  }
  return fallback;
}
