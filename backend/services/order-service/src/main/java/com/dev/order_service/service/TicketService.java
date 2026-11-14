package com.dev.order_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.order_service.dto.response.CheckInResponse;
import com.dev.order_service.dto.response.TicketResponse;
import com.dev.order_service.entity.Hold;
import com.dev.order_service.entity.Order;
import com.dev.order_service.entity.OrderItem;
import com.dev.order_service.entity.Ticket;
import com.dev.order_service.enums.OrderErrorCode;
import com.dev.order_service.enums.TicketStatus;
import com.dev.order_service.repository.OrderItemRepository;
import com.dev.order_service.repository.OrderRepository;
import com.dev.order_service.repository.TicketRepository;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Phát hành vé, ảnh QR và check-in (P5). Order sở hữu vé vì chỉ Order biết ai mua vé nào. */
@Service
@RequiredArgsConstructor
@Slf4j
public class TicketService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int QR_SIZE_PX = 320;

    private final TicketRepository ticketRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderRepository orderRepository;

    /**
     * Gọi trong transaction đã khóa Order và đã chuyển hold sang PURCHASED. Ghế = 1 vé, GA số lượng
     * N = N vé (mỗi người vào cổng một mã).
     *
     * @return số vé đã phát hành
     */
    public int issueTickets(Order order, List<Hold> purchasedHolds) {
        List<Ticket> tickets = new ArrayList<>();
        for (Hold hold : purchasedHolds) {
            OrderItem item = orderItemRepository.findByIdAndOrderId(hold.getOrderItemId(), order.getId())
                    .orElseThrow(() -> new IllegalStateException("Hold không có OrderItem: " + hold.getId()));
            for (int i = 0; i < hold.getQuantity(); i++) {
                tickets.add(Ticket.builder()
                        .orderId(order.getId())
                        .orderItemId(item.getId())
                        .eventId(order.getEventId())
                        .buyerId(order.getBuyerId())
                        .ticketTypeId(item.getTicketTypeId())
                        .seatId(item.getSeatId())
                        .ticketNameSnapshot(item.getTicketNameSnapshot())
                        .seatLabelSnapshot(item.getSeatLabelSnapshot())
                        .code(newCode())
                        .status(TicketStatus.VALID)
                        .build());
            }
        }
        ticketRepository.saveAll(tickets);
        return tickets.size();
    }

    /** Hoàn tiền / hủy event: vé chưa dùng thành VOID. Gọi trong transaction đã khóa Order. */
    public void voidValidTickets(UUID orderId) {
        for (Ticket ticket : ticketRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId)) {
            if (ticket.getStatus() == TicketStatus.VALID) {
                ticket.setStatus(TicketStatus.VOID);
            }
        }
    }

    public boolean hasCheckedInTickets(UUID orderId) {
        return ticketRepository.countByOrderIdAndStatus(orderId, TicketStatus.CHECKED_IN) > 0;
    }

    public List<TicketResponse> getTickets(UUID orderId) {
        return ticketRepository.findByOrderIdOrderByCreatedAtAscIdAsc(orderId).stream()
                .map(ticket -> TicketResponse.builder()
                        .id(ticket.getId())
                        .ticketTypeId(ticket.getTicketTypeId())
                        .ticketName(ticket.getTicketNameSnapshot())
                        .seatLabel(ticket.getSeatLabelSnapshot())
                        .code(ticket.getCode())
                        .status(ticket.getStatus())
                        .checkedInAt(ticket.getCheckedInAt())
                        .build())
                .toList();
    }

    /** Ảnh QR (PNG) của một vé thuộc buyer. Vé VOID không còn QR. */
    @Transactional(readOnly = true)
    public byte[] qrPng(UUID buyerId, UUID orderId, UUID ticketId) {
        // Không phân biệt "không tồn tại" và "của người khác" (chống dò, D17)
        orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(OrderErrorCode.ORDER_NOT_FOUND));
        Ticket ticket = ticketRepository.findByIdAndOrderId(ticketId, orderId)
                .orElseThrow(() -> new AppException(OrderErrorCode.TICKET_NOT_FOUND));
        if (ticket.getStatus() == TicketStatus.VOID) {
            throw new AppException(OrderErrorCode.TICKET_NOT_VALID);
        }
        return renderQr(ticket.getCode());
    }

    /**
     * Quét vé ở cổng (ADMIN/STAFF). Update có điều kiện quyết định ai thắng khi hai cổng quét cùng
     * lúc; nhánh lỗi chỉ đọc lại để báo lý do.
     */
    @Transactional
    public CheckInResponse checkIn(UUID staffId, UUID eventId, String rawCode) {
        String code = rawCode.trim();
        if (ticketRepository.checkIn(code, eventId, staffId, Instant.now()) == 1) {
            Ticket ticket = ticketRepository.findByCode(code)
                    .orElseThrow(() -> new IllegalStateException("Vé vừa check-in không đọc lại được"));
            log.info("Check-in thành công ticketId={} orderId={} staffId={}", ticket.getId(), ticket.getOrderId(), staffId);
            return toCheckIn(ticket);
        }
        // Vé của sự kiện khác báo như không tồn tại: STAFF sự kiện A không dò được vé sự kiện B
        Ticket ticket = ticketRepository.findByCode(code)
                .filter(found -> found.getEventId().equals(eventId))
                .orElseThrow(() -> new AppException(OrderErrorCode.TICKET_NOT_FOUND));
        if (ticket.getStatus() == TicketStatus.CHECKED_IN) {
            log.warn("Quét trùng ticketId={} staffId={} lần đầu bởi {}", ticket.getId(), staffId, ticket.getCheckedInBy());
            throw new AppException(OrderErrorCode.TICKET_ALREADY_CHECKED_IN, toCheckIn(ticket));
        }
        throw new AppException(OrderErrorCode.TICKET_NOT_VALID);
    }

    private CheckInResponse toCheckIn(Ticket ticket) {
        return CheckInResponse.builder()
                .ticketId(ticket.getId())
                .orderId(ticket.getOrderId())
                .ticketName(ticket.getTicketNameSnapshot())
                .seatLabel(ticket.getSeatLabelSnapshot())
                .checkedInAt(ticket.getCheckedInAt())
                .checkedInBy(ticket.getCheckedInBy())
                .build();
    }

    // 24 byte = 192 bit ngẫu nhiên → 32 ký tự base64url, đủ ngắn để nhập tay khi camera hỏng
    private static String newCode() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] renderQr(String content) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, QR_SIZE_PX, QR_SIZE_PX,
                    Map.of(EncodeHintType.MARGIN, 2, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_BYTE_BINARY);
            for (int x = 0; x < matrix.getWidth(); x++) {
                for (int y = 0; y < matrix.getHeight(); y++) {
                    image.setRGB(x, y, matrix.get(x, y) ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (WriterException | IOException ex) {
            throw new IllegalStateException("Không tạo được ảnh QR", ex);
        }
    }
}
