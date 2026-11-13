package com.dev.commonlibjwt.jwt;


import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Slf4j
public class JwtUtils {
    /*
     * Utility dùng chung để sign và verify JWT.
     * Phân công:
     *   - User Service  → gọi sign() sau khi authenticate thành công
     *   - API Gateway   → gọi verify() trước khi forward request
     * Claims trong token:
     *   sub  = userId (UUID dạng String)
     *   role = tên role (BUYER / SELLER / ADMIN)
     *   iat  = issued-at
     *   exp  = expiration
     */

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_MCP = "mcp"; // must_change_password
    private static final String CLAIM_TOKEN_TYPE = "token_type";
    private static final String TYPE_ACCESS = "access";
    private static final String TYPE_REFRESH = "refresh";

    private final SecretKey signingKey;
    private final Duration expirationMs;
    private final Duration expirationRefreshMs;
    /**
     * @param secret       Secret dạng String (min 32 ký tự để đủ 256 bit).
     *                     Phải khớp giữa User Service và API Gateway.
     * @param expirationMs Thời gian sống token (ms). Ví dụ: 86_400_000 = 24h.
     */
    public JwtUtils(String secret, Duration expirationMs, Duration expirationRefreshMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
        this.expirationRefreshMs = expirationRefreshMs;

        log.info("expiration-ms access token: {}", expirationMs);
        log.info("expiration-refresh-ms access token: {}", expirationRefreshMs);
        // TẠM THỜI để so sánh secret giữa các service, xóa sau khi xong
        log.info("JWT secret fingerprint: {}, length: {}", Integer.toHexString(secret.hashCode()), secret.length());
    }


    public String sign(String userId, String role, Duration expiration, String email, boolean mcp, String tokenType){
        Instant now = Instant.now();
        Instant expiry = now.plus(expiration);

        UUID jwtID = UUID.randomUUID();

        return Jwts.builder()
                .issuer("Vantix")
                .id(jwtID.toString())
                .subject(userId)
                .claim(CLAIM_ROLE, role)
                .claim(CLAIM_EMAIL, email)
                .claim(CLAIM_MCP, mcp)
                .claim(CLAIM_TOKEN_TYPE, tokenType)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(signingKey)
                .compact();
    }

    public String generateAccessToken(String userId, String role, String email, boolean mcp){
        return sign(userId, role, expirationMs, email, mcp, TYPE_ACCESS);
    }

    public String generateRefreshToken(String userId, String role, String email, boolean mcp){
        return sign(userId, role, expirationRefreshMs, email, mcp, TYPE_REFRESH);
    }

    public JwtClaims verifyAccessToken(String token) {
        return verify(token, TYPE_ACCESS);
    }

    public JwtClaims verifyRefreshToken(String token) {
        return verify(token, TYPE_REFRESH);
    }

    /*
     * Verify và parse token.
     *
     * @param token JWT token string (không kèm "Bearer " prefix)
     * @return JwtClaims chứa userId và role
     * @throws JwtException nếu token invalid, expired, hoặc signature sai
     */
    public JwtClaims verify(String token, String expectedType){
        Claims claims = getClaims(token);
        if (!expectedType.equals(claims.get(CLAIM_TOKEN_TYPE, String.class))) {
            throw new JwtException("Sai loại token, cần: " + expectedType);
        }
        String userId = claims.getSubject();
        String role = claims.get(CLAIM_ROLE, String.class);
        boolean mcp = Boolean.TRUE.equals(claims.get(CLAIM_MCP, Boolean.class));
        return new JwtClaims(userId, role, mcp);
    }

    public Instant getExpiryInstant(String token){
        Claims claims = getClaims(token);
        return claims.getExpiration().toInstant();
    }


    // helper method
    private Claims getClaims(String token){
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
