package com.travelguide.rag.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL Embedding 持久缓存。
 *
 * <p>只缓存向量本身，不改变检索算法；文本和模型版本共同决定缓存身份。</p>
 */
public final class PgEmbeddingCache {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PgEmbeddingCache(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, float[]> load(String modelId) {
        Map<String, float[]> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT content_hash, vector_json FROM rag_embedding_cache WHERE model_id = ?",
                statement -> statement.setString(1, modelId),
                row -> {
                    try {
                        List<Float> values = objectMapper.readValue(
                                row.getString("vector_json"),
                                new TypeReference<>() {
                                }
                        );
                        float[] vector = new float[values.size()];
                        for (int index = 0; index < values.size(); index++) {
                            vector[index] = values.get(index);
                        }
                        result.put(row.getString("content_hash"), vector);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Embedding 缓存解析失败", exception);
                    }
                }
        );
        return result;
    }

    public void saveAll(
            String modelId,
            Map<String, float[]> vectors
    ) {
        Instant now = Instant.now();
        for (Map.Entry<String, float[]> entry : vectors.entrySet()) {
            try {
                String json = objectMapper.writeValueAsString(entry.getValue());
                jdbcTemplate.update(
                        """
                        INSERT INTO rag_embedding_cache(model_id, content_hash, dimensions, vector_json, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (model_id, content_hash)
                        DO UPDATE SET dimensions = EXCLUDED.dimensions,
                                      vector_json = EXCLUDED.vector_json,
                                      updated_at = EXCLUDED.updated_at
                        """,
                        modelId,
                        entry.getKey(),
                        entry.getValue().length,
                        json,
                        java.sql.Timestamp.from(now)
                );
            } catch (Exception exception) {
                throw new IllegalStateException("Embedding 缓存写入失败", exception);
            }
        }
    }

    public String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
