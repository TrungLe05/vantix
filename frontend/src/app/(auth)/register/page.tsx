"use client";

import { useMutation } from "@tanstack/react-query";
import { zodResolver } from "@hookform/resolvers/zod";
import { Mail, UserRound } from "lucide-react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { AuthCard } from "@/components/auth/AuthCard";
import { GoogleButton } from "@/components/auth/GoogleButton";
import { IconInput } from "@/components/auth/IconInput";
import { PasswordInput } from "@/components/auth/PasswordInput";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { getErrorMessage } from "@/lib/api/client";
import { register as createAccount } from "@/lib/api/auth";

const schema = z.object({
  fullName: z.string().trim().min(2, "Nhập họ tên của bạn").max(100, "Họ tên quá dài"),
  email: z.string().email("Email không đúng định dạng"),
  password: z.string().min(8, "Mật khẩu cần ít nhất 8 ký tự").regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, "Mật khẩu cần có cả chữ cái và chữ số"),
});
type RegisterForm = z.infer<typeof schema>;

export default function RegisterPage() {
  const router = useRouter();
  const { register: registerField, handleSubmit, formState: { errors } } = useForm<RegisterForm>({ resolver: zodResolver(schema) });
  const mutation = useMutation({ mutationFn: (data: RegisterForm) => createAccount(data.email, data.password, data.fullName), onSuccess: (_, data) => router.push(`/verify-email?email=${encodeURIComponent(data.email)}`) });

  return <AuthCard>
    <h1 className="font-heading text-2xl font-bold tracking-tight">Tạo tài khoản</h1>
    <p className="mt-2 text-sm text-muted-foreground">Tham gia Vantix để khám phá và đặt vé sự kiện</p>
    <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="mt-7 space-y-4">
      {mutation.isError ? <p role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{getErrorMessage(mutation.error)}</p> : null}
      <div><Label htmlFor="fullName" className="font-semibold">Họ và tên</Label><div className="mt-2"><IconInput id="fullName" autoComplete="name" placeholder="Tên của bạn" icon={<UserRound size={18} />} {...registerField("fullName")} /></div>{errors.fullName ? <p className="mt-1 text-sm text-destructive">{errors.fullName.message}</p> : null}</div>
      <div><Label htmlFor="email" className="font-semibold">Địa chỉ email</Label><div className="mt-2"><IconInput id="email" type="email" autoComplete="email" placeholder="you@example.com" icon={<Mail size={18} />} {...registerField("email")} /></div>{errors.email ? <p className="mt-1 text-sm text-destructive">{errors.email.message}</p> : null}</div>
      <div><Label htmlFor="password" className="font-semibold">Mật khẩu</Label><div className="mt-2"><PasswordInput id="password" autoComplete="new-password" placeholder="Ít nhất 8 ký tự" {...registerField("password")} /></div><p className="mt-2 text-xs text-muted-foreground">Dùng ít nhất 8 ký tự, gồm chữ cái và chữ số.</p>{errors.password ? <p className="mt-1 text-sm text-destructive">{errors.password.message}</p> : null}</div>
      <Button type="submit" className="w-full" disabled={mutation.isPending}>{mutation.isPending ? "Đang tạo tài khoản…" : "Tạo tài khoản"}</Button>
    </form>
    <div className="my-6 flex items-center gap-3 text-xs text-muted-foreground"><span className="h-px flex-1 bg-border" />hoặc<span className="h-px flex-1 bg-border" /></div>
    <GoogleButton />
    <p className="mt-6 text-center text-sm text-muted-foreground">Đã có tài khoản? <Link href="/login" className="font-semibold text-primary hover:underline">Đăng nhập</Link></p>
  </AuthCard>;
}
