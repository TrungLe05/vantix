// src/store/auth-store.ts
import { create } from "zustand";
import type { UserResponse } from "@/types/auth";

interface AuthState {
  accessToken: string | null;
  user: UserResponse | null;
  isHydrated: boolean; // true sau khi silent refresh lúc load trang chạy xong (dù thành công hay thất bại)
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