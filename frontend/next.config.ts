import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Cho phép chạy thêm một server (vd stack E2E Playwright) cạnh `npm run dev` mà không dùng chung thư mục build
  distDir: process.env.NEXT_DIST_DIR || ".next",
};

export default nextConfig;
