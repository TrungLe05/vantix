package com.dev.product_service.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Response xem sơ đồ ghế (PR11).
 * Mỗi Seat kèm ticketType tương ứng (null nếu ghế chưa được gán — chỉ xảy ra khi event còn DRAFT).
 * Trạng thái giao dịch (đang giữ/đã bán) do Order cung cấp riêng; Product chỉ trả catalog tĩnh.
 */
@Getter
@Builder
@AllArgsConstructor
public class SeatMapDetailResponse {

    private UUID seatMapId;
    private List<SeatDetail> seats;

    @Getter
    @Builder
    @AllArgsConstructor
    public static class SeatDetail {
        private UUID id;
        private String section;
        private String rowLabel;
        private String seatNumber;
        private String displayLabel;
        private String status;

        /** null nếu ghế chưa gán hạng vé (chỉ xảy ra khi event DRAFT). */
        @JsonInclude(JsonInclude.Include.ALWAYS)
        private TicketTypeBrief ticketType;
    }

    @Getter
    @Builder
    @AllArgsConstructor
    public static class TicketTypeBrief {
        private UUID id;
        private String name;
        private BigDecimal price;
    }
}
