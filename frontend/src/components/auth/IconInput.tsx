// src/components/auth/IconInput.tsx
"use client";

import { Input } from "@/components/ui/input";
import type { InputHTMLAttributes, ReactNode } from "react";
import { forwardRef } from "react";

interface IconInputProps extends InputHTMLAttributes<HTMLInputElement> {
  icon: ReactNode;
}

export const IconInput = forwardRef<HTMLInputElement, IconInputProps>(
  ({ icon, className, ...props }, ref) => (
    <div className="relative">
      <span className="pointer-events-none absolute left-4 top-1/2 -translate-y-1/2 text-muted-foreground">
        {icon}
      </span>
      <Input
        ref={ref}
        className={`pl-11 rounded-lg ${className ?? ""}`}
        {...props}
      />
    </div>
  ),
);
IconInput.displayName = "IconInput";
