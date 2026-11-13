// src/app/(auth)/change-password/page.tsx
"use client";

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { AuthCard } from "@/components/auth/AuthCard";
import { PasswordInput } from "@/components/auth/PasswordInput";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { changePassword } from "@/lib/api/auth";
import { getErrorMessage } from "@/lib/api/client";
import { useAuthStore } from "@/store/auth-store";

// Trang bắt buộc đổi mật khẩu (claim mcp, vd STAFF do ADMIN tạo với mật khẩu tạm): login và auth-callback
// chuyển tới đây. Gateway chỉ cho token mcp gọi PATCH /auth/change-password, POST /auth/logout, GET /users/me
// nên trang không dùng layout (main) có header gọi API khác. Rule mật khẩu khớp backend (A12) và trang profile.
const changePasswordSchema = z
  .object({
    oldPassword: z.string().min(1, "Enter your current password"),
    newPassword: z
      .string()
      .min(8, "Password must be 8–64 characters and include at least one letter and one number.")
      .max(64, "Password must be 8–64 characters and include at least one letter and one number.")
      .regex(/(?=.*[A-Za-z])(?=.*\d)/, "Password must be 8–64 characters and include at least one letter and one number."),
    confirmPassword: z.string(),
  })
  .refine((data) => data.newPassword === data.confirmPassword, {
    path: ["confirmPassword"],
    message: "The passwords do not match.",
  });

type ChangePasswordForm = z.infer<typeof changePasswordSchema>;

export default function ChangePasswordPage() {
  const router = useRouter();
  const clearSession = useAuthStore((s) => s.clear);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<ChangePasswordForm>({ resolver: zodResolver(changePasswordSchema) });

  const mutation = useMutation({
    mutationFn: (data: ChangePasswordForm) => changePassword(data.oldPassword, data.newPassword),
    onSuccess: () => {
      // Backend thu hồi phiên sau khi đổi mật khẩu: đăng nhập lại với mật khẩu mới
      clearSession();
      router.replace("/login?passwordChanged=1");
    },
  });

  return (
    <AuthCard>
      <h1 className="font-heading text-2xl font-bold tracking-tight">Set a new password</h1>
      <p className="mt-2 text-sm text-muted-foreground">
        Your account uses a temporary password. Choose a new one to continue.
      </p>

      <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="mt-7 space-y-4">
        {mutation.isError && (
          <p role="alert" className="rounded-lg bg-destructive/10 p-3 text-sm text-destructive">
            {getErrorMessage(mutation.error)}
          </p>
        )}

        <div>
          <Label htmlFor="old-password" className="font-semibold">Current password</Label>
          <div className="mt-1.5">
            <PasswordInput id="old-password" autoComplete="current-password" {...register("oldPassword")} />
          </div>
          {errors.oldPassword && <p className="mt-1 text-sm text-destructive">{errors.oldPassword.message}</p>}
        </div>

        <div>
          <Label htmlFor="new-password" className="font-semibold">New password</Label>
          <div className="mt-1.5">
            <PasswordInput id="new-password" autoComplete="new-password" {...register("newPassword")} />
          </div>
          {errors.newPassword && <p className="mt-1 text-sm text-destructive">{errors.newPassword.message}</p>}
        </div>

        <div>
          <Label htmlFor="confirm-password" className="font-semibold">Confirm new password</Label>
          <div className="mt-1.5">
            <PasswordInput id="confirm-password" autoComplete="new-password" {...register("confirmPassword")} />
          </div>
          {errors.confirmPassword && (
            <p className="mt-1 text-sm text-destructive">{errors.confirmPassword.message}</p>
          )}
        </div>

        <Button type="submit" className="w-full" disabled={mutation.isPending}>
          {mutation.isPending ? "Updating…" : "Update password"}
        </Button>
      </form>
    </AuthCard>
  );
}
