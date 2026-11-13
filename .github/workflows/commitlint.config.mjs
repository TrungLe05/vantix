// Quy ước commit: Conventional Commits, ví dụ "feat(user): thêm API đăng ký"

// Commit cũ sai quy ước đã merge vào develop (5cacf97, 452d625). Viết lại lịch sử develop đòi
// force-push nên chỉ bỏ qua đúng header của chúng; commit mới vẫn bị lint đầy đủ.
const LEGACY_HEADERS = [
  "feat(auth): implement full authentication system for user-service with JWT, OAuth2 Google, and API Gateway security layer",
  "1. feat(admin): add event and venue management",
];

export default {
  extends: ["@commitlint/config-conventional"],
  ignores: [(message) => LEGACY_HEADERS.includes(message.split("\n", 1)[0].trim())],
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
