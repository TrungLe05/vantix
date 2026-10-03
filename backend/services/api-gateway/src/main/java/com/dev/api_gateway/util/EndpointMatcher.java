package com.dev.api_gateway.util;

import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

public class EndpointMatcher {

    private record Rule(HttpMethod method, PathPattern pattern) {}

    private final List<Rule> rules;

    public EndpointMatcher(List<String> definitions) {
        this.rules = definitions.stream().map(definition -> {
            String[] parts = definition.trim().split("\\s+", 2);
            if (parts.length < 2) {
                // Fail-fast lúc khởi động thay vì âm thầm bỏ sót một rule bảo mật
                throw new IllegalArgumentException("Endpoint phải có dạng 'METHOD /path': " + definition);
            }
            return new Rule(HttpMethod.valueOf(parts[0].toUpperCase()),
                    PathPatternParser.defaultInstance.parse(parts[1]));
        }).toList();
    }

    public boolean matches(HttpMethod method, PathContainer path) {
        return rules.stream().anyMatch(r -> r.method().equals(method) && r.pattern().matches(path));
    }
}