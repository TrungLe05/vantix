"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
import { z } from "zod";
import { AuthCard } from "@/components/auth/AuthCard";
import { OtpInput } from "@/components/auth/OtpInput";
import { PasswordInput } from "@/components/auth/PasswordInput";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { getErrorMessage } from "@/lib/api/client";
import { requestPasswordReset, resetPassword } from "@/lib/api/auth";

const passwordSchema = z.string().min(8, "Mật khẩu cần ít nhất 8 ký tự").regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, "Mật khẩu cần có cả chữ cái và chữ số");

function ResetPasswordForm() {
  const params = useSearchParams();
  const router = useRouter();
  const email = params.get("email") ?? "";
  const [otp, setOtp] = useState("");
  const [password, setPassword] = useState("");
  const [passwordError, setPasswordError] = useState("");
  const mutation = useMutation({ mutationFn: () => resetPassword(email, otp, password), onSuccess: () => router.replace("/login?passwordReset=1") });
  const [secondsLeft, setSecondsLeft] = useState(60);
  const resend = useMutation({ mutationFn: () => requestPasswordReset(email), onSuccess: () => setSecondsLeft(60) });
  useEffect(() => {
    if (secondsLeft <= 0) return;
    const timer = window.setTimeout(() => setSecondsLeft((current) => current - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [secondsLeft]);
  const submit = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const result = passwordSchema.safeParse(password);
    if (!result.success) { setPasswordError(result.error.issues[0]?.message ?? "Mật khẩu không hợp lệ"); return; }
    setPasswordError("");
    mutation.mutate();
  };

  return <AuthCard>
    <h1 className="font-heading text-2xl font-bold tracking-tight">Kiểm tra email</h1>
    <p className="mt-2 text-sm leading-6 text-muted-foreground">Mã đặt lại gồm 6 chữ số đã được gửi đến <span className="font-medium text-foreground">{email || "email của bạn"}</span>.</p>
    {!email ? <p role="alert" className="mt-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive">Thiếu email. Hãy bắt đầu lại từ trang quên mật khẩu.</p> : null}
    <form onSubmit={submit} className="mt-7 space-y-6">
      <OtpInput value={otp} onChange={setOtp} />
      <div><Label htmlFor="new-password" className="mb-2 block font-semibold">Mật khẩu mới</Label><PasswordInput id="new-password" autoComplete="new-password" placeholder="Ít nhất 8 ký tự" value={password} onChange={(event) => setPassword(event.target.value)} /><p className="mt-2 text-xs text-muted-foreground">Cần có ít nhất 8 ký tự, gồm chữ cái và chữ số.</p>{passwordError ? <p role="alert" className="mt-1 text-sm text-destructive">{passwordError}</p> : null}</div>
      {mutation.isError ? <p role="alert" className="text-sm text-destructive">{getErrorMessage(mutation.error)}</p> : null}
      <Button type="submit" className="w-full" disabled={!email || otp.length !== 6 || mutation.isPending}>{mutation.isPending ? "Đang cập nhật…" : "Đặt lại mật khẩu"}</Button>
    </form>
    <div className="mt-6 text-center text-sm"><span className="text-muted-foreground">Chưa nhận được mã? </span><button type="button" disabled={!email || secondsLeft > 0 || resend.isPending} onClick={() => resend.mutate()} className="font-semibold text-primary disabled:cursor-not-allowed disabled:text-muted-foreground">{resend.isPending ? "Đang gửi…" : "Gửi lại"}</button>{secondsLeft > 0 ? <span className="ml-1 text-xs text-muted-foreground">({secondsLeft}s)</span> : null}</div>
    {resend.isError ? <p role="alert" className="mt-2 text-center text-sm text-destructive">{getErrorMessage(resend.error)}</p> : null}
    <Link href="/login" className="mt-6 inline-flex items-center gap-2 border-t border-border pt-5 text-sm font-semibold text-primary"><ArrowLeft aria-hidden="true" className="size-4" />Quay lại đăng nhập</Link>
  </AuthCard>;
}

export default function ResetPasswordPage() {
  return <Suspense fallback={<main className="min-h-screen animate-pulse bg-muted" />}><ResetPasswordForm /></Suspense>;
}
