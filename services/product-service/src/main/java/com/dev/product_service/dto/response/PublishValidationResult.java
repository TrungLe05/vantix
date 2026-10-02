package com.dev.product_service.dto.response;

import java.util.List;

public record PublishValidationResult(List<String> reasons) {
}