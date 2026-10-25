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

const passwordSchema = z.string().min(8, "Password must contain at least 8 characters").regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, "Password must contain both letters and numbers");

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
    if (!result.success) { setPasswordError(result.error.issues[0]?.message ?? "Invalid password"); return; }
    setPasswordError("");
    mutation.mutate();
  };

  return <AuthCard>
    <h1 className="font-heading text-2xl font-bold tracking-tight">Check your email</h1>
    <p className="mt-2 text-sm leading-6 text-muted-foreground">A 6-digit reset code was sent to <span className="font-medium text-foreground">{email || "your email"}</span>.</p>
    {!email ? <p role="alert" className="mt-4 rounded-lg bg-destructive/10 p-3 text-sm text-destructive">Email address is missing. Start again from the forgot password page.</p> : null}
    <form onSubmit={submit} className="mt-7 space-y-6">
      <OtpInput value={otp} onChange={setOtp} />
      <div><Label htmlFor="new-password" className="mb-2 block font-semibold">New password</Label><PasswordInput id="new-password" autoComplete="new-password" placeholder="At least 8 characters" value={password} onChange={(event) => setPassword(event.target.value)} /><p className="mt-2 text-xs text-muted-foreground">Use at least 8 characters, including letters and numbers.</p>{passwordError ? <p role="alert" className="mt-1 text-sm text-destructive">{passwordError}</p> : null}</div>
      {mutation.isError ? <p role="alert" className="text-sm text-destructive">{getErrorMessage(mutation.error)}</p> : null}
      <Button type="submit" className="w-full" disabled={!email || otp.length !== 6 || mutation.isPending}>{mutation.isPending ? "Updating…" : "Reset password"}</Button>
    </form>
    <div className="mt-6 text-center text-sm"><span className="text-muted-foreground">Didn’t receive a code? </span><button type="button" disabled={!email || secondsLeft > 0 || resend.isPending} onClick={() => resend.mutate()} className="font-semibold text-primary disabled:cursor-not-allowed disabled:text-muted-foreground">{resend.isPending ? "Sending…" : "Resend"}</button>{secondsLeft > 0 ? <span className="ml-1 text-xs text-muted-foreground">({secondsLeft}s)</span> : null}</div>
    {resend.isError ? <p role="alert" className="mt-2 text-center text-sm text-destructive">{getErrorMessage(resend.error)}</p> : null}
    <Link href="/login" className="mt-6 inline-flex items-center gap-2 border-t border-border pt-5 text-sm font-semibold text-primary"><ArrowLeft aria-hidden="true" className="size-4" />Back to log in</Link>
  </AuthCard>;
}

export default function ResetPasswordPage() {
  return <Suspense fallback={<main className="min-h-screen animate-pulse bg-muted" />}><ResetPasswordForm /></Suspense>;
}
