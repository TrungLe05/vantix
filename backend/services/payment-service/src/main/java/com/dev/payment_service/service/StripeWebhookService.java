package com.dev.payment_service.service;

import com.dev.commonlib_api_response.exception.AppException;
import com.dev.payment_service.enums.PaymentErrorCode;
import com.dev.payment_service.stripe.StripeSignatureVerifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class StripeWebhookService {

    private final StripeSignatureVerifier signatureVerifier;
    private final PaymentLedgerService ledger;
    private final JsonMapper jsonMapper;

    /**
     * Đọc trực tiếp JSON thô thay vì deserialize bằng model của stripe-java: model gắn với một
     * phiên bản API cố định, webhook từ tài khoản dùng phiên bản khác sẽ không đọc được object.
     * Ta chỉ cần vài field ổn định (id, type, status, payment_intent).
     */
    public void handle(String payload, String signatureHeader) {
        if (!signatureVerifier.isValid(payload, signatureHeader)) {
            log.warn("Webhook Stripe bị từ chối: chữ ký không hợp lệ");
            throw new AppException(PaymentErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        JsonNode root;
        try {
            root = jsonMapper.readTree(payload);
        } catch (JacksonException ex) {
            throw new AppException(PaymentErrorCode.WEBHOOK_PAYLOAD_INVALID);
        }
        JsonNode id = root.path("id");
        JsonNode type = root.path("type");
        if (!id.isString() || !type.isString()) {
            throw new AppException(PaymentErrorCode.WEBHOOK_PAYLOAD_INVALID);
        }
        boolean processed = ledger.applyWebhookEvent(
                id.stringValue(), type.stringValue(), root.path("data").path("object"));
        if (!processed) {
            log.info("Webhook Stripe lặp, bỏ qua eventId={} type={}", id.stringValue(), type.stringValue());
        }
    }
}
