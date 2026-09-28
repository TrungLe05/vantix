package com.dev.commonlib_security.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Objects;
import java.util.UUID;

public class SecurityUtils {

    public SecurityUtils() {}

    public static UUID getCurrentUserId(){
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if(auth == null) return null;
        return UUID.fromString((String) Objects.requireNonNull(auth.getPrincipal()));
    }

    public static String getCurrentUserRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().isEmpty()) return null;
        return auth.getAuthorities().iterator().next().getAuthority();
    }
}
