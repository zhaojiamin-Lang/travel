package com.travelguide.rag.retrieval;

import com.travelguide.rag.RagModels.HybridResult;
import com.travelguide.rag.RagModels.Intent;
import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;
import com.travelguide.rag.RagModels.RetrievalChannel;
import com.travelguide.rag.RagModels.RetrievalOptions;
import com.travelguide.rag.RagModels.RetrievalProfile;
import com.travelguide.rag.persistence.PgVectorStore;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 三路混合召回与 RRF 融合。
 *
 * <p>实体召回负责专名和上下文锚点，BM25 负责精确词和政策原文，Dense
 * 负责同义表达。chunk_id 是融合键，最终返回逐路排名和融合结果，便于
 * Recall@10 归因与失败回放。</p>
 */
public final class HybridRetriever {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final double RRF_K = 60.0;
    private static final int MAX_CHUNKS_PER_DOCUMENT = 4;

    private final List<KnowledgeChunk> chunks;
    private final TextTokenizer tokenizer;
    private final EmbeddingProvider embeddingProvider;
    private final Map<String, List<String>> chunkTokens;
    private final Map<String, float[]> chunkVectors;
    private final Map<String, Integer> documentFrequency;
    private final double averageDocumentLength;
    private final PgVectorStore vectorStore;
    private final boolean vectorIndexReady;

    public HybridRetriever(
            List<KnowledgeChunk> chunks,
            EmbeddingProvider embeddingProvider
    ) {
        this(chunks, embeddingProvider, null);
    }

    public HybridRetriever(
            List<KnowledgeChunk> chunks,
            EmbeddingProvider embeddingProvider,
            PgVectorStore vectorStore
    ) {
        this.chunks = List.copyOf(chunks);
        this.tokenizer = new TextTokenizer();
        this.embeddingProvider = embeddingProvider;
        this.chunkTokens = new LinkedHashMap<>();
        this.chunkVectors = new LinkedHashMap<>();
        this.documentFrequency = new LinkedHashMap<>();

        int totalLength = 0;
        List<float[]> vectors = embeddingProvider.embedAll(
                chunks.stream().map(KnowledgeChunk::embeddingText).toList()
        );
        if (vectors.size() != chunks.size()) {
            throw new IllegalArgumentException("Embedding 返回数量与 chunk 数量不一致");
        }
        for (int index = 0; index < chunks.size(); index++) {
            KnowledgeChunk chunk = chunks.get(index);
            List<String> tokens = tokenizer.tokenize(chunk.embeddingText());
            chunkTokens.put(chunk.chunkId(), tokens);
            // 初始化索引时批量请求向量，避免每个 chunk 单独调用远程模型。
            chunkVectors.put(chunk.chunkId(), vectors.get(index));
            totalLength += tokens.size();
            new HashSet<>(tokens).forEach(token ->
                    documentFrequency.merge(token, 1, Integer::sum)
            );
        }
        this.averageDocumentLength = chunks.isEmpty() ? 0 : (double) totalLength / chunks.size();
        this.vectorStore = vectorStore;
        // 索引同步只做一次；灰度关闭 pgvector 或维度不匹配时自动使用内存精确检索。
        this.vectorIndexReady = vectorStore != null
                && vectorStore.synchronize(embeddingProvider.modelId(), this.chunks, vectors);
    }

    public HybridResult retrieve(QueryPlan query, RetrievalOptions options) {
        return retrieve(query, options, RetrievalProfile.balanced());
    }

    public HybridResult retrieve(
            QueryPlan query,
            RetrievalOptions options,
            RetrievalProfile profile
    ) {
        List<RankedChunk> entityRanked = entityRecall(query, options, profile.entityK());
        List<RankedChunk> bm25Ranked = bm25Recall(query, options, profile.bm25K());
        List<RankedChunk> denseRanked = denseRecall(query, options, profile.denseK());

        Map<String, FusionState> fused = new LinkedHashMap<>();
        addChannel(fused, entityRanked, RetrievalChannel.ENTITY, 1.2);
        addChannel(fused, bm25Ranked, RetrievalChannel.BM25, 1.0);
        addChannel(fused, denseRanked, RetrievalChannel.DENSE, 1.0);

        List<RankedChunk> sorted = fused.values().stream()
                .map(FusionState::toRankedChunk)
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .toList();
        int uniqueCandidateCount = fused.size();

        // 单文档最多保留 4 个 chunk，避免同一份长文档挤掉其他实体和事实类型。
        Map<String, Integer> perDocument = new LinkedHashMap<>();
        Set<String> seenContent = new LinkedHashSet<>();
        List<RankedChunk> unique = new ArrayList<>();
        for (RankedChunk candidate : sorted) {
            String docId = candidate.chunk().metadata().docId();
            if (perDocument.getOrDefault(docId, 0) >= MAX_CHUNKS_PER_DOCUMENT) {
                continue;
            }
            String dedupeKey = docId + "|"
                    + tokenizer.normalize(candidate.chunk().rawText()).replace(" ", "");
            if (seenContent.add(dedupeKey)) {
                unique.add(candidate);
                perDocument.merge(docId, 1, Integer::sum);
            }
            if (unique.size() >= profile.fusedK()) {
                break;
            }
        }

        return new HybridResult(
                unique,
                Map.of(
                        RetrievalChannel.ENTITY, entityRanked.size(),
                        RetrievalChannel.BM25, bm25Ranked.size(),
                        RetrievalChannel.DENSE, denseRanked.size()
                ),
                uniqueCandidateCount,
                Map.of(
                        RetrievalChannel.ENTITY, entityRanked,
                        RetrievalChannel.BM25, bm25Ranked,
                        RetrievalChannel.DENSE, denseRanked
                )
        );
    }

    private List<RankedChunk> entityRecall(QueryPlan query, RetrievalOptions options, int limit) {
        if (query.entityIds().isEmpty()) {
            return List.of();
        }
        return chunks.stream()
                .filter(chunk -> eligible(chunk, query, options))
                .filter(chunk -> intersects(query.entityIds(), chunk.metadata().entityIds()))
                .map(chunk -> new RankedChunk(
                        chunk,
                        entityScore(query, chunk),
                        Map.of(RetrievalChannel.ENTITY, 0)
                ))
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .toList();
    }

    private List<RankedChunk> bm25Recall(QueryPlan query, RetrievalOptions options, int limit) {
        List<String> queryTokens = new ArrayList<>(new LinkedHashSet<>(
                tokenizer.tokenize(query.standaloneQuery())
        ));
        if (queryTokens.isEmpty()) {
            return List.of();
        }

        List<RankedChunk> ranked = chunks.stream()
                .filter(chunk -> eligible(chunk, query, options))
                .map(chunk -> new RankedChunk(
                        chunk,
                        bm25(queryTokens, chunk.chunkId()),
                        Map.of(RetrievalChannel.BM25, 0)
                ))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .toList();
        return ranked;
    }

    private List<RankedChunk> denseRecall(QueryPlan query, RetrievalOptions options, int limit) {
        float[] queryVector = embeddingProvider.embed(query.standaloneQuery());
        List<KnowledgeChunk> eligibleChunks = chunks.stream()
                .filter(chunk -> eligible(chunk, query, options))
                .toList();
        if (vectorIndexReady) {
            Map<String, KnowledgeChunk> byId = eligibleChunks.stream()
                    .collect(Collectors.toMap(KnowledgeChunk::chunkId, chunk -> chunk));
            // 多取一些再过滤低相似度，兼顾 ANN 过滤条件与最终固定的 denseK。
            List<RankedChunk> indexed = vectorStore.nearest(
                            embeddingProvider.modelId(),
                            queryVector,
                            List.copyOf(byId.keySet()),
                            Math.max(limit * 4, 100)
                    ).stream()
                    .filter(match -> match.similarity() > 0)
                    .filter(match -> byId.containsKey(match.chunkId()))
                    .map(match -> new RankedChunk(
                            byId.get(match.chunkId()),
                            match.similarity(),
                            Map.of(RetrievalChannel.DENSE, 0)
                    ))
                    .limit(limit)
                    .toList();
            if (!indexed.isEmpty()) {
                return indexed;
            }
        }
        return eligibleChunks.stream()
                .map(chunk -> new RankedChunk(
                        chunk,
                        cosine(queryVector, chunkVectors.get(chunk.chunkId())),
                        Map.of(RetrievalChannel.DENSE, 0)
                ))
                .filter(item -> item.score() > 0)
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .toList();
    }

    private void addChannel(
            Map<String, FusionState> fused,
            List<RankedChunk> ranking,
            RetrievalChannel channel,
            double weight
    ) {
        for (int index = 0; index < ranking.size(); index++) {
            KnowledgeChunk chunk = ranking.get(index).chunk();
            FusionState state = fused.computeIfAbsent(chunk.chunkId(), ignored -> new FusionState(chunk));
            int rank = index + 1;
            state.score += weight / (RRF_K + rank);
            state.ranks.put(channel, rank);
        }
    }

    private double bm25(List<String> queryTokens, String chunkId) {
        List<String> document = chunkTokens.getOrDefault(chunkId, List.of());
        if (document.isEmpty()) {
            return 0;
        }
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        document.forEach(token -> frequencies.merge(token, 1, Integer::sum));

        double score = 0;
        double k1 = 1.2;
        double b = 0.75;
        for (String token : queryTokens) {
            int frequency = frequencies.getOrDefault(token, 0);
            if (frequency == 0) {
                continue;
            }
            int df = documentFrequency.getOrDefault(token, 0);
            double idf = Math.log(1.0 + (chunks.size() - df + 0.5) / (df + 0.5));
            double denominator = frequency + k1 * (
                    1.0 - b + b * document.size() / Math.max(1.0, averageDocumentLength)
            );
            score += idf * frequency * (k1 + 1.0) / denominator;
        }
        return score;
    }

    private double entityScore(QueryPlan query, KnowledgeChunk chunk) {
        double score = 1.0;
        if (!query.factTypes().isEmpty()
                && chunk.factTypes().stream().anyMatch(query.factTypes()::contains)) {
            score += 0.5;
        }
        String normalizedQuery = tokenizer.normalize(query.standaloneQuery());
        String normalizedText = tokenizer.normalize(chunk.embeddingText());
        for (String alias : chunk.metadata().aliases()) {
            if (!alias.isBlank() && normalizedQuery.contains(tokenizer.normalize(alias))
                    && normalizedText.contains(tokenizer.normalize(alias))) {
                score += 0.2;
                break;
            }
        }
        return score;
    }

    private boolean eligible(KnowledgeChunk chunk, QueryPlan query, RetrievalOptions options) {
        if (!options.allowedConfidence().isEmpty()
                && !options.allowedConfidence().contains(chunk.metadata().dataConfidence())) {
            return false;
        }
        if (query.city() != null && !query.city().isBlank()
                && chunk.metadata().city() != null
                && !chunk.metadata().city().isBlank()
                && !query.city().equals(chunk.metadata().city())) {
            return false;
        }
        if (requiresExactFactSection(query.intent())
                && !query.factTypes().isEmpty()
                && chunk.factTypes().stream().noneMatch(query.factTypes()::contains)) {
            return false;
        }
        // 已明确景点时，票务、开放、预约等高价值事实不能跨景点借用。
        if (requiresEntityMatch(query.intent())
                && !query.entityIds().isEmpty()
                && !intersects(query.entityIds(), chunk.metadata().entityIds())) {
            return false;
        }

        LocalDate asOf = options.asOf().atZone(SHANGHAI).toLocalDate();
        if (chunk.metadata().validFrom() != null && asOf.isBefore(chunk.metadata().validFrom())) {
            return false;
        }
        if (chunk.metadata().validTo() != null && asOf.isAfter(chunk.metadata().validTo())) {
            return false;
        }
        return true;
    }

    private boolean requiresExactFactSection(Intent intent) {
        return intent == Intent.TICKET
                || intent == Intent.OPEN_HOURS
                || intent == Intent.OPEN_STATUS
                || intent == Intent.BOOKING
                || intent == Intent.DURATION
                || intent == Intent.TRAVEL_DURATION
                || intent == Intent.DINING;
    }

    private boolean requiresEntityMatch(Intent intent) {
        return requiresExactFactSection(intent)
                || intent == Intent.HIGHLIGHT
                || intent == Intent.HISTORY
                || intent == Intent.ROUTE
                || intent == Intent.DINING
                || intent == Intent.DURATION
                || intent == Intent.TRAVEL_DURATION;
    }

    private boolean intersects(List<String> left, List<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        for (String value : left) {
            if (right.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        // 不假设供应商向量已经归一化。
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static final class FusionState {
        private final KnowledgeChunk chunk;
        private final Map<RetrievalChannel, Integer> ranks = new LinkedHashMap<>();
        private double score;

        private FusionState(KnowledgeChunk chunk) {
            this.chunk = chunk;
        }

        private RankedChunk toRankedChunk() {
            return new RankedChunk(chunk, score, ranks);
        }
    }
}
