package com.travelguide.rag;

import com.travelguide.rag.RagModels.ChunkedDocument;
import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.ParentChunk;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RagSearchResult;
import com.travelguide.rag.RagModels.RetrievalOptions;
import com.travelguide.rag.RagModels.RetrievalProfile;
import com.travelguide.rag.ingestion.KnowledgeChunker;
import com.travelguide.rag.ingestion.KnowledgeLoader;
import com.travelguide.rag.query.QueryRewriteService;
import com.travelguide.rag.retrieval.EmbeddingProvider;
import com.travelguide.rag.retrieval.HybridRetriever;
import com.travelguide.rag.retrieval.Reranker;
import com.travelguide.rag.persistence.PgVectorStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RAG 查询门面。
 *
 * <p>当前类不注册 Spring Bean，也不改动已有 GuideService。接入现有导游链路时
 * 只需构造一次实例，再把 QueryContext 从当前导游会话转换进来。</p>
 */
public final class RagEngine {

    private final List<KnowledgeDocument> documents;
    private final List<ChunkedDocument> chunkedDocuments;
    private final List<KnowledgeChunk> chunks;
    private final Map<String, ParentChunk> parentsById;
    private final QueryRewriteService queryRewriteService;
    private final HybridRetriever hybridRetriever;
    private final Reranker reranker;

    public RagEngine(
            List<KnowledgeDocument> documents,
            EmbeddingProvider embeddingProvider,
            Reranker reranker
    ) {
        this(documents, RagModels.ChunkingOptions.defaults(), embeddingProvider, reranker, null);
    }

    public RagEngine(
            List<KnowledgeDocument> documents,
            EmbeddingProvider embeddingProvider,
            Reranker reranker,
            PgVectorStore vectorStore
    ) {
        this(documents, RagModels.ChunkingOptions.defaults(), embeddingProvider, reranker, vectorStore);
    }

    public RagEngine(
            List<KnowledgeDocument> documents,
            RagModels.ChunkingOptions chunkingOptions,
            EmbeddingProvider embeddingProvider,
            Reranker reranker
    ) {
        this(documents, chunkingOptions, embeddingProvider, reranker, null);
    }

    public RagEngine(
            List<KnowledgeDocument> documents,
            RagModels.ChunkingOptions chunkingOptions,
            EmbeddingProvider embeddingProvider,
            Reranker reranker,
            PgVectorStore vectorStore
    ) {
        this.documents = List.copyOf(documents);
        this.chunkedDocuments = new KnowledgeChunker(chunkingOptions).chunkAll(documents);
        this.chunks = chunkedDocuments.stream()
                .flatMap(document -> document.chunks().stream())
                .toList();
        this.parentsById = new LinkedHashMap<>();
        chunkedDocuments.stream()
                .flatMap(document -> document.parents().stream())
                .forEach(parent -> parentsById.put(parent.parentId(), parent));
        this.queryRewriteService = new QueryRewriteService(documents);
        this.hybridRetriever = new HybridRetriever(chunks, embeddingProvider, vectorStore);
        this.reranker = reranker;
    }

    public static RagEngine fromDirectory(
            Path knowledgeRoot,
            EmbeddingProvider embeddingProvider,
            Reranker reranker
    ) throws IOException {
        return fromDirectory(knowledgeRoot, embeddingProvider, reranker, null);
    }

    public static RagEngine fromDirectory(
            Path knowledgeRoot,
            EmbeddingProvider embeddingProvider,
            Reranker reranker,
            PgVectorStore vectorStore
    ) throws IOException {
        List<KnowledgeDocument> documents = new KnowledgeLoader().loadDirectory(knowledgeRoot);
        return new RagEngine(documents, embeddingProvider, reranker, vectorStore);
    }

    public RagSearchResult search(String question, QueryContext context) {
        return search(question, context, RetrievalOptions.defaults(context.asOf()));
    }

    public RagSearchResult search(
            String question,
            QueryContext context,
            RetrievalOptions options
    ) {
        return search(question, context, options, RetrievalProfile.balanced());
    }

    public RagSearchResult search(
            String question,
            QueryContext context,
            RetrievalOptions options,
            RetrievalProfile profile
    ) {
        QueryPlan plan = queryRewriteService.rewrite(question, context);
        return search(plan, options, profile);
    }

    public RagSearchResult search(
            QueryPlan plan,
            RetrievalOptions options,
            RetrievalProfile profile
    ) {
        var hybrid = hybridRetriever.retrieve(plan, options, profile);
        var reranked = reranker.rerank(plan, hybrid.fusedCandidates(), profile.finalK());
        return new RagSearchResult(
                plan,
                hybrid.fusedCandidates(),
                reranked,
                hybrid.channelCandidateCounts(),
                hybrid.uniqueCandidateCount(),
                hybrid.channelResults()
        );
    }

    public QueryPlan plan(String question, QueryContext context) {
        return queryRewriteService.rewrite(question, context);
    }

    public Optional<ParentChunk> parentContext(KnowledgeChunk chunk) {
        return Optional.ofNullable(parentsById.get(chunk.parentId()));
    }

    public List<KnowledgeDocument> documents() {
        return documents;
    }

    public List<ChunkedDocument> chunkedDocuments() {
        return chunkedDocuments;
    }

    public List<KnowledgeChunk> chunks() {
        return chunks;
    }
}
