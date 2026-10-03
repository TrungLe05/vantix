package com.dev.product_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Wrapper phân trang dùng cho các API có danh sách — nhất quán với cách P1 dùng Page từ Spring Data.
 * Dùng riêng thay vì trả Page<T> trực tiếp để kiểm soát chính xác các field trả về (totalElements, totalPages...).
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
