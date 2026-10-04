package com.globalpagegenerator.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Spring Cache configuration backed by Redis.
 *
 * <h2>Design</h2>
 * <ul>
 *   <li><b>JSON serializer</b> — values are stored as JSON via a {@code GenericJackson2JsonRedisSerializer}
 *       configured with our application's {@link ObjectMapper}. This preserves type
 *       information on round-trip so cached {@code PageLayoutDto} records — which
 *       carry nested {@code JsonNode} fields — deserialise correctly.</li>
 *   <li><b>String key serializer</b> — human-readable keys (e.g. {@code "layouts::42"})
 *       make {@code redis-cli} inspection trivial.</li>
 *   <li><b>Per-cache TTL</b> — the {@code layouts} cache uses a 5-minute TTL
 *       (configurable via {@code app.cache.layout-ttl-ms}) so that layout config
 *       edits propagate without a deploy, but the DB is shielded from repeated
 *       identical GETs under load.</li>
 *   <li><b>Default TTL</b> — 1 minute. Caches without an explicit entry fall back
 *       to a conservative value rather than caching forever.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 *   @Cacheable(cacheNames = "layouts", key = "#serviceId")
 *   public PageLayoutDto getLayoutForService(Long serviceId) { ... }
 * }</pre>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** Canonical name of the layout cache. Referenced by {@code LayoutService}. */
    public static final String LAYOUT_CACHE = "layouts";

    /** Default TTL applied to any cache not explicitly listed in {@link #cacheConfigurations}. */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(1);

    /** TTL for the {@code layouts} cache — driven by configuration. */
    private final Duration layoutTtl;

    public CacheConfig(@Value("${app.cache.layout-ttl-ms:300000}") long layoutTtlMs) {
        this.layoutTtl = Duration.ofMillis(layoutTtlMs);
    }

    /**
     * Builds the Redis-backed {@link CacheManager} used by Spring's caching
     * infrastructure ({@code @Cacheable}, {@code @CacheEvict}, etc.).
     *
     * <p>The bean is named {@code cacheManager} so Spring Boot's auto-config
     * picks it up automatically — no explicit annotation override required.
     *
     * @param connectionFactory Lettuce / Jedis factory auto-configured by Spring Boot
     * @param objectMapper      application's shared Jackson {@link ObjectMapper}
     *                          (so cached JSON nodes carry the right JSR-310 / snake-case config)
     */
    @Bean
    public CacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                     ObjectMapper objectMapper) {

        // Build a JSON serializer that reuses our app's ObjectMapper so cached
        // records survive JSR-310 date formatting and snake-case policies.
        GenericJackson2JsonRedisSerializer valueSerializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        // Base configuration — all caches inherit this unless overridden below.
        RedisCacheConfiguration baseConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(DEFAULT_TTL)
                .disableCachingNullValues()
                .computePrefixWith(name -> name + "::")   // "layouts::42"
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer));

        // Per-cache overrides.
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put(LAYOUT_CACHE, baseConfig.entryTtl(layoutTtl));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(baseConfig)
                .withInitialCacheConfigurations(perCache)
                .transactionAware()       // honour ongoing Spring transactions
                .build();
    }
}