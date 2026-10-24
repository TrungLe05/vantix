// src/components/auth/AuthCard.tsx
import Link from "next/link";

export function AuthCard({ children }: { children: React.ReactNode }) {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center bg-background px-5 py-12 text-foreground">
      <Link href="/" className="mb-7 text-2xl font-bold tracking-tight">
        Vantix<span className="text-primary">.</span>
      </Link>
      <section className="w-full max-w-[420px] rounded-xl border border-border bg-card p-6 shadow-md sm:p-8">
        {children}
      </section>
      <p className="mt-7 text-center text-xs text-muted-foreground">© 2026 Vantix. All rights reserved.</p>
    </main>
  );
}
