package com.dev.order_service.device;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * deviceHash của request hiện tại (do {@link DeviceIdFilter} đặt). Ngoài request HTTP (consumer
 * Kafka, job, test gọi service trực tiếp) luôn null — giống "không có thiết bị".
 */
public final class DeviceContext {

    static final String ATTRIBUTE = DeviceContext.class.getName() + ".deviceHash";

    private DeviceContext() {
    }

    public static String currentDeviceHash() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object value = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return value instanceof String hash ? hash : null;
    }
}
