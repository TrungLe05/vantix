"use client";

import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { z } from "zod";
import { LoaderCircle, UserPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { getErrorMessage } from "@/lib/api/client";
import { createStaff } from "@/lib/api/staff";
import type { UserResponse } from "@/types/auth";

// Khớp CreateStaffRequest: email ≤ 255, fullName ≤ 100
const staffSchema = z.object({
  email: z.string().trim().min(1, "Enter an email address.").max(255).email("Enter a valid email address."),
  fullName: z.string().trim().min(1, "Enter the staff member's name.").max(100, "Name must be 100 characters or fewer."),
});

type StaffFormValues = z.infer<typeof staffSchema>;

export default function AdminStaffPage() {
  const [created, setCreated] = useState<UserResponse[]>([]);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<StaffFormValues>({ resolver: zodResolver(staffSchema), defaultValues: { email: "", fullName: "" } });
  const mutation = useMutation({
    mutationFn: createStaff,
    onSuccess: (user) => {
      setCreated((list) => [user, ...list]);
      reset();
    },
  });

  return (
    <div className="mx-auto w-full max-w-3xl space-y-6">
      <div>
        <h1 className="mt-1 font-heading text-3xl font-bold tracking-tight">Staff accounts</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          Staff can check tickets in at the entrance. Vantix generates a random temporary password and
          emails it to the new staff member; they must change it the first time they log in.
        </p>
      </div>

      <section className="rounded-2xl border border-border bg-card p-5 shadow-sm md:p-7" aria-labelledby="new-staff-title">
        <h2 id="new-staff-title" className="font-heading text-lg font-semibold">Add a staff member</h2>
        <form
          noValidate
          onSubmit={handleSubmit((values) => mutation.mutate(values))}
          className="mt-4 grid gap-4 sm:grid-cols-2"
        >
          <div className="space-y-2">
            <Label htmlFor="staff-name">Full name</Label>
            <Input id="staff-name" autoComplete="off" aria-invalid={Boolean(errors.fullName)} {...register("fullName")} />
            {errors.fullName ? <p className="text-sm text-destructive">{errors.fullName.message}</p> : null}
          </div>
          <div className="space-y-2">
            <Label htmlFor="staff-email">Email</Label>
            <Input id="staff-email" type="email" autoComplete="off" aria-invalid={Boolean(errors.email)} {...register("email")} />
            {errors.email ? <p className="text-sm text-destructive">{errors.email.message}</p> : null}
          </div>
          {mutation.isError ? (
            <p role="alert" className="text-sm text-destructive sm:col-span-2">
              {getErrorMessage(mutation.error, "Could not create the staff account.")}
            </p>
          ) : null}
          <div className="flex justify-end sm:col-span-2">
            <Button type="submit" disabled={mutation.isPending}>
              {mutation.isPending ? <LoaderCircle aria-hidden="true" className="animate-spin" /> : <UserPlus aria-hidden="true" />}
              Create staff account
            </Button>
          </div>
        </form>
      </section>

      {created.length > 0 ? (
        <section aria-labelledby="created-staff-title" className="rounded-2xl border border-primary/20 bg-primary/5 p-5">
          <h2 id="created-staff-title" className="font-semibold">Created in this session</h2>
          <ul className="mt-3 space-y-2 text-sm" role="status" aria-live="polite">
            {created.map((user) => (
              <li key={user.id}>
                <span className="font-medium">{user.fullName}</span> · {user.email} — temporary password sent by
                email, password change required at first login.
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
