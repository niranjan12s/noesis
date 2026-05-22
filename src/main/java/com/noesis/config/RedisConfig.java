package com.noesis.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for node/edge caching during graph traversal.
 *
 * <p>Uses a {@link RedisTemplate} with String keys and JSON-serialised values,
 * keeping the cache data human-readable for debugging.</p>
 */
@Configuration
public class RedisConfig {

    /** Default TTL for cached graph nodes (5 minutes). */
    public static final Duration DEFAULT_NODE_TTL = Duration.ofMinutes(5);

    /**
     * Configures a {@link RedisTemplate} with String keys and JSON values.
     *
     * @param connectionFactory auto-configured Redis connection factory
     * @return configured template
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}
