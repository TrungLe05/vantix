package com.dev.api_gateway;

import com.dev.commonlibjwt.jwt.JwtUtils;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Chạy gateway thật (Netty, port ngẫu nhiên) trước một HTTP stub đóng vai service phía sau.
 * Stub trả lại header X-User-Id/X-User-Role nhận được để kiểm tra gateway inject/xóa header đúng.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "FRONTEND_ORIGIN=" + GatewayAuthAndCorsIntegrationTest.ALLOWED_ORIGIN)
class GatewayAuthAndCorsIntegrationTest {

    // Sinh mỗi lần chạy: không để chuỗi giống secret trong repo
    private static final String SECRET = UUID.randomUUID() + "-" + UUID.randomUUID();
    static final String ALLOWED_ORIGIN = "http://localhost:3000";

    private static final HttpServer DOWNSTREAM = startDownstreamStub();

    @Autowired
    private Environment environment;

    private final JwtUtils jwt = new JwtUtils(SECRET, Duration.ofMinutes(15), Duration.ofDays(7));
    private WebTestClient client;

    @DynamicPropertySource
    static void routeToStub(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret-key", () -> SECRET);
        String stub = "http://localhost:" + DOWNSTREAM.getAddress().getPort();
        // Spring Boot không merge list theo index giữa các nguồn cấu hình: khai báo lại trọn route cần dùng
        String prefix = "spring.cloud.gateway.server.webflux.routes";
        registry.add(prefix + "[0].id", () -> "user-service");
        registry.add(prefix + "[0].uri", () -> stub);
        registry.add(prefix + "[0].predicates[0]", () -> "Path=/api/users/**,/api/auth/**");
        registry.add(prefix + "[1].id", () -> "order-service");
        registry.add(prefix + "[1].uri", () -> stub);
        registry.add(prefix + "[1].predicates[0]", () -> "Path=/api/orders/**");
    }

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + environment.getProperty("local.server.port"))
                .build();
    }

    @AfterAll
    static void stopStub() {
        DOWNSTREAM.stop(0);
    }

    @Test
    void gatewayLogsAtInfoUnlessDevProfileIsActive() {
        assertThat(environment.getProperty("logging.level.com.dev.api_gateway")).isEqualTo("INFO");
    }

    // ---------- Preflight CORS ----------

    @Test
    void preflightOnProtectedRouteIsAnsweredWithoutToken() {
        client.method(HttpMethod.OPTIONS).uri("/api/orders")
                .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN)
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }

    @Test
    void preflightFromUnknownOriginIsRejected() {
        client.method(HttpMethod.OPTIONS).uri("/api/orders")
                .header(HttpHeaders.ORIGIN, "http://evil.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    // ---------- Xác thực access token ----------

    @Test
    void missingTokenReturns9001() {
        client.get().uri("/api/orders").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(9001);
    }

    @Test
    void tamperedTokenReturns9002() {
        String token = jwt.generateAccessToken("u-1", "BUYER", "a@example.com", false);
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token + "x")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(9002);
    }

    @Test
    void refreshTokenIsNotAcceptedAsAccessToken() {
        String refresh = jwt.generateRefreshToken("u-1", "BUYER", "a@example.com", false);
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + refresh)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(9002);
    }

    @Test
    void expiredTokenReturns9003() {
        String expired = jwt.sign("u-1", "BUYER", Duration.ofSeconds(-30), "a@example.com", false, "access");
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo(9003);
    }

    @Test
    void validTokenIsForwardedWithInjectedIdentity() {
        String token = jwt.generateAccessToken("u-42", "BUYER", "a@example.com", false);
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                // Header giả mạo phải bị ghi đè bằng giá trị từ token
                .header("X-User-Id", "attacker")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("id=u-42;role=BUYER");
    }

    @Test
    void spoofedIdentityHeadersAreStrippedOnPublicEndpoint() {
        client.post().uri("/api/auth/login")
                .header("X-User-Id", "attacker")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("id=null;role=null");
    }

    // ---------- Allowlist khi bắt buộc đổi mật khẩu (claim mcp) ----------

    @Test
    void mcpTokenIsBlockedOutsideAllowlist() {
        String token = jwt.generateAccessToken("staff-1", "STAFF", "s@example.com", true);
        client.get().uri("/api/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(9004);
    }

    @Test
    void mcpTokenIsAllowedOnAllowlistedEndpoints() {
        String token = jwt.generateAccessToken("staff-1", "STAFF", "s@example.com", true);
        client.get().uri("/api/users/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("id=staff-1;role=STAFF");
        client.patch().uri("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void mcpAllowlistMatchesMethodAsWellAsPath() {
        // GET /api/auth/change-password không có trong allowlist (chỉ PATCH)
        String token = jwt.generateAccessToken("staff-1", "STAFF", "s@example.com", true);
        client.get().uri("/api/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo(9004);
    }

    private static HttpServer startDownstreamStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String body = "id=" + exchange.getRequestHeaders().getFirst("X-User-Id")
                        + ";role=" + exchange.getRequestHeaders().getFirst("X-User-Role");
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
