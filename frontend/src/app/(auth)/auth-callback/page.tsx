"use client";

import { Suspense, useEffect, useMemo, useRef, useState } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import axios from "axios";
import { ArrowLeft, CircleAlert, LockKeyhole, LoaderCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { GoogleButton } from "@/components/auth/GoogleButton";
import { exchangeOAuthCode } from "@/lib/api/auth";
import { useAuthStore } from "@/store/auth-store";

type CallbackState =
  | { status: "loading" }
  | { status: "error"; kind: "failed" | "locked"; message: string };

const OAUTH_ERROR_MESSAGES: Record<number, string> = {
  2502: "Không thể hoàn tất đăng nhập Google. Vui lòng thử lại.",
  2504: "Email Google của bạn chưa được xác minh.",
  2505: "Email này đã được liên kết với một tài khoản Google khác.",
  2506: "Liên kết đăng nhập đã hết hạn hoặc đã được sử dụng. Vui lòng thử lại.",
};

function getExchangeError(error: unknown) {
  if (!axios.isAxiosError<{ code?: number; message?: string }>(error)) {
    return { kind: "failed" as const, message: "Đăng nhập Google không thành công. Vui lòng thử lại." };
  }

  const code = error.response?.data?.code;
  if (code === 2103) {
    return {
      kind: "locked" as const,
      message: "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên để được hỗ trợ.",
    };
  }

  return {
    kind: "failed" as const,
    message:
      error.response?.data?.message ??
      (typeof code === "number" ? OAUTH_ERROR_MESSAGES[code] : undefined) ??
      "Đăng nhập Google không thành công. Vui lòng thử lại.",
  };
}

export default function GoogleAuthCallbackPage() {
  return (
    <Suspense fallback={<CallbackScreen state={{ status: "loading" }} />}>
      <GoogleAuthCallbackContent />
    </Suspense>
  );
}

function GoogleAuthCallbackContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [callbackParams] = useState(() => ({
    code: searchParams.get("code"),
    errorCode: searchParams.get("error"),
  }));
  const { code, errorCode } = callbackParams;
  const setSession = useAuthStore((state) => state.setSession);
  const started = useRef(false);
  const [state, setState] = useState<CallbackState>({ status: "loading" });
  const queryError = useMemo(() => getQueryError(code, errorCode), [code, errorCode]);

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    // Remove the short-lived credential from the address bar and browser history.
    window.history.replaceState(window.history.state, "", window.location.pathname);

    if (queryError || !code) return;

    void exchangeOAuthCode(code)
      .then(({ auth, user }) => {
        setSession(auth.accessToken, user);
        router.replace(user.mustChangePassword ? "/change-password" : "/");
      })
      .catch((error: unknown) => {
        setState({ status: "error", ...getExchangeError(error) });
      });
  }, [code, queryError, router, setSession]);

  useEffect(() => {
    if (queryError?.status !== "error" && state.status !== "error") return;
    const timeout = window.setTimeout(() => router.replace("/login"), 7000);
    return () => window.clearTimeout(timeout);
  }, [queryError, router, state.status]);

  return <CallbackScreen state={queryError ?? state} />;
}

function getQueryError(code: string | null, errorCode: string | null): CallbackState | undefined {
  if (errorCode) {
    const numericCode = Number(errorCode);
    if (numericCode === 2103) {
      return {
        status: "error",
        kind: "locked",
        message: "Tài khoản của bạn đã bị khóa. Vui lòng liên hệ quản trị viên để được hỗ trợ.",
      };
    }

    return {
      status: "error",
      kind: "failed",
      message:
        OAUTH_ERROR_MESSAGES[numericCode] ??
        "Không thể hoàn tất đăng nhập Google. Vui lòng thử lại.",
    };
  }

  if (!code) {
    return {
      status: "error",
      kind: "failed",
      message: "Liên kết đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng thử lại.",
    };
  }

  return undefined;
}

function CallbackScreen({ state }: { state: CallbackState }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-5 py-12 text-foreground">
      <Link href="/" className="mb-8 text-2xl font-bold tracking-tight">
        Vantix<span className="text-primary">.</span>
      </Link>

      <section className="w-full max-w-[440px] rounded-2xl border border-border bg-card p-8 text-center shadow-md sm:p-10">
        {state.status === "loading" ? (
          <div role="status" aria-live="polite" className="flex flex-col items-center">
            <span className="mb-6 flex size-16 items-center justify-center rounded-full bg-primary/10 text-primary">
              <LoaderCircle className="size-8 animate-spin" aria-hidden="true" />
            </span>
            <h1 className="font-heading text-2xl font-semibold tracking-tight">
              Đang hoàn tất đăng nhập
            </h1>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">
              Vui lòng đợi trong khi chúng tôi xác minh tài khoản Google của bạn.
            </p>
            <p className="mt-6 text-xs text-muted-foreground">Kết nối bảo mật OAuth 2.0</p>
          </div>
        ) : state.kind === "locked" ? (
          <div role="alert" className="flex flex-col items-center">
            <span className="mb-6 flex size-16 items-center justify-center rounded-full bg-destructive/10 text-destructive">
              <LockKeyhole className="size-8" aria-hidden="true" />
            </span>
            <h1 className="font-heading text-2xl font-semibold tracking-tight">
              Tài khoản bị khóa
            </h1>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">{state.message}</p>
            <Button asChild className="mt-8 w-full">
              <Link href="/login">
                <ArrowLeft className="mr-2 size-4" aria-hidden="true" />
                Quay lại đăng nhập
              </Link>
            </Button>
          </div>
        ) : (
          <div role="alert" className="flex flex-col items-center">
            <span className="mb-6 flex size-16 items-center justify-center rounded-full bg-destructive/10 text-destructive">
              <CircleAlert className="size-8" aria-hidden="true" />
            </span>
            <h1 className="font-heading text-2xl font-semibold tracking-tight">
              Đăng nhập Google không thành công
            </h1>
            <p className="mt-3 text-sm leading-6 text-muted-foreground">{state.message}</p>
            <div className="mt-8 w-full space-y-3">
              <GoogleButton />
              <Button asChild variant="outline" className="w-full">
                <Link href="/login">
                  <ArrowLeft className="mr-2 size-4" aria-hidden="true" />
                  Quay lại đăng nhập
                </Link>
              </Button>
            </div>
          </div>
        )}
      </section>

      <p className="mt-8 text-center text-xs text-muted-foreground">
        © 2026 Vantix. All rights reserved.
      </p>
    </main>
  );
}
