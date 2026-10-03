import axios from "axios";
import useAuthStore from "@/store/auth-store";

export const apiClient = axios.create({
  baseURL: process.env.NEXT_PUBLIC_API_BASE_URL,
  withCredentials: true, // bắt buộc — để trình duyệt gửi/nhận cookie refreshToken
});

apiClient.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) config.headers.Authorization = `Bearer ${token}`;
  return config;
});

let refreshPromise: Promise<string> | null = null;

async function refreshAccessToken(): Promise<string> {
  // Nhiều request 401 cùng lúc chỉ tạo ĐÚNG MỘT request refresh thật —
  // các request sau "ăn theo" cùng 1 Promise thay vì tự gọi refresh riêng
  if (!refreshPromise) {
    refreshPromise = apiClient
      .post("/auth/refresh")
      .then((res) => {
        const token = res.data.result.auth.accessToken;
        useAuthStore.getState().setAccessToken(token);
        return token;
      })
      .finally(() => {
        refreshPromise = null;
      });
  }
  return refreshPromise;
}

apiClient.interceptors.response.use(
  (res) => res,
  async (error) => {
    const { config, response } = error;
    const errorCode = response?.data?.code;

    // CHỈ tự refresh khi đúng 9003 (hết hạn) — KHÔNG refresh khi 9002 (token sai),
    // tránh vòng lặp vô ích (đúng lưu ý đã ghi ở P1_auth_api_design.md mục 4)
    if (errorCode === 9003 && !config._retried) {
      config._retried = true;
      try {
        const newToken = await refreshAccessToken();
        config.headers.Authorization = `Bearer ${newToken}`;
        return apiClient.request(config);
      } catch {
        useAuthStore.getState().clear();
        window.location.href = "/login";
      }
    }

    if (errorCode === 9001 || errorCode === 9002) {
      useAuthStore.getState().clear();
      window.location.href = "/login";
    }

    return Promise.reject(error);
  }
);