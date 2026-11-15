package com.dev.notification_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_security.security.SecurityUtils;
import com.dev.notification_service.dto.response.NotificationResponse;
import com.dev.notification_service.dto.response.PageResponse;
import com.dev.notification_service.dto.response.UnreadCountResponse;
import com.dev.notification_service.dto.response.WsTicketResponse;
import com.dev.notification_service.service.NotificationService;
import com.dev.notification_service.websocket.WsTicketService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Mọi vai trò đăng nhập đều có hộp thông báo của mình; chỉ đọc/sửa thông báo của chính user. */
@RestController
@Validated
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final WsTicketService wsTicketService;

    @GetMapping
    public ApiResponse<PageResponse<NotificationResponse>> list(
            @RequestParam(defaultValue = "0", name = "page") @Min(0) int page,
            @RequestParam(defaultValue = "20", name = "size") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.<PageResponse<NotificationResponse>>builder()
                .result(notificationService.list(SecurityUtils.getCurrentUserId(), page, size))
                .build();
    }

    @GetMapping("/unread-count")
    public ApiResponse<UnreadCountResponse> unreadCount() {
        return ApiResponse.<UnreadCountResponse>builder()
                .result(notificationService.unreadCount(SecurityUtils.getCurrentUserId()))
                .build();
    }

    @PatchMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markRead(@PathVariable UUID notificationId) {
        return ApiResponse.<NotificationResponse>builder()
                .result(notificationService.markRead(SecurityUtils.getCurrentUserId(), notificationId))
                .build();
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        return ApiResponse.<Map<String, Integer>>builder()
                .result(Map.of("updated", notificationService.markAllRead(SecurityUtils.getCurrentUserId())))
                .build();
    }

    @PostMapping("/ws-ticket")
    public ApiResponse<WsTicketResponse> wsTicket() {
        WsTicketService.Issued issued = wsTicketService.issue(SecurityUtils.getCurrentUserId());
        return ApiResponse.<WsTicketResponse>builder()
                .result(new WsTicketResponse(issued.ticket(), issued.expiresAt()))
                .build();
    }
}
