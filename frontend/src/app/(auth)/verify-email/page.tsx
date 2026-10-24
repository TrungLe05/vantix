"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useMutation } from "@tanstack/react-query";
import { ArrowLeft, CheckCircle2 } from "lucide-react";
import { AuthCard } from "@/components/auth/AuthCard";
import { OtpInput } from "@/components/auth/OtpInput";
import { Button } from "@/components/ui/button";
import { getErrorMessage } from "@/lib/api/client";
import { resendVerification, verifyEmail } from "@/lib/api/auth";

function VerifyEmailForm() {
  const params = useSearchParams();
  const email = params.get("email") ?? "";
  const [otp, setOtp] = useState("");
  const [secondsLeft, setSecondsLeft] = useState(60);
  const verification = useMutation({ mutationFn: () => verifyEmail(email, otp) });
  const resend = useMutation({ mutationFn: () => resendVerification(email), onSuccess: () => setSecondsLeft(60) });

  useEffect(() => {
    if (secondsLeft <= 0) return;
    const timer = window.setTimeout(() => setSecondsLeft((current) => current - 1), 1000);
    return () => window.clearTimeout(timer);
  }, [secondsLeft]);

  return <AuthCard>
    {verification.isSuccess ? <div role="status" className="text-center"><CheckCircle2 aria-hidden="true" className="mx-auto size-12 text-primary" /><h1 className="mt-4 font-heading text-2xl font-bold">Email đã được xác minh</h1><p className="mt-2 text-sm text-muted-foreground">Bạn có thể đăng nhập và bắt đầu khám phá sự kiện.</p><Button asChild className="mt-6 w-full"><Link href="/login">Đến đăng nhập</Link></Button></div> : <>
      <h1 className="font-heading text-2xl font-bold tracking-tight">Xác minh email</h1>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">Chúng tôi đã gửi mã gồm 6 chữ số đến <span className="font-semibold text-foreground">{email || "email của bạn"}</span></p>
      {!email ? <p role="alert" className="mt-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive">Thiếu địa chỉ email. Hãy đăng ký lại hoặc quay về đăng nhập.</p> : null}
      <form onSubmit={(event) => { event.preventDefault(); verification.mutate(); }} className="mt-7 space-y-6">
        <OtpInput value={otp} onChange={setOtp} />
        {verification.isError ? <p role="alert" className="text-sm text-destructive">{getErrorMessage(verification.error)}</p> : null}
        <Button type="submit" className="w-full" disabled={!email || otp.length !== 6 || verification.isPending}>{verification.isPending ? "Đang xác minh…" : "Xác minh email"}</Button>
      </form>
      <div className="mt-6 text-center text-sm"><span className="text-muted-foreground">Chưa nhận được mã? </span><button type="button" disabled={!email || secondsLeft > 0 || resend.isPending} onClick={() => resend.mutate()} className="font-semibold text-primary disabled:cursor-not-allowed disabled:text-muted-foreground">{resend.isPending ? "Đang gửi…" : "Gửi lại"}</button>{secondsLeft > 0 ? <span className="ml-1 text-xs text-muted-foreground">({secondsLeft}s)</span> : null}</div>
      {resend.isError ? <p role="alert" className="mt-2 text-center text-sm text-destructive">{getErrorMessage(resend.error)}</p> : null}
      <div className="mt-6 border-t border-border pt-5"><Link href="/login" className="inline-flex items-center gap-2 text-sm font-semibold text-primary"><ArrowLeft aria-hidden="true" className="size-4" />Quay lại đăng nhập</Link></div>
    </>}
  </AuthCard>;
}

export default function VerifyEmailPage() {
  return <Suspense fallback={<main className="min-h-screen animate-pulse bg-muted" />}><VerifyEmailForm /></Suspense>;
}
