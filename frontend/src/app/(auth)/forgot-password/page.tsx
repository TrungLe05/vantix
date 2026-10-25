"use client";

import { useMutation } from "@tanstack/react-query";
import { zodResolver } from "@hookform/resolvers/zod";
import { ArrowLeft, ArrowRight, Mail } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { AuthCard } from "@/components/auth/AuthCard";
import { IconInput } from "@/components/auth/IconInput";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { requestPasswordReset } from "@/lib/api/auth";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";

const schema = z.object({
  email: z.string().email("Enter a valid email address"),
});
type ForgotForm = z.infer<typeof schema>;

export default function ForgotPasswordPage() {
  const router = useRouter();
  const user = useAuthStore((state) => state.user);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ForgotForm>({ resolver: zodResolver(schema) });
  const mutation = useMutation({
    mutationFn: (data: ForgotForm) => requestPasswordReset(data.email),
    onSuccess: (_, data) =>
      router.push(`/reset-password?email=${encodeURIComponent(data.email)}`),
  });
  return (
    <AuthCard>
      <h1 className="font-heading text-2xl font-bold tracking-tight">
        Forgot your password?
      </h1>
      <p className="mt-2 text-sm leading-6 text-muted-foreground">
        Enter your email and we&apos;ll send you a 6-digit code to reset it.
      </p>
      <form
        onSubmit={handleSubmit((data) => mutation.mutate(data))}
        className="mt-7 space-y-5"
      >
        {mutation.isError ? (
          <p
            role="alert"
            className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive"
          >
            {getErrorMessage(mutation.error)}
          </p>
        ) : null}
        <div>
          <Label htmlFor="email" className="font-semibold">
            Email address
          </Label>
          <div className="mt-2">
            <IconInput
              id="email"
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              icon={<Mail size={18} />}
              {...register("email")}
            />
          </div>
          {errors.email ? (
            <p className="mt-1 text-sm text-destructive">
              {errors.email.message}
            </p>
          ) : null}
        </div>
        <Button type="submit" className="w-full" disabled={mutation.isPending}>
          {mutation.isPending ? "Sending code…" : "Send reset code"}
          <ArrowRight />
        </Button>
      </form>
      <div className="mt-6 pt-6 border-t border-border text-center">
        <Link
          href={user ? "/profile" : "/login"}
          className="inline-flex justify-center items-center gap-2 text-sm font-semibold text-primary hover:opacity-80"
        >
          <ArrowLeft aria-hidden="true" className="size-4" />
          {user ? "Back to my profile" : "Back to sign in"}
        </Link>
      </div>
    </AuthCard>
  );
}
