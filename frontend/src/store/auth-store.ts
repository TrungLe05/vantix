// src/store/auth-store.ts
import { create } from "zustand";
import type { UserResponse } from "@/types/auth";

interface AuthState {
  accessToken: string | null;
  user: UserResponse | null;
  isHydrated: boolean; // True after the silent refresh on page load finishes, whether it succeeds or fails.
  setSession: (accessToken: string, user: UserResponse) => void;
  setHydrated: () => void;
  clear: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  isHydrated: false,
  setSession: (accessToken, user) => set({ accessToken, user }),
  setHydrated: () => set({ isHydrated: true }),
  clear: () => set({ accessToken: null, user: null }),
}));
