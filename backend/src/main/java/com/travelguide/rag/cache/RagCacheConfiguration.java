package com.travelguide.rag.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class RagCacheConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "travel.rag",
            name = "answer-cache-enabled",
            havingValue = "true",
            matchIfMissing = false
    )
    public RagAnswerCache ragAnswerCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${travel.rag.answer-cache-ttl-seconds:600}") long ttlSeconds
    ) {
        return new RagAnswerCache(
                redisTemplate,
                objectMapper,
                Duration.ofSeconds(ttlSeconds),
                "travel:rag:answer:"
        );
    }
}
