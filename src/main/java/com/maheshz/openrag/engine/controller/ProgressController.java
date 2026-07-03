package com.maheshz.openrag.engine.controller;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.maheshz.openrag.engine.config.RedisPubSubConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@RestController
public class ProgressController {

    private static final Logger log = LoggerFactory.getLogger(ProgressController.class);

    // SENIOR FIX: The timeout is now explicitly defined as a constant
    private static final long SSE_TIMEOUT_MS = 3600000L; // 1 Hour

    private final StringRedisTemplate redisTemplate;
    private final RedisMessageListenerContainer redisListenerContainer;

    // Maps Job ID to the active SSE Connection on THIS specific server node
    private final Map<String, SseEmitter> localEmitters = new ConcurrentHashMap<>();

    // Maps Job ID to the active Redis Subscription on THIS specific server node
    private final Map<String, MessageListener> localListeners = new ConcurrentHashMap<>();

    // We keep Caffeine for fast, local caching of recent messages in case a client reconnects to the same node
    private final Cache<String, List<String>> messageCache = Caffeine.newBuilder()
            .expireAfterWrite(2, TimeUnit.HOURS)
            .maximumSize(5000)
            .build();

    public ProgressController(StringRedisTemplate redisTemplate, RedisMessageListenerContainer redisListenerContainer) {
        this.redisTemplate = redisTemplate;
        this.redisListenerContainer = redisListenerContainer;
    }

    /**
     * Called by the Frontend UI to open a stream.
     */
    @GetMapping("/api/jobs/{jobId}/stream")
    public SseEmitter streamProgress(@PathVariable String jobId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        localEmitters.put(jobId, emitter);

        // 1. Send any cached messages immediately upon connection
        sendCachedMessages(jobId, emitter);

        // 2. Subscribe THIS server node to the Redis channel for this specific job
        subscribeToRedisChannel(jobId, emitter);

        // 3. Register lifecycle hooks to clean up local resources
        emitter.onCompletion(() -> cleanupConnection(jobId));
        emitter.onTimeout(() -> cleanupConnection(jobId));
        emitter.onError((e) -> cleanupConnection(jobId));

        return emitter;
    }

    /**
     * Called by the AsyncIngestionWorker (which could be running on ANY node).
     * We no longer emit directly to the map; we publish to the cluster.
     */
    public void emitProgress(String jobId, String message) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, message);
    }

    /**
     * Called by the AsyncIngestionWorker when the job finishes.
     */
    public void completeEmitter(String jobId) {
        String channel = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;
        redisTemplate.convertAndSend(channel, "Complete");
    }

    // ==========================================
    // PRIVATE INFRASTRUCTURE HELPERS
    // ==========================================

    private void subscribeToRedisChannel(String jobId, SseEmitter emitter) {
        String channelName = RedisPubSubConfig.PROGRESS_TOPIC_PREFIX + jobId;

        MessageListener listener = (Message message, byte[] pattern) -> {
            String payload = new String(message.getBody(), StandardCharsets.UTF_8);

            // Fast local cache for reconnection resilience
            cacheMessageLocally(jobId, payload);

            try {
                emitter.send(SseEmitter.event().name("progress").data(payload));
                if ("Complete".equals(payload)) {
                    emitter.complete();
                    cleanupConnection(jobId);
                }
            } catch (Exception e) {
                log.debug("Client disconnected during emit for job {}", jobId);
                cleanupConnection(jobId);
            }
        };

        localListeners.put(jobId, listener);
        redisListenerContainer.addMessageListener(listener, new ChannelTopic(channelName));
    }

    private void cacheMessageLocally(String jobId, String message) {
        List<String> logs = messageCache.get(jobId, k -> new CopyOnWriteArrayList<>());
        if (logs != null) {
            logs.add(message);
        }
    }

    private void sendCachedMessages(String jobId, SseEmitter emitter) {
        List<String> cachedMessages = messageCache.getIfPresent(jobId);
        if (cachedMessages != null) {
            for (String msg : cachedMessages) {
                try {
                    emitter.send(SseEmitter.event().name("progress").data(msg));
                } catch (Exception e) {
                    log.warn("Failed to send cached progress to client for job {}", jobId);
                    cleanupConnection(jobId);
                    return;
                }
            }
        }
    }

    private void cleanupConnection(String jobId) {
        localEmitters.remove(jobId);

        MessageListener listener = localListeners.remove(jobId);
        if (listener != null) {
            redisListenerContainer.removeMessageListener(listener);
        }
    }
}