package com.maheshz.ForensiX.engine.config;

import com.maheshz.ForensiX.engine.security.TenantInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enterprise HTTP Request Lifecycle and Interceptor Configuration.
 * <p>
 * This class serves two critical architectural purposes:
 * 1. Enforcing the Multi-Tenant Security Perimeter: It binds our custom TenantInterceptor
 * to the incoming HTTP request cycle, ensuring no request reaches a controller without a valid Case ID.
 * 2. Asynchronous Connection Management: It overrides Spring MVC's default timeout limits to
 * support the massive, long-lived Server-Sent Event (SSE) streams required for heavy AI processing.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TenantInterceptor tenantInterceptor;

    public WebConfig(TenantInterceptor tenantInterceptor) {
        this.tenantInterceptor = tenantInterceptor;
    }

    /**
     * Registers HTTP interceptors to validate and mutate incoming requests before they reach controllers.
     *
     * @param registry The Spring InterceptorRegistry.
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantInterceptor)
                // -----------------------------------------------------------
                // 1. THE GLOBAL PERIMETER
                // -----------------------------------------------------------
                // Apply the tenant isolation check to all data-bearing API endpoints.
                .addPathPatterns("/api/**")

                // -----------------------------------------------------------
                // 2. THE BYPASS LIST (WHITELIST)
                // -----------------------------------------------------------
                // Explicitly exclude endpoints that mathematically CANNOT have a Tenant ID yet,
                // or where applying standard interceptor logic would break the underlying protocol.
                .excludePathPatterns(
                        // UI Bootstrap: The frontend must fetch the list of available cases (tenants)
                        // and AI models *before* the user has selected a case.
                        "/api/admin/tenants",
                        "/api/admin/models",

                        // Async Telemetry: SSE streams manage their lifecycle differently.
                        // Interceptors can prematurely lock or kill the DispatcherServlet's
                        // async boundary. We exclude the stream endpoint here and manage its
                        // security/scoping directly within the Job ID and Controller.
                        "/api/jobs/**/stream"
                );
    }

    /**
     * Configures the behavior for asynchronous request processing (DeferredResult, SseEmitter, etc.).
     *
     * @param configurer The Spring MVC Async Support Configurer.
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // -----------------------------------------------------------
        // ARCHITECTURAL HOTFIX: LONG-LIVED SSE TIMEOUTS
        // -----------------------------------------------------------
        // By default, Spring MVC aggressively kills asynchronous requests after ~30 seconds.
        // In the ForensiX engine, parsing a 1,000-page PDF or waiting for a local 16B parameter
        // LLM to deduce a complex RAG query can take several minutes.
        // If we do not explicitly override this, Spring will silently sever the SSE connection,
        // causing the frontend UI to stall out at "Processing..." indefinitely.
        //
        // Here we extend the absolute maximum lifespan of an async HTTP connection to 1 hour,
        // deferring actual timeout logic to our specific Controller implementations or the UI client.
        configurer.setDefaultTimeout(3600000L); // 1 Hour (3,600,000 ms)
    }
}