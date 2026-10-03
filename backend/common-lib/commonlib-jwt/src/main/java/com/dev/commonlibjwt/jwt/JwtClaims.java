package com.dev.commonlibjwt.jwt;

/**
 * Claims được bóc tách từ JWT token sau khi verify thành công.
 * Được truyền từ API Gateway xuống các service qua header:
 *   X-User-Id   → userId
 *   X-User-Role → role
 */
public record JwtClaims(
        String userId,
        String role,
        boolean mcp
) {}
