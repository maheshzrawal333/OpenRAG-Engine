package com.maheshz.ForensiX.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;

/**
 * Enterprise Object Storage Adapter Configuration.
 * <p>
 * ForensiX is designed to operate in highly secure, air-gapped environments where uploading
 * forensic evidence to the public AWS cloud is a severe security violation.
 * <p>
 * This configuration explicitly intercepts Spring's default S3 auto-configuration to construct
 * an S3Client that routes traffic to a local, self-hosted S3-compatible storage array
 * (specifically MinIO). This ensures 100% of raw binary evidence remains on-premise.
 */
@Configuration
public class S3Config {

    // -----------------------------------------------------------
    // ENVIRONMENT INJECTIONS
    // -----------------------------------------------------------
    // Credentials and routing are strictly injected via the environment (or Vault)
    // to prevent hardcoded secrets in the repository, adhering to 12-Factor App methodology.

    @Value("${spring.cloud.aws.s3.endpoint}")
    private String endpoint;

    @Value("${spring.cloud.aws.credentials.access-key}")
    private String accessKey;

    @Value("${spring.cloud.aws.credentials.secret-key}")
    private String secretKey;

    @Value("${spring.cloud.aws.region.static}")
    private String region;

    /**
     * Provisions the primary S3Client bean for the application context.
     * <p>
     * This client is heavily customized to bypass public AWS DNS routing and force
     * local network resolution.
     *
     * @return A thread-safe, immutable S3Client configured for local object storage.
     */
    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credentials = AwsBasicCredentials.create(accessKey, secretKey);

        return S3Client.builder()
                // 1. ENDPOINT OVERRIDE
                // Hijacks the standard AWS routing (e.g., s3.us-east-1.amazonaws.com)
                // and forces the client to resolve to our local Docker network (e.g., http://127.0.0.1:9000).
                .endpointOverride(URI.create(endpoint))

                // 2. STATIC CREDENTIALS
                // Bypasses the default AWS credentials chain (which checks EC2 metadata, profiles, etc.)
                // to explicitly use the injected environment variables, saving startup time.
                .credentialsProvider(StaticCredentialsProvider.create(credentials))

                .region(Region.of(region))

                // -----------------------------------------------------------
                // 3. ARCHITECTURAL CRITICAL: PATH-STYLE ACCESS
                // -----------------------------------------------------------
                // AWS natively uses virtual-hosted style URIs (e.g., https://bucketname.s3.amazonaws.com).
                // However, self-hosted solutions like MinIO cannot dynamically create DNS subdomains
                // for every new bucket on a local network.
                // Forcing path-style (e.g., http://localhost:9000/bucketname) ensures compatibility
                // and prevents DNS resolution failures when the application attempts to upload evidence.
                .forcePathStyle(true)

                .build();
    }
}