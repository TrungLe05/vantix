package com.dev.api_gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.gateway")
@Getter
@Setter
public class GatewayAuthProperties {
    // Mỗi phần tử dạng "METHOD /path/pattern", ví dụ "POST /api/auth/login"
    private List<String> publicEndpoints = List.of();
    private List<String> passwordChangeAllowlist = List.of();
}