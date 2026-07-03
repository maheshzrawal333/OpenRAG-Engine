package com.maheshz.openrag.engine.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class TenantInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TenantInterceptor.class);
    private static final String TENANT_HEADER = "X-Tenant-ID";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String requestedTenantId = request.getHeader(TENANT_HEADER);

        // 1. Validate Header Presence
        if (requestedTenantId == null || requestedTenantId.isBlank()) {
            log.warn("Security Block: Missing X-Tenant-ID header.");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return false;
        }

        // 🚨 DEV MODE BYPASS:
        // We are temporarily skipping the cryptographic JWT extraction and "allowed_cases"
        // claim validation so you can test the frontend UI immediately.
        // We blindly trust the frontend header for now.

        TenantContextHolder.setTenantId(requestedTenantId);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // Guarantee the thread-local context is wiped to prevent data leaks across HTTP requests
        TenantContextHolder.clear();
    }
}