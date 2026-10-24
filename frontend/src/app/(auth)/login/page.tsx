// src/app/(auth)/login/page.tsx
"use client";

import { Suspense } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import Link from "next/link";
import { AuthCard } from "@/components/auth/AuthCard";
import { PasswordInput } from "@/components/auth/PasswordInput";
import { GoogleButton } from "@/components/auth/GoogleButton";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { login } from "@/lib/api/auth";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";
import { Mail } from "lucide-react";
import { IconInput } from "@/components/auth/IconInput";

const loginSchema = z.object({
  email: z.string().email("Email không đúng định dạng"),
  password: z.string().min(1, "Vui lòng nhập mật khẩu"),
});

type LoginForm = z.infer<typeof loginSchema>;

function LoginForm() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const setSession = useAuthStore((s) => s.setSession);

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema) });

  const mutation = useMutation({
    mutationFn: (data: LoginForm) => login(data.email, data.password),
    onSuccess: ({ auth, user }) => {
      setSession(auth.accessToken, user);
      router.push(user.mustChangePassword ? "/change-password" : "/");
    },
  });

  return (
    <AuthCard>
      <h1 className="font-heading text-2xl font-bold tracking-tight">Chào mừng bạn quay lại</h1>
      <p className="mt-2 text-sm text-muted-foreground">Đăng nhập để tiếp tục với Vantix</p>
      {searchParams.get("passwordReset") === "1" || searchParams.get("passwordChanged") === "1" ? <p role="status" className="mt-4 rounded-lg bg-primary/10 p-3 text-sm text-primary">Mật khẩu đã được cập nhật. Hãy đăng nhập lại.</p> : null}

      <form
        onSubmit={handleSubmit((data) => mutation.mutate(data))}
        className="mt-7 space-y-4"
      >
        {mutation.isError && (
          <p role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
            {getErrorMessage(mutation.error)}
          </p>
        )}

        <div>
          <Label htmlFor="email" className="font-semibold">
            Địa chỉ email
          </Label>
          <div className="mt-1.5">
            <IconInput
              id="email"
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              icon={<Mail size={18} />}
              {...register("email")}
            />
          </div>
          {errors.email && (
            <p className="mt-1 text-sm text-destructive">{errors.email.message}</p>
          )}
        </div>

        <div>
          <div className="flex items-center justify-between">
            <Label htmlFor="password" className="font-semibold">Mật khẩu</Label>
            <Link
              href="/forgot-password"
              className="text-sm font-medium text-primary"
            >
              Quên mật khẩu?
            </Link>
          </div>
          <PasswordInput
            id="password"
            autoComplete="current-password"
            placeholder="Nhập mật khẩu"
            {...register("password")}
          />
          {errors.password && (
            <p className="mt-1 text-sm text-destructive">
              {errors.password.message}
            </p>
          )}
        </div>

        <Button type="submit" className="w-full" disabled={mutation.isPending}>
          {mutation.isPending ? "Đang đăng nhập…" : "Đăng nhập"}
        </Button>
      </form>

      <div className="my-6 flex items-center gap-3 text-sm text-muted-foreground">
        <div className="h-px flex-1 bg-border" />
        or
        <div className="h-px flex-1 bg-border" />
      </div>

      <GoogleButton />

      <p className="mt-6 text-center text-sm text-muted-foreground">
        Chưa có tài khoản?{" "}
        <Link href="/register" className="font-medium text-primary">
          Đăng ký
        </Link>
      </p>
    </AuthCard>
  );
}

export default function LoginPage() {
  return <Suspense fallback={<main className="min-h-screen animate-pulse bg-muted" />}><LoginForm /></Suspense>;
}
