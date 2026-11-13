package com.dev.order_service.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * Wrapper phân trang, cùng shape với PageResponse của product-service (content/page/size/totalElements/totalPages)
 * để frontend dùng chung type. Chưa tách common-lib vì mới 2 service cần (rule of 3).
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
