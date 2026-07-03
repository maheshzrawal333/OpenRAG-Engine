package com.maheshz.ForensiX.engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Distributed Telemetry and Real-Time Event Broker Configuration.
 * <p>
 * In the ForensiX engine, parsing and vectorizing massive forensic files (like 500-page PDFs
 * or 100k-row CSVs) happens asynchronously on background threads. We use Redis Pub/Sub as an
 * ultra-fast, lightweight message broker to pipe progress updates from those background workers
 * directly to the frontend UI via Server-Sent Events (SSE), completely bypassing the primary database.
 */
@Configuration
public class RedisPubSubConfig {

    /**
     * The routing prefix for dynamic ingestion channels.
     * Rather than dumping all updates into a single global channel, we append a unique Job ID
     * to this prefix (e.g., "job-progress-74e41beb..."). This guarantees that a user's browser
     * only subscribes to the telemetry of their specific file upload, ensuring multi-tenant privacy.
     */
    public static final String PROGRESS_TOPIC_PREFIX = "job-progress-";

    /**
     * Provisions the publisher client for dispatching events.
     *
     * @param connectionFactory Provided by Spring Boot's auto-configuration (Lettuce/Jedis).
     * @return A strictly typed String-to-String template.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // -----------------------------------------------------------
        // SERIALIZATION STRATEGY
        // -----------------------------------------------------------
        // By default, Spring Boot uses JdkSerializationRedisSerializer, which injects heavy,
        // unreadable binary headers into Redis payloads.
        // We explicitly force UTF-8 String serialization for both Keys and Values. This ensures:
        // 1. Extreme performance (no reflection or JSON-tree parsing overhead).
        // 2. Cross-platform interoperability (Node.js or Python microservices could easily read it).
        // 3. Debuggability (You can read the exact payload in the `redis-cli`).
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());

        return template;
    }

    /**
     * Provisions the asynchronous listener container for subscribing to events.
     * <p>
     * This acts as the "Sub" engine in our Pub/Sub architecture. It manages the lifecycle,
     * thread allocation, and connection multiplexing for any service that needs to listen to
     * a Redis channel (like our SSE Controller endpoints waiting for progress ticks).
     *
     * @param connectionFactory Provided by Spring Boot.
     * @return The lifecycle-managed container.
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Note: Thread pooling for listeners is currently delegated to the default task executor.
        // If the number of concurrent SSE connections scales massively, we may need to inject
        // a custom ThreadPoolTaskExecutor here to prevent listener starvation.

        return container;
    }
}