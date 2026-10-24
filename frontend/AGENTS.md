# Vantix Frontend Rules

Áp dụng cho frontend Next.js + TypeScript.

## Cách làm việc
- Trước khi sửa, đọc package.json, cấu hình và code liên quan để xác định phiên bản, router, thư viện và convention hiện có; không tự đoán stack.
- Giữ cấu trúc thư mục, cách đặt tên và pattern hiện tại. Chỉ sửa trong phạm vi tác vụ; không tự refactor phần không liên quan.
- Áp dụng các skill Vercel đã cài khi phù hợp với tác vụ và phiên bản project. Khi skill đề xuất khác convention hiện tại, nêu khác biệt trước khi thay đổi kiến trúc.
- Hỏi trước khi thêm, thay thế hoặc nâng phiên bản dependency. Dùng package manager và lockfile hiện có.

## Code và dữ liệu
- Tái sử dụng component, hook, utility và lớp API hiện có; chỉ tạo abstraction khi có nhu cầu cụ thể.
- Khai báo type rõ cho props và dữ liệu API; tránh any và ép kiểu để che lỗi. Giữ đúng ranh giới server/client theo router hiện tại.
- Khi API chưa có, đặt mock ở lớp dữ liệu riêng, có type và đánh dấu rõ; không nhúng mock vào component hoặc coi contract giả định là API thật.
- Xử lý loading, empty, error và success khi có liên quan. Không báo thành công khi thao tác thật thất bại.
- Không đưa secret vào code phía client hoặc log dữ liệu nhạy cảm.

## UI và UXMagic
- Với màn hình có thiết kế UXMagic, đọc màn hình và theme qua MCP trước khi triển khai. Bám bố cục, màu, typography và tương tác đã thể hiện.
- Tái sử dụng component và design token hiện có khi phù hợp; không tự đổi phong cách. Nếu thiếu thiết kế hoặc không truy cập được MCP, báo rõ và hỏi phần cần thiết, không tuyên bố đã đối chiếu.
- Đảm bảo responsive, semantic HTML, label, focus và thao tác bàn phím. Nếu thiết kế chưa thể hiện trạng thái phụ, bổ sung nhất quán; hỏi lại khi ảnh hưởng nghiệp vụ.

## Kiểm tra và bàn giao
- Chạy lint và typecheck theo script/config có sẵn; kiểm tra logic thay đổi bằng test phù hợp, không thêm test chỉ để lặp lại implementation.
- Chạy build khi thay đổi routing, cấu hình, dependency hoặc ranh giới server/client. Với thay đổi UI, kiểm tra hiển thị và tương tác ở kích thước phù hợp nếu có công cụ.
- Không tự thêm dependency chỉ để kiểm tra. Báo rõ kiểm tra chưa chạy, lỗi còn lại và phần đang dùng mock.
- Kết thúc bằng tóm tắt ngắn: đã đổi gì, đã kiểm tra gì, còn hạn chế gì.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
