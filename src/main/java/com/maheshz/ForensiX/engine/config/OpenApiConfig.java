package com.maheshz.ForensiX.engine.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Enterprise API Governance and Documentation Configuration.
 * <p>
 * This configuration auto-generates the OpenAPI (Swagger) specification for the ForensiX platform.
 * Beyond simply documenting endpoints, this class enforces our architectural security constraints
 * at the contract level. By explicitly declaring the tenant isolation headers globally, we ensure
 * that any generated API clients (e.g., via Swagger Codegen) or developers using the Swagger UI
 * cannot bypass or "forget" the multi-tenant security model.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Provisions the OpenAPI specification bean.
     * Defines the API metadata, lifecycle versioning, and structural security constraints.
     *
     * @return A configured OpenAPI object mapped by springdoc-openapi.
     */
    @Bean
    public OpenAPI forensicEngineOpenAPI() {
        return new OpenAPI()
                // -----------------------------------------------------------
                // 1. API METADATA & GOVERNANCE
                // -----------------------------------------------------------
                // Establishes the living contract for the API. This is critical for frontend
                // integration and potential third-party forensic tool integrations in the future.
                .info(new Info().title("ForensiX Enterprise Engine")
                        .description("Enterprise API for asynchronous ingestion and RAG-based analysis of unstructured forensic data.")
                        .version("v1.0.0")
                        .contact(new Contact().name("Mahesh").url("https://github.com/mahesh")))

                // -----------------------------------------------------------
                // 2. TENANT ISOLATION SECURITY SCHEME
                // -----------------------------------------------------------
                // We model the 'X-Tenant-ID' as an API Key injected into the HTTP Header.
                // While not technically a cryptographic JWT, in our architecture, it acts
                // as the ultimate authorization boundary. Registering it as a SecurityScheme
                // forces the Swagger UI to prompt the user (via the green "Authorize" button)
                // for this Case ID before allowing any requests to execute.
                .components(new Components()
                        .addSecuritySchemes("Case-Isolation-Header", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Tenant-ID")
                                .description("Enter the active Investigation Case ID (e.g., 'CASE-405') to isolate data access.")))

                // -----------------------------------------------------------
                // 3. GLOBAL ENFORCEMENT
                // -----------------------------------------------------------
                // By adding this as a global SecurityRequirement, EVERY endpoint in the system
                // inherits this mandate. This prevents human error where a new controller is added
                // later but the developer forgets to document the required tenant header.
                .addSecurityItem(new SecurityRequirement().addList("Case-Isolation-Header"));
    }
}