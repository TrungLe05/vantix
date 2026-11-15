package com.dev.notification_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Wrapper phân trang, cùng shape với PageResponse của product-service (content/page/size/totalElements/totalPages)
 * để frontend dùng chung type. Đây là bản sao thứ ba (product, order, notification) — đã đủ rule of 3,
 * nên tách vào commonlib-api-response ở một thay đổi riêng thay vì refactor trong P5.
 */
@Getter
@Builder
@AllArgsConstructor
public class PageResponse<T> {
    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
}
