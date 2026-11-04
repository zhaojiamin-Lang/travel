package com.travelguide.rag;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RAG 模块内部共用的数据模型。
 *
 * <p>这里刻意不依赖 Spring，也不引用现有导游和规划 DTO；
 * 后续接入现有链路时通过适配层转换即可。</p>
 */
public final class RagModels {

    private RagModels() {
    }

    public enum DocType {
        INTRO,
        VISIT,
        HIGHLIGHT,
        AREA,
        ROUTE,
        TRANSIT,
        NOTICE,
        SEASON,
        FOOD,
        UNKNOWN;

        public static DocType parse(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            try {
                return valueOf(value.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }

    public enum Intent {
        GENERAL,
        HISTORY,
        HIGHLIGHT,
        OPEN_HOURS,
        OPEN_STATUS,
        TICKET,
        BOOKING,
        ROUTE,
        TRANSIT,
        DINING,
        DURATION,
        TRAVEL_DURATION,
        WEATHER
    }

    public enum RetrievalChannel {
        ENTITY,
        BM25,
        DENSE
    }

    public record ChunkingOptions(
            int narrativeTarget,
            int narrativeMax,
            int narrativeOverlap,
            int atomicMax
    ) {
        public ChunkingOptions {
            if (narrativeTarget <= 0 || narrativeMax < narrativeTarget || narrativeOverlap < 0 || atomicMax <= 0) {
                throw new IllegalArgumentException("分片参数不合法");
            }
        }

        public static ChunkingOptions defaults() {
            return new ChunkingOptions(400, 500, 64, 240);
        }
    }

    public record DocumentMetadata(
            String docId,
            DocType docType,
            int version,
            String title,
            String city,
            String area,
            List<String> entityIds,
            List<String> poiIds,
            List<String> factTypes,
            List<String> aliases,
            String sourceTier,
            String sourceName,
            String sourceUrl,
            String dataConfidence,
            String status,
            String reviewer,
            LocalDate validFrom,
            LocalDate validTo,
            String rawSnapshot,
            String contentSha256
    ) {
        public DocumentMetadata {
            entityIds = List.copyOf(entityIds == null ? List.of() : entityIds);
            poiIds = List.copyOf(poiIds == null ? List.of() : poiIds);
            factTypes = List.copyOf(factTypes == null ? List.of() : factTypes);
            aliases = List.copyOf(aliases == null ? List.of() : aliases);
        }
    }

    public record KnowledgeDocument(DocumentMetadata metadata, String body) {
        public KnowledgeDocument {
            if (metadata == null || metadata.docId() == null || metadata.docId().isBlank()) {
                throw new IllegalArgumentException("知识文档必须包含 docId");
            }
            body = body == null ? "" : body;
        }
    }

    public record ParentChunk(
            String parentId,
            String docId,
            String sectionPath,
            String text
    ) {
    }

    public record KnowledgeChunk(
            String chunkId,
            String parentId,
            DocumentMetadata metadata,
            String sectionPath,
            List<String> factTypes,
            String rawText,
            String embeddingText
    ) {
        public KnowledgeChunk {
            factTypes = List.copyOf(factTypes == null ? List.of() : factTypes);
        }
    }

    public record ChunkedDocument(
            KnowledgeDocument document,
            List<ParentChunk> parents,
            List<KnowledgeChunk> chunks
    ) {
        public ChunkedDocument {
            parents = List.copyOf(parents);
            chunks = List.copyOf(chunks);
        }
    }

    public record QueryContext(
            String city,
            String currentPoiId,
            String currentPoiName,
            String nextPoiId,
            String nextPoiName,
            Map<String, String> rememberedEntities,
            Instant asOf
    ) {
        public QueryContext {
            rememberedEntities = Map.copyOf(rememberedEntities == null ? Map.of() : rememberedEntities);
            asOf = asOf == null ? Instant.now() : asOf;
        }

        public static QueryContext of(String city, Instant asOf) {
            return new QueryContext(city, null, null, null, null, Map.of(), asOf);
        }

        public static QueryContext atPoi(
                String city,
                String currentPoiId,
                String currentPoiName,
                Instant asOf
        ) {
            return new QueryContext(city, currentPoiId, currentPoiName, null, null, Map.of(), asOf);
        }
    }

    public record QueryPlan(
            String originalQuery,
            String standaloneQuery,
            Intent intent,
            List<String> entityIds,
            List<String> poiIds,
            List<String> factTypes,
            String city,
            String currentPoiName,
            Instant asOf,
            List<String> unresolvedMentions,
            boolean rewritten,
            String rewriteSource
    ) {
        public QueryPlan {
            entityIds = List.copyOf(entityIds == null ? List.of() : entityIds);
            poiIds = List.copyOf(poiIds == null ? List.of() : poiIds);
            factTypes = List.copyOf(factTypes == null ? List.of() : factTypes);
            unresolvedMentions = List.copyOf(unresolvedMentions == null ? List.of() : unresolvedMentions);
        }
    }

    public record RetrievalOptions(
            Set<String> allowedConfidence,
            Instant asOf
    ) {
        public RetrievalOptions {
            allowedConfidence = Set.copyOf(allowedConfidence == null ? Set.of() : allowedConfidence);
            asOf = asOf == null ? Instant.now() : asOf;
        }

        public static RetrievalOptions defaults(Instant asOf) {
            return new RetrievalOptions(Set.of("DEMO", "CITED", "VERIFIED"), asOf);
        }
    }

    public record RetrievalProfile(
            String name,
            int entityK,
            int bm25K,
            int denseK,
            int fusedK,
            int finalK
    ) {
        public RetrievalProfile {
            if (entityK <= 0 || bm25K <= 0 || denseK <= 0 || fusedK <= 0 || finalK <= 0) {
                throw new IllegalArgumentException("各通道召回数量必须大于 0");
            }
        }

        public static RetrievalProfile currentUniform50() {
            return new RetrievalProfile("uniform-50", 50, 50, 50, 30, 8);
        }

        public static RetrievalProfile balanced() {
            return new RetrievalProfile("balanced-20-30-30", 20, 30, 30, 20, 8);
        }

        public static RetrievalProfile balancedRerank24() {
            return new RetrievalProfile("balanced-fused-24", 20, 30, 30, 24, 8);
        }

        public static RetrievalProfile medium() {
            return new RetrievalProfile("medium-30", 30, 30, 30, 24, 8);
        }
    }

    public record RankedChunk(
            KnowledgeChunk chunk,
            double score,
            Map<RetrievalChannel, Integer> channelRanks
    ) {
        public RankedChunk {
            channelRanks = Map.copyOf(channelRanks == null ? Map.of() : channelRanks);
        }
    }

    public record HybridResult(
            List<RankedChunk> fusedCandidates,
            Map<RetrievalChannel, Integer> channelCandidateCounts,
            int uniqueCandidateCount,
            Map<RetrievalChannel, List<RankedChunk>> channelResults
    ) {
        public HybridResult {
            fusedCandidates = List.copyOf(fusedCandidates);
            channelCandidateCounts = Map.copyOf(channelCandidateCounts);
            channelResults = Map.copyOf(channelResults);
        }
    }

    public record RagSearchResult(
            QueryPlan queryPlan,
            List<RankedChunk> fusedCandidates,
            List<RankedChunk> results,
            Map<RetrievalChannel, Integer> channelCandidateCounts,
            int uniqueCandidateCount,
            Map<RetrievalChannel, List<RankedChunk>> channelResults
    ) {
        public RagSearchResult {
            fusedCandidates = List.copyOf(fusedCandidates);
            results = List.copyOf(results);
            channelCandidateCounts = Map.copyOf(channelCandidateCounts);
            channelResults = Map.copyOf(channelResults);
        }
    }
}
