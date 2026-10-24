"use client";

import { useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import axios from "axios";
import { useMutation, useQuery } from "@tanstack/react-query";
import {
  ArrowLeft,
  BadgeCheck,
  CircleUserRound,
  KeyRound,
  LoaderCircle,
  LockKeyhole,
  Mail,
  ShieldCheck,
  UserRound,
} from "lucide-react";
import { EventFooter, EventHeader } from "@/components/events/EventHeader";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { changePassword, getCurrentUser } from "@/lib/api/auth";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";
import type { UserResponse } from "@/types/auth";
import type { ApiResponse } from "@/types/common";

const roleLabels: Record<UserResponse["role"], string> = {
  BUYER: "Người mua",
  ADMIN: "Quản trị viên",
  STAFF: "Nhân viên",
};

const statusLabels: Record<UserResponse["status"], string> = {
  ACTIVE: "Đang hoạt động",
  PENDING_VERIFICATION: "Chờ xác thực email",
  LOCKED: "Đã khóa",
};

function initials(fullName: string) {
  return fullName
    .trim()
    .split(/\s+/)
    .slice(-2)
    .map((part) => part[0])
    .join("")
    .toLocaleUpperCase("vi");
}

export default function ProfilePage() {
  const router = useRouter();
  const clearSession = useAuthStore((state) => state.clear);
  const [isPasswordDialogOpen, setPasswordDialogOpen] = useState(false);
  const [oldPassword, setOldPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [validationMessage, setValidationMessage] = useState<string | null>(null);
  const {
    data: user,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ["current-user"],
    queryFn: getCurrentUser,
  });

  const passwordMutation = useMutation({
    mutationFn: () => changePassword(oldPassword, newPassword),
    onSuccess: () => {
      clearSession();
      router.replace("/login?passwordChanged=1");
    },
  });
  const passwordErrorCode = axios.isAxiosError<ApiResponse<unknown>>(
    passwordMutation.error,
  )
    ? passwordMutation.error.response?.data?.code
    : undefined;

  function submitPasswordChange(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setValidationMessage(null);
    if (newPassword !== confirmPassword) {
      setValidationMessage("Mật khẩu xác nhận chưa khớp.");
      return;
    }
    if (
      newPassword.length < 8 ||
      newPassword.length > 64 ||
      !/(?=.*[A-Za-z])(?=.*\d)/.test(newPassword)
    ) {
      setValidationMessage("Mật khẩu mới cần có 8–64 ký tự, gồm ít nhất một chữ cái và một chữ số.");
      return;
    }
    passwordMutation.mutate();
  }

  function closePasswordDialog(open: boolean) {
    setPasswordDialogOpen(open);
    if (!open) {
      setOldPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setValidationMessage(null);
      passwordMutation.reset();
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-muted/40 text-foreground">
      <EventHeader />
      <main className="mx-auto w-full max-w-6xl flex-1 px-5 py-8 md:px-8 md:py-12">
        <Link
          href="/"
          className="inline-flex items-center gap-2 text-sm font-medium text-muted-foreground hover:text-foreground"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
          Về trang khám phá
        </Link>
        <div className="mt-7 grid gap-8 lg:grid-cols-[230px_minmax(0,1fr)]">
          <aside className="h-fit rounded-2xl border border-border bg-card p-3 shadow-sm">
            <p className="px-3 pb-3 pt-2 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
              Tài khoản
            </p>
            <a
              href="#profile-details"
              className="flex min-h-11 items-center gap-3 rounded-xl bg-accent px-3 text-sm font-semibold text-accent-foreground"
            >
              <CircleUserRound aria-hidden="true" className="size-4" />
              Hồ sơ của tôi
            </a>
            <a
              href="#security"
              className="mt-1 flex min-h-11 items-center gap-3 rounded-xl px-3 text-sm font-medium text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <LockKeyhole aria-hidden="true" className="size-4" />
              Bảo mật
            </a>
          </aside>

          <div className="min-w-0">
            <div className="mb-6">
              <p className="text-sm font-semibold text-primary">VANTIX ACCOUNT</p>
              <h1 className="mt-2 font-heading text-3xl font-bold tracking-tight sm:text-4xl">
                Hồ sơ của tôi
              </h1>
              <p className="mt-2 text-sm leading-6 text-muted-foreground">
                Xem thông tin tài khoản và quản lý thiết lập bảo mật.
              </p>
            </div>

            {isPending ? (
              <div
                aria-label="Đang tải hồ sơ"
                className="space-y-5 rounded-2xl border border-border bg-card p-6 shadow-sm"
              >
                <div className="flex items-center gap-4">
                  <div className="size-16 animate-pulse rounded-full bg-muted" />
                  <div className="space-y-2">
                    <div className="h-5 w-40 animate-pulse rounded bg-muted" />
                    <div className="h-4 w-52 animate-pulse rounded bg-muted" />
                  </div>
                </div>
                <div className="h-28 animate-pulse rounded-xl bg-muted" />
              </div>
            ) : null}

            {isError ? (
              <div
                role="alert"
                className="rounded-2xl border border-destructive/30 bg-card p-6 shadow-sm"
              >
                <h2 className="font-heading text-lg font-semibold">
                  Không tải được hồ sơ
                </h2>
                <p className="mt-2 text-sm text-muted-foreground">
                  {getErrorMessage(error)}
                </p>
                <div className="mt-5 flex flex-wrap gap-3">
                  <button
                    type="button"
                    onClick={() => refetch()}
                    className="min-h-10 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90"
                  >
                    Thử lại
                  </button>
                  <Link
                    href="/login"
                    className="inline-flex min-h-10 items-center rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted"
                  >
                    Đăng nhập
                  </Link>
                </div>
              </div>
            ) : null}

            {user ? (
              <div className="space-y-5">
                <section
                  id="profile-details"
                  className="scroll-mt-6 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7"
                >
                  <div className="flex flex-col gap-5 border-b border-border pb-6 sm:flex-row sm:items-center">
                    <div className="flex size-16 shrink-0 items-center justify-center rounded-full bg-accent font-heading text-xl font-bold text-accent-foreground">
                      {initials(user.fullName)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <h2 className="font-heading text-xl font-bold">
                          {user.fullName}
                        </h2>
                        <span className="inline-flex items-center gap-1 rounded-full bg-accent px-2.5 py-1 text-xs font-semibold text-accent-foreground">
                          <BadgeCheck aria-hidden="true" className="size-3.5" />
                          {roleLabels[user.role]}
                        </span>
                      </div>
                      <p className="mt-1 truncate text-sm text-muted-foreground">
                        {user.email}
                      </p>
                    </div>
                  </div>

                  <div className="grid gap-x-8 sm:grid-cols-2">
                    <div className="border-b border-border py-5 sm:border-b-0">
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        Họ và tên
                      </p>
                      <p className="mt-2 flex items-center gap-2 text-sm font-medium">
                        <UserRound aria-hidden="true" className="size-4 text-muted-foreground" />
                        {user.fullName}
                      </p>
                    </div>
                    <div className="border-b border-border py-5 sm:border-b-0">
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        Địa chỉ email
                      </p>
                      <p className="mt-2 flex items-center gap-2 break-all text-sm font-medium">
                        <Mail aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                        {user.email}
                      </p>
                    </div>
                    <div className="border-b border-border py-5 sm:border-b-0 sm:border-t sm:border-border">
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        Trạng thái tài khoản
                      </p>
                      <p className="mt-2 flex items-center gap-2 text-sm font-medium">
                        <ShieldCheck aria-hidden="true" className="size-4 text-primary" />
                        {statusLabels[user.status]}
                      </p>
                    </div>
                    <div className="py-5 sm:border-t sm:border-border">
                      <p className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
                        Phương thức đăng nhập
                      </p>
                      <p className="mt-2 flex items-center gap-2 text-sm font-medium">
                        {user.oauthProvider === "GOOGLE" ? (
                          <>
                            <span aria-hidden="true" className="flex size-5 items-center justify-center rounded-full border text-xs font-bold">G</span>
                            Tài khoản Google
                          </>
                        ) : (
                          <>
                            <KeyRound aria-hidden="true" className="size-4 text-muted-foreground" />
                            Email và mật khẩu
                          </>
                        )}
                      </p>
                    </div>
                  </div>
                </section>

                <section
                  id="security"
                  className="scroll-mt-6 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7"
                >
                  <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                    <div>
                      <h2 className="font-heading text-lg font-semibold">Bảo mật</h2>
                      <p className="mt-1 max-w-xl text-sm leading-6 text-muted-foreground">
                        {user.oauthProvider === "GOOGLE"
                          ? "Mật khẩu được quản lý bởi Google. Bạn có thể đặt mật khẩu Vantix qua chức năng quên mật khẩu."
                          : "Đổi mật khẩu định kỳ để giữ an toàn cho tài khoản của bạn."}
                      </p>
                    </div>
                    {user.oauthProvider === "GOOGLE" ? (
                      <Link
                        href="/forgot-password"
                        className="inline-flex min-h-10 shrink-0 items-center justify-center rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted"
                      >
                        Đặt mật khẩu Vantix
                      </Link>
                    ) : (
                      <button
                        type="button"
                        onClick={() => setPasswordDialogOpen(true)}
                        className="inline-flex min-h-10 shrink-0 items-center justify-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground hover:opacity-90"
                      >
                        <LockKeyhole aria-hidden="true" className="size-4" />
                        Đổi mật khẩu
                      </button>
                    )}
                  </div>
                </section>
              </div>
            ) : null}
          </div>
        </div>
      </main>
      <EventFooter />

      <Dialog open={isPasswordDialogOpen} onOpenChange={closePasswordDialog}>
        <DialogContent className="max-h-[calc(100dvh-2rem)] overflow-y-auto sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>Đổi mật khẩu</DialogTitle>
            <DialogDescription>
              Nhập mật khẩu hiện tại và chọn mật khẩu mới cho tài khoản.
            </DialogDescription>
          </DialogHeader>
          <form onSubmit={submitPasswordChange} className="space-y-4">
            <label className="block space-y-1.5 text-sm font-medium">
              Mật khẩu hiện tại
              <input
                autoComplete="current-password"
                required
                type="password"
                value={oldPassword}
                onChange={(event) => setOldPassword(event.target.value)}
                className="min-h-11 w-full rounded-lg border border-input bg-background px-3 outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </label>
            <label className="block space-y-1.5 text-sm font-medium">
              Mật khẩu mới
              <input
                autoComplete="new-password"
                required
                type="password"
                value={newPassword}
                onChange={(event) => setNewPassword(event.target.value)}
                className="min-h-11 w-full rounded-lg border border-input bg-background px-3 outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
              <span className="block text-xs font-normal text-muted-foreground">
                8–64 ký tự, gồm ít nhất một chữ cái và một chữ số.
              </span>
            </label>
            <label className="block space-y-1.5 text-sm font-medium">
              Xác nhận mật khẩu mới
              <input
                autoComplete="new-password"
                required
                type="password"
                value={confirmPassword}
                onChange={(event) => setConfirmPassword(event.target.value)}
                className="min-h-11 w-full rounded-lg border border-input bg-background px-3 outline-none focus-visible:ring-2 focus-visible:ring-ring"
              />
            </label>
            {validationMessage ? (
              <p role="alert" className="text-sm text-destructive">
                {validationMessage}
              </p>
            ) : null}
            {passwordMutation.isError ? (
              <div role="alert" className="space-y-1 text-sm text-destructive">
                <p>{getErrorMessage(passwordMutation.error)}</p>
                {passwordErrorCode === 2403 ? (
                  <Link className="font-semibold underline underline-offset-4" href="/forgot-password">
                    Đặt mật khẩu bằng email
                  </Link>
                ) : null}
              </div>
            ) : null}
            <DialogFooter>
              <button
                type="button"
                onClick={() => closePasswordDialog(false)}
                className="inline-flex min-h-10 items-center justify-center rounded-lg border border-border px-4 text-sm font-semibold hover:bg-muted"
              >
                Hủy
              </button>
              <button
                type="submit"
                disabled={passwordMutation.isPending}
                className="inline-flex min-h-10 items-center justify-center gap-2 rounded-lg bg-primary px-4 text-sm font-semibold text-primary-foreground disabled:opacity-60"
              >
                {passwordMutation.isPending ? (
                  <LoaderCircle aria-hidden="true" className="size-4 animate-spin" />
                ) : null}
                {passwordMutation.isPending ? "Đang cập nhật…" : "Cập nhật mật khẩu"}
              </button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  );
}
