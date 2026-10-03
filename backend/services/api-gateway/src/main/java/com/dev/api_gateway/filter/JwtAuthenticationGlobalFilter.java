package com.dev.api_gateway.filter;

import com.dev.api_gateway.config.GatewayAuthProperties;
import com.dev.api_gateway.exception.GatewayErrorCode;
import com.dev.api_gateway.util.EndpointMatcher;
import com.dev.commonlib_api_response.exception.ErrorCode;

import com.dev.commonlibjwt.jwt.JwtClaims;
import com.dev.commonlibjwt.jwt.JwtUtils;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
@Component
public class JwtAuthenticationGlobalFilter implements GlobalFilter, Ordered {

    private static final String HEADER_USER_ID = "X-User-Id";
    private static final String HEADER_USER_ROLE = "X-User-Role";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtils jwtUtils;
    private final JsonMapper jsonMapper;
    private final EndpointMatcher publicEndpoints;
    private final EndpointMatcher passwordChangeAllowlist;

    public JwtAuthenticationGlobalFilter(JwtUtils jwtUtils, JsonMapper jsonMapper, GatewayAuthProperties properties) {
        this.jwtUtils = jwtUtils;
        this.jsonMapper = jsonMapper;
        this.publicEndpoints = new EndpointMatcher(properties.getPublicEndpoints());
        this.passwordChangeAllowlist = new EndpointMatcher(properties.getPasswordChangeAllowlist());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Luôn xóa header định danh do client gửi, kể cả ở route công khai:
        // service phía sau tin tuyệt đối vào 2 header này nên chỉ gateway được phép set
        ServerHttpRequest.Builder builder = request.mutate().headers(headers -> {
            headers.remove(HEADER_USER_ID);
            headers.remove(HEADER_USER_ROLE);
        });

        PathContainer path = request.getPath().pathWithinApplication();
        HttpMethod method = request.getMethod();

        if (publicEndpoints.matches(method, path)) {
            return chain.filter(exchange.mutate().request(builder.build()).build());
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return writeError(exchange, GatewayErrorCode.TOKEN_MISSING);
        }

        JwtClaims claims;
        try {
            claims = jwtUtils.verifyAccessToken(authorization.substring(BEARER_PREFIX.length()).trim());
        } catch (ExpiredJwtException e) {
            // Tách riêng để frontend biết chỉ khi hết hạn mới nên gọi refresh
            return writeError(exchange, GatewayErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Access token bị từ chối: {}", e.getMessage());
            return writeError(exchange, GatewayErrorCode.TOKEN_INVALID);
        }

        if (claims.userId() == null || claims.role() == null) {
            return writeError(exchange, GatewayErrorCode.TOKEN_INVALID);
        }

        // STAFF mới tạo phải đổi mật khẩu trước, chỉ cho gọi vài API trong allowlist
        if (claims.mcp() && !passwordChangeAllowlist.matches(method, path)) {
            return writeError(exchange, GatewayErrorCode.PASSWORD_CHANGE_REQUIRED);
        }

        builder.header(HEADER_USER_ID, claims.userId()).header(HEADER_USER_ROLE, claims.role());
        log.debug("HEADER_USER_ID: {}, HEADER_USER_ROLE: {}", claims.userId(), claims.role());
        return chain.filter(exchange.mutate().request(builder.build()).build());
    }

    private Mono<Void> writeError(ServerWebExchange exchange, ErrorCode errorCode) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(errorCode.getHttpStatus());
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = jsonMapper.writeValueAsBytes(
                com.dev.commonlib_api_response.dto.response.ApiResponse.<Void>builder()
                        .code(errorCode.getCode())
                        .message(errorCode.getMessage())
                        .build()
        );
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE; // chạy đầu tiên, trước mọi filter khác của gateway
    }
}