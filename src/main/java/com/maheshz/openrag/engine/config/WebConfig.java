package com.maheshz.openrag.engine.config;

import com.maheshz.openrag.engine.security.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public WebConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/admin/tenants",     // UI Bootstrap Cases
                        "/api/admin/models",      // UI Bootstrap Models
                        "/api/jobs/**/stream"     // SSE Progress Stream
                );
    }

    // SENIOR FIX: The Missing Async Timeout Override!
    // Because implementing WebMvcConfigurer overrides auto-configuration,
    // we must manually tell Spring to allow 1-hour SSE connections.
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(3600000L); // 1 Hour (3,600,000 ms)
    }
}