// src/components/auth/GoogleButton.tsx
import { Button } from "@/components/ui/button";

export function GoogleButton() {
  const url = `${process.env.NEXT_PUBLIC_API_BASE_URL}/oauth2/authorization/google`;
  return (
    <Button variant="outline" className="w-full" asChild>
      <a href={url}>
        <span className="mr-2 font-bold text-primary">G</span>
        Continue with Google
      </a>
    </Button>
  );
}
