package com.maheshz.openrag.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 1. Disable CSRF (Cross-Site Request Forgery)
                .csrf(AbstractHttpConfigurer::disable)

                // 2. Enforce Stateless Sessions
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )

                // 3. Endpoint Authorization Rules (DEV MODE BYPASS)
                .authorizeHttpRequests(auth -> auth
                        // 🚨 DEV MODE: Temporarily allow ALL traffic so the frontend UI can connect
                        // without needing a Keycloak/Auth0 JWT setup yet.
                        .anyRequest().permitAll()
                );

        // 🚨 DEV MODE: Commented out the JWT Validator so Spring doesn't crash
        // looking for an Identity Provider URL in your application.properties
                /*
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> {})
                );
                */

        return http.build();
    }
}