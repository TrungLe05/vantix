// src/lib/api/auth.ts
import { apiClient } from "./client";
import type { LoginResponse, UserResponse } from "@/types/auth";
import type { ApiResponse } from "@/types/common";

export async function login(email: string, password: string) {
  const res = await apiClient.post<{ result: LoginResponse }>("/auth/login", {
    email,
    password,
  });
  return res.data.result;
}

export async function exchangeOAuthCode(code: string) {
  const res = await apiClient.post<ApiResponse<LoginResponse>>(
    "/auth/oauth2/exchange",
    { code },
  );
  if (!res.data.result) {
    throw new Error("Không nhận được phiên đăng nhập Google");
  }
  return res.data.result;
}

export async function logout() {
  await apiClient.post<ApiResponse<void>>("/auth/logout");
}

export async function getCurrentUser() {
  const res = await apiClient.get<ApiResponse<UserResponse>>("/users/me");
  if (!res.data.result) {
    throw new Error("Không nhận được thông tin tài khoản");
  }
  return res.data.result;
}

export async function changePassword(oldPassword: string, newPassword: string) {
  const res = await apiClient.patch<ApiResponse<void>>("/auth/change-password", {
    oldPassword,
    newPassword,
  });
  return res.data.message;
}

export async function register(
  email: string,
  password: string,
  fullName: string,
) {
  const res = await apiClient.post<ApiResponse<{ email: string }>>(
    "/auth/register",
    { email, password, fullName },
  );
  return res.data.result;
}

export async function verifyEmail(email: string, otp: string) {
  const res = await apiClient.post<ApiResponse<void>>("/auth/verify-email", {
    email,
    otp,
  });
  return res.data.message;
}

export async function resendVerification(email: string) {
  const res = await apiClient.post<ApiResponse<void>>(
    "/auth/resend-verification",
    { email },
  );
  return res.data.message;
}

export async function requestPasswordReset(email: string) {
  const res = await apiClient.post<ApiResponse<void>>("/auth/forgot-password", {
    email,
  });
  return res.data.message;
}

export async function resetPassword(
  email: string,
  otp: string,
  newPassword: string,
) {
  const res = await apiClient.post<ApiResponse<void>>("/auth/reset-password", {
    email,
    otp,
    newPassword,
  });
  return res.data.message;
}
