package com.travelguide.rag.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.web.ApiDtos.AskResponse;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Optional;

/**
 * Redis 回答缓存。
 *
 * <p>天气、实时状态和含时间词的问题不进入缓存；普通导游问答默认缓存 10 分钟。</p>
 */
public final class RagAnswerCache {

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final String namespace;

    public RagAnswerCache(
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            Duration ttl,
            String namespace
    ) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.namespace = namespace;
    }

    public Optional<AskResponse> get(String key) {
        try {
            String value = redis.opsForValue().get(namespace + key);
            return value == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(value, AskResponse.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    public void put(String key, AskResponse response) {
        try {
            redis.opsForValue().set(
                    namespace + key,
                    objectMapper.writeValueAsString(response),
                    ttl
            );
        } catch (Exception ignored) {
            // 缓存不可用不能影响导游主链路。
        }
    }

    public String key(
            String userId,
            String currentPoiId,
            String question,
            String indexFingerprint
    ) {
        String source = String.join("|",
                userId == null ? "anonymous" : userId,
                currentPoiId == null ? "" : currentPoiId,
                question.strip(),
                indexFingerprint
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            return Integer.toHexString(source.hashCode());
        }
    }

    public boolean cacheable(String question) {
        return !question.matches(".*(现在|今天|明天|后天|实时|天气|下雨|降雨|气温|台风|上次|刚才).*");
    }
}
