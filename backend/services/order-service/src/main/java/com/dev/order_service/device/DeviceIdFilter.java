package com.dev.order_service.device;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Đọc header {@code X-Device-Id} một lần mỗi request, chỉ giữ lại hash (D37). */
@Component
@RequiredArgsConstructor
public class DeviceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Device-Id";

    private final DeviceHasher deviceHasher;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        deviceHasher.hash(request.getHeader(HEADER))
                .ifPresent(hash -> request.setAttribute(DeviceContext.ATTRIBUTE, hash));
        filterChain.doFilter(request, response);
    }
}
