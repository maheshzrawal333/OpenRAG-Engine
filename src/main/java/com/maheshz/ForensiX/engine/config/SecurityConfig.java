package com.maheshz.ForensiX.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Enterprise Security Configuration (Development Profile).
 * <p>
 * This class dictates the global authentication and authorization boundaries for the ForensiX Engine.
 * Currently, the system is explicitly operating in a 'Development Bypass Mode' to unblock UI/UX
 * iteration and API testing without the friction of a full Identity Provider (IdP) integration.
 * <p>
 * CRITICAL: Before deploying to a staging or production environment, the {@code permitAll()}
 * directive must be removed and the OAuth2 Resource Server must be enabled.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Constructs the primary Spring Security filter chain.
     * Defines session lifecycle, cross-site protections, and endpoint access rules.
     *
     * @param http The HttpSecurity builder provided by Spring.
     * @return The finalized SecurityFilterChain bean.
     * @throws Exception if the configuration fails to compile.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // -----------------------------------------------------------
                // 1. CROSS-SITE REQUEST FORGERY (CSRF)
                // -----------------------------------------------------------
                // CSRF attacks rely on browsers automatically attaching session cookies to requests.
                // Because our ForensiX API is strictly stateless and will eventually rely on
                // Authorization Bearer tokens (JWTs) rather than cookies, CSRF protection is
                // structurally redundant and safely disabled.
                .csrf(AbstractHttpConfigurer::disable)

                // -----------------------------------------------------------
                // 2. SESSION LIFECYCLE
                // -----------------------------------------------------------
                // Enforces a strictly stateless architecture. The Tomcat server will never create
                // or maintain an HTTP Session (`JSESSIONID`) in memory.
                // This is a prerequisite for true horizontal scalability, ensuring that any
                // ForensiX instance can handle any API request without "sticky sessions".
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // -----------------------------------------------------------
                // 3. ENDPOINT AUTHORIZATION (DEVELOPMENT BYPASS)
                // -----------------------------------------------------------
                // 🚨 WARNING: TEMPORARY DEVELOPMENT CONFIGURATION 🚨
                // This explicitly exposes the entire RAG pipeline to unauthenticated requests.
                // Currently, multi-tenant isolation relies solely on the client voluntarily
                // passing the correct `X-Tenant-ID` header.
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll()
                );

        // -----------------------------------------------------------
        // 4. PRODUCTION MIGRATION PATH (OAUTH2 JWT)
        // -----------------------------------------------------------
        // To secure this application for production:
        // 1. Uncomment the block below.
        // 2. Change `.permitAll()` above to `.authenticated()`.
        // 3. Add `spring.security.oauth2.resourceserver.jwt.issuer-uri=YOUR_IDP_URL` to application.properties.
        // 4. Update the TenantInterceptor to extract the `X-Tenant-ID` securely from the JWT claims
        //    rather than trusting a raw HTTP header.

        /*
        http.oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> {})
        );
        */

        return http.build();
    }
}