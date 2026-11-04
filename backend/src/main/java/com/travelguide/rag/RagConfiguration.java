package com.travelguide.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.rag.retrieval.EmbeddingProvider;
import com.travelguide.rag.retrieval.CachedEmbeddingProvider;
import com.travelguide.rag.retrieval.DashScopeReranker;
import com.travelguide.rag.retrieval.HashingEmbeddingProvider;
import com.travelguide.rag.retrieval.HeuristicReranker;
import com.travelguide.rag.retrieval.OpenAiCompatibleEmbeddingProvider;
import com.travelguide.rag.retrieval.Reranker;
import com.travelguide.rag.persistence.PgEmbeddingCache;
import com.travelguide.rag.persistence.PgVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * RAG 基础组件装配。
 */
@Configuration
public class RagConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmbeddingProvider.class)
    public EmbeddingProvider embeddingProvider(
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            @Value("${travel.ai.api-key:}") String apiKey,
            @Value("${travel.ai.base-url:https://api.openai.com}") String baseUrl,
            @Value("${travel.ai.embedding-model:text-embedding-v3}") String model,
            @Value("${travel.rag.embedding-cache-enabled:true}") boolean cacheEnabled
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            // 无模型 Key 时使用确定性本地向量，保证开发环境和测试可运行。
            return new HashingEmbeddingProvider(512);
        }
        EmbeddingProvider provider = new OpenAiCompatibleEmbeddingProvider(
                baseUrl,
                apiKey,
                model,
                objectMapper
        );
        return cacheEnabled
                ? new CachedEmbeddingProvider(
                        provider,
                        new PgEmbeddingCache(jdbcTemplate, objectMapper)
                )
                : provider;
    }

    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    public Reranker reranker(
            ObjectMapper objectMapper,
            @Value("${travel.ai.api-key:}") String apiKey,
            @Value("${travel.ai.rerank-base-url:https://dashscope.aliyuncs.com/api/v1}") String baseUrl,
            @Value("${travel.ai.rerank-model:gte-rerank-v2}") String model
    ) {
        Reranker local = new HeuristicReranker();
        if (apiKey == null || apiKey.isBlank()) {
            return local;
        }
        return new DashScopeReranker(baseUrl, apiKey, model, objectMapper, local);
    }

    @Bean
    @ConditionalOnProperty(
            name = "travel.rag.pgvector-enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    public PgVectorStore pgVectorStore(JdbcTemplate jdbcTemplate) {
        return new PgVectorStore(jdbcTemplate);
    }

    @Bean
    public RagRuntime ragRuntime(
            EmbeddingProvider embeddingProvider,
            Reranker reranker,
            ObjectProvider<PgVectorStore> vectorStore,
            @Value("${travel.rag.knowledge-path:knowledge}") String knowledgePath
    ) {
        return new RagRuntime(
                resolveKnowledgePath(knowledgePath),
                embeddingProvider,
                reranker,
                vectorStore.getIfAvailable()
        );
    }

    static Path resolveKnowledgePath(String configuredPath) {
        Path configured = Path.of(configuredPath == null || configuredPath.isBlank()
                ? "knowledge"
                : configuredPath);
        List<Path> candidates = List.of(
                configured,
                Path.of("knowledge"),
                Path.of("..", "knowledge")
        );
        return candidates.stream()
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(path -> Files.isDirectory(path.resolve("poi")))
                .findFirst()
                .orElse(configured.toAbsolutePath().normalize());
    }
}
