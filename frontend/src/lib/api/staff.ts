// src/lib/api/staff.ts — A14 (ADMIN): tạo tài khoản STAFF. Backend sinh mật khẩu tạm ngẫu nhiên, gửi qua
// email và bắt đổi mật khẩu ở lần đăng nhập đầu (mcp=true); mật khẩu không bao giờ trả về cho ADMIN.
import { apiClient } from "@/lib/api/client";
import type { UserResponse } from "@/types/auth";
import type { ApiResponse } from "@/types/common";

export async function createStaff(request: { email: string; fullName: string }) {
  const response = await apiClient.post<ApiResponse<UserResponse>>("/users/staff", request);
  if (!response.data.result) throw new Error("Could not create the staff account");
  return response.data.result;
}
