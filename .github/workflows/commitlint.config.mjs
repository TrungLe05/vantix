// Quy ước commit: Conventional Commits, ví dụ "feat(user): thêm API đăng ký"
export default {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "type-enum": [
      2,
      "always",
      ["feat", "fix", "docs", "style", "refactor", "perf", "test", "build", "ci", "chore", "revert"],
    ],
    // Cho phép viết subject bằng tiếng Việt (có dấu, viết hoa tùy ý)
    "subject-case": [0],
    "header-max-length": [2, "always", 100],
    // Tắt giới hạn độ dài dòng ở body/footer vì trailer (Co-Authored-By, URL) thường dài
    "body-max-line-length": [0],
    "footer-max-line-length": [0],
  },
};
