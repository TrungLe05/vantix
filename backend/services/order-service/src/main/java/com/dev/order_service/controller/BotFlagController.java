package com.dev.order_service.controller;

import com.dev.commonlib_api_response.dto.response.ApiResponse;
import com.dev.commonlib_api_response.exception.AppException;
import com.dev.commonlib_api_response.exception.CommonErrorCode;
import com.dev.commonlib_security.security.SecurityUtils;
import com.dev.order_service.dto.response.BotFlagResponse;
import com.dev.order_service.dto.response.PageResponse;
import com.dev.order_service.enums.BotFlagStatus;
import com.dev.order_service.service.BotFlagService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.UUID;

/** OR14/OR15 — ADMIN review kết quả bot-detection (D41). */
@RestController
@Validated
@RequestMapping("/orders/bot-flags")
@RequiredArgsConstructor
public class BotFlagController {

    private final BotFlagService botFlagService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<PageResponse<BotFlagResponse>> list(
            @RequestParam(required = false, name = "status") String status,
            @RequestParam(defaultValue = "0", name = "page") @Min(0) int page,
            @RequestParam(defaultValue = "20", name = "size") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.<PageResponse<BotFlagResponse>>builder()
                .result(botFlagService.list(parseStatus(status), page, size))
                .build();
    }

    @PostMapping("/{flagId}/clear")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<BotFlagResponse> clear(@PathVariable UUID flagId) {
        return ApiResponse.<BotFlagResponse>builder()
                .result(botFlagService.clear(SecurityUtils.getCurrentUserId(), flagId))
                .build();
    }

    private static BotFlagStatus parseStatus(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return BotFlagStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AppException(CommonErrorCode.VALIDATION_FAILED);
        }
    }
}
