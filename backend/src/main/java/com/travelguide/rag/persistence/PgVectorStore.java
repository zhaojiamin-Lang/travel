package com.travelguide.rag.persistence;

import com.travelguide.rag.RagModels.KnowledgeChunk;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL + pgvector 的 chunk 向量索引。
 *
 * <p>Embedding 文本缓存只解决“同一文本不重复调用模型”，本类解决真正的相似
 * 向量检索。查询使用余弦距离并由 HNSW 索引加速，知识块 ID 过滤仍保留，
 * 防止已下线或不符合时间条件的事实进入候选。</p>
 */
public final class PgVectorStore {

    private static final int INDEX_DIMENSIONS = 1024;

    private final JdbcTemplate jdbcTemplate;

    public PgVectorStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量同步当前知识版本。返回 false 表示向量维度不受索引支持，调用方应回退内存检索。
     */
    public boolean synchronize(
            String modelId,
            List<KnowledgeChunk> chunks,
            List<float[]> vectors
    ) {
        if (chunks.isEmpty()) {
            return true;
        }
        if (vectors.stream().anyMatch(vector -> vector.length != INDEX_DIMENSIONS)) {
            return false;
        }

        Timestamp now = Timestamp.from(Instant.now());
        String sql = """
                INSERT INTO rag_chunk_embedding(
                    model_id, chunk_id, dimensions, content_hash, embedding, updated_at
                )
                VALUES (?, ?, ?, ?, ?::vector, ?)
                ON CONFLICT (model_id, chunk_id)
                DO UPDATE SET dimensions = EXCLUDED.dimensions,
                              content_hash = EXCLUDED.content_hash,
                              embedding = EXCLUDED.embedding,
                              updated_at = EXCLUDED.updated_at
                """;
        jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                KnowledgeChunk chunk = chunks.get(index);
                statement.setString(1, modelId);
                statement.setString(2, chunk.chunkId());
                statement.setInt(3, INDEX_DIMENSIONS);
                statement.setString(4, sha256(chunk.embeddingText()));
                statement.setString(5, vectorLiteral(vectors.get(index)));
                statement.setTimestamp(6, now);
            }

            @Override
            public int getBatchSize() {
                return chunks.size();
            }
        });
        return true;
    }

    /**
     * 用 ANN 找出当前允许参与检索的 chunk，再交给 Java 侧做 RRF 融合。
     */
    public List<VectorMatch> nearest(
            String modelId,
            float[] queryVector,
            List<String> allowedChunkIds,
            int limit
    ) {
        if (queryVector.length != INDEX_DIMENSIONS) {
            throw new IllegalArgumentException("pgvector 查询维度必须是 " + INDEX_DIMENSIONS);
        }
        if (allowedChunkIds.isEmpty() || limit <= 0) {
            return List.of();
        }

        // 先在纯 HNSW 近邻集上取回更多候选，再按模型和业务过滤。
        // 若把低选择性的 WHERE 直接放在 ANN 外层，规划器可能改用 B-Tree+全量排序。
        String sql = """
                WITH nearest AS MATERIALIZED (
                    SELECT chunk_id, model_id, embedding <=> ?::vector AS distance
                    FROM rag_chunk_embedding
                    ORDER BY embedding <=> ?::vector
                    LIMIT ?
                )
                SELECT chunk_id, distance
                FROM nearest
                WHERE model_id = ?
                  AND chunk_id = ANY(?)
                ORDER BY distance
                LIMIT ?
                """;
        String vector = vectorLiteral(queryVector);
        int fetchSize = Math.max(limit * 8, 256);
        return jdbcTemplate.execute((java.sql.Connection connection) -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, vector);
                statement.setString(2, vector);
                statement.setInt(3, fetchSize);
                statement.setString(4, modelId);
                statement.setArray(
                        5,
                        connection.createArrayOf("text", allowedChunkIds.toArray(String[]::new))
                );
                statement.setInt(6, limit);
                try (var rows = statement.executeQuery()) {
                    List<VectorMatch> matches = new ArrayList<>();
                    while (rows.next()) {
                        matches.add(new VectorMatch(
                                rows.getString("chunk_id"),
                                1.0 - rows.getDouble("distance")
                        ));
                    }
                    return matches;
                }
            }
        });
    }

    private static String vectorLiteral(float[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(Float.toString(value));
        }
        return values.stream().collect(Collectors.joining(",", "[", "]"));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record VectorMatch(String chunkId, double similarity) {
    }
}
