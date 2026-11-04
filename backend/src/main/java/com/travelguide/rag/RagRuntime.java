package com.travelguide.rag;

import com.travelguide.rag.ingestion.KnowledgeLoader;
import com.travelguide.rag.persistence.PgVectorStore;
import com.travelguide.rag.retrieval.EmbeddingProvider;
import com.travelguide.rag.retrieval.Reranker;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * RAG 运行时缓存。
 *
 * <p>知识索引在第一次问答时构建，避免应用启动阶段立即发起大量 Embedding 请求。
 * 构建失败时会保留异常，由导游服务降级到原有本地回答。</p>
 */
public final class RagRuntime {

    private static final String INDEX_SCHEMA_VERSION = "rag-index-v3-fact-sections";

    private final Path knowledgePath;
    private final EmbeddingProvider embeddingProvider;
    private final Reranker reranker;
    private final PgVectorStore vectorStore;
    private volatile RagEngine engine;
    private volatile String indexFingerprint;

    public RagRuntime(
            Path knowledgePath,
            EmbeddingProvider embeddingProvider,
            Reranker reranker
    ) {
        this(knowledgePath, embeddingProvider, reranker, null);
    }

    public RagRuntime(
            Path knowledgePath,
            EmbeddingProvider embeddingProvider,
            Reranker reranker,
            PgVectorStore vectorStore
    ) {
        this.knowledgePath = knowledgePath;
        this.embeddingProvider = Objects.requireNonNull(embeddingProvider);
        this.reranker = Objects.requireNonNull(reranker);
        this.vectorStore = vectorStore;
    }

    public RagEngine engine() {
        RagEngine current = engine;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (engine == null) {
                try {
                    engine = RagEngine.fromDirectory(
                            knowledgePath,
                            embeddingProvider,
                            reranker,
                            vectorStore
                    );
                    indexFingerprint = fingerprint(engine);
                } catch (IOException exception) {
                    throw new IllegalStateException("RAG 知识索引加载失败: " + knowledgePath, exception);
                }
            }
            return engine;
        }
    }

    public int documentCount() {
        return engine().documents().size();
    }

    public String indexFingerprint() {
        engine();
        return indexFingerprint;
    }

    private String fingerprint(RagEngine engine) {
        // 缓存键包含索引协议版本；检索/清洗规则升级后旧回答不会继续命中。
        StringBuilder source = new StringBuilder(INDEX_SCHEMA_VERSION)
                .append('|')
                .append(embeddingProvider.modelId())
                .append('|');
        engine.documents().stream()
                .sorted(java.util.Comparator.comparing(document -> document.metadata().docId()))
                .forEach(document -> source.append(document.metadata().docId())
                        .append(':').append(document.metadata().version())
                        .append(':').append(document.metadata().contentSha256())
                        .append('|'));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) {
                result.append(String.format("%02x", value));
            }
            return result.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("无法计算 RAG 索引指纹", exception);
        }
    }
}
