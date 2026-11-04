package com.travelguide.rag.retrieval;

import com.travelguide.rag.RagModels.DocType;
import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 无外部模型时的本地重排器。
 *
 * <p>它保留 RRF 排名作为基础分，再叠加实体、章节、事实类型和证据等级。
 * 生产环境可用 Cross-Encoder 实现替换，不改变调用方接口。</p>
 */
public final class HeuristicReranker implements Reranker {

    private final TextTokenizer tokenizer = new TextTokenizer();

    @Override
    public List<RankedChunk> rerank(QueryPlan query, List<RankedChunk> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        Set<String> queryTokens = new LinkedHashSet<>(tokenizer.tokenize(query.standaloneQuery()));
        String normalizedQuery = tokenizer.normalize(query.standaloneQuery()).replace(" ", "");

        return candidates.stream()
                .map(candidate -> {
                    KnowledgeChunk chunk = candidate.chunk();
                    Set<String> chunkTokens = new LinkedHashSet<>(tokenizer.tokenize(chunk.embeddingText()));
                    long overlap = queryTokens.stream().filter(chunkTokens::contains).count();
                    double lexical = queryTokens.isEmpty() ? 0 : (double) overlap / queryTokens.size();

                    double score = candidate.score() * 10;
                    score += lexical * 2.0;
                    score += preferredTypeBoost(query, chunk.metadata().docType());
                    score += factTypeBoost(query, chunk);
                    if (intersects(query.entityIds(), chunk.metadata().entityIds())) {
                        score += 1.5;
                    }
                    String normalizedText = tokenizer.normalize(chunk.rawText()).replace(" ", "");
                    if (normalizedText.contains(normalizedQuery)) {
                        score += 1.0;
                    }
                    // 有客观引文的文档优先于纯内部编排，但 DEMO 仍可进入开发评测。
                    if ("VERIFIED".equals(chunk.metadata().dataConfidence())) {
                        score += 0.40;
                    } else if ("CITED".equals(chunk.metadata().dataConfidence())) {
                        score += 0.20;
                    }
                    return new RankedChunk(chunk, score, candidate.channelRanks());
                })
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(topK)
                .toList();
    }

    private double preferredTypeBoost(QueryPlan query, DocType type) {
        return switch (query.intent()) {
            case OPEN_HOURS, OPEN_STATUS, TICKET, BOOKING ->
                    type == DocType.VISIT ? 1.2 : type == DocType.NOTICE ? 0.4 : 0;
            case HISTORY -> type == DocType.INTRO ? 1.0 : type == DocType.HIGHLIGHT ? 0.5 : 0;
            case HIGHLIGHT -> type == DocType.HIGHLIGHT ? 1.0 : 0;
            case ROUTE, TRANSIT -> type == DocType.TRANSIT ? 0.8
                    : type == DocType.ROUTE ? 0.7 : type == DocType.AREA ? 0.4 : 0;
            case DINING -> type == DocType.FOOD ? 0.8 : 0;
            case DURATION -> type == DocType.HIGHLIGHT || type == DocType.VISIT ? 1.0 : 0;
            case TRAVEL_DURATION -> type == DocType.TRANSIT ? 1.1
                    : type == DocType.ROUTE ? 0.8 : type == DocType.AREA ? 0.3 : 0;
            default -> 0;
        };
    }

    private double factTypeBoost(QueryPlan query, KnowledgeChunk chunk) {
        if (query.factTypes().isEmpty()) {
            return 0;
        }
        Map<String, Boolean> expected = new HashMap<>();
        query.factTypes().forEach(type -> expected.put(type, true));
        return chunk.factTypes().stream().anyMatch(expected::containsKey) ? 0.6 : 0;
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
}
