package com.dev.product_service.exception;

import com.dev.commonlib_api_response.exception.ErrorCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ProductErrorCode implements ErrorCode {

    // 30xx — Venue
    VENUE_NOT_FOUND(3001, "Không tìm thấy địa điểm", HttpStatus.NOT_FOUND),

    // 31xx — Event
    EVENT_NOT_FOUND(3101, "Không tìm thấy sự kiện", HttpStatus.NOT_FOUND),
    EVENT_NOT_IN_DRAFT(3102, "Thao tác này chỉ thực hiện được khi sự kiện đang ở trạng thái nháp", HttpStatus.CONFLICT),
    EVENT_ALREADY_CANCELLED(3103, "Sự kiện đã bị hủy", HttpStatus.CONFLICT),
    EVENT_ALREADY_PUBLISHED(3104, "Sự kiện đã được publish trước đó", HttpStatus.CONFLICT),
    EVENT_PUBLISH_VALIDATION_FAILED(3105, "Sự kiện chưa đủ điều kiện để publish", HttpStatus.BAD_REQUEST),
    EVENT_TIME_RANGE_INVALID(3106, "Thời gian diễn ra sự kiện không hợp lệ", HttpStatus.BAD_REQUEST),
    SALES_WINDOW_INVALID(3107, "Khung thời gian mở bán không hợp lệ", HttpStatus.BAD_REQUEST),

    // 32xx — SeatMap / Seat
    SEAT_MAP_ALREADY_EXISTS(3201, "Sự kiện đã có sơ đồ ghế", HttpStatus.CONFLICT),
    SEAT_MAP_NOT_FOUND(3202, "Sự kiện chưa có sơ đồ ghế", HttpStatus.NOT_FOUND),
    DUPLICATE_SEAT(3203, "Ghế bị trùng (cùng khu vực/hàng/số ghế)", HttpStatus.CONFLICT),
    SEAT_NOT_FOUND(3204, "Không tìm thấy ghế", HttpStatus.NOT_FOUND),
    SEAT_ALREADY_ASSIGNED(3205, "Ghế đã được gán cho một hạng vé khác", HttpStatus.CONFLICT),
    SEAT_BELONGS_TO_DIFFERENT_EVENT(3206, "Ghế không thuộc sự kiện này", HttpStatus.BAD_REQUEST),

    // 33xx — TicketType
    TICKET_TYPE_NOT_FOUND(3301, "Không tìm thấy hạng vé", HttpStatus.NOT_FOUND),
    GA_QUANTITY_REQUIRED(3302, "Hạng vé GA phải có số lượng lớn hơn 0", HttpStatus.BAD_REQUEST),
    SEAT_IDS_REQUIRED_FOR_SEATED(3303, "Hạng vé SEATED phải gán ít nhất một ghế", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;
}