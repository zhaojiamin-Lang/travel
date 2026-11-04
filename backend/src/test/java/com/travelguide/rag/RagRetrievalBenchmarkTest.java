package com.travelguide.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.RetrievalChannel;
import com.travelguide.rag.RagModels.RetrievalOptions;
import com.travelguide.rag.RagModels.RetrievalProfile;
import com.travelguide.rag.retrieval.HashingEmbeddingProvider;
import com.travelguide.rag.retrieval.HeuristicReranker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 固定标注集上的召回参数对比。
 *
 * <p>这里衡量的是文档级 Recall@10，符合当前知识库阶段先验证“正确证据有没有
 * 进入候选”的目标。结果只用于参数选择，不替代后续真实 Embedding 和线上回放。</p>
 */
class RagRetrievalBenchmarkTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.of(
            2026, 10, 29, 12, 0, 0, 0, ZoneOffset.ofHours(8)
    );
    private static RagEngine engine;
    private static List<EvaluationCase> cases;

    @BeforeAll
    static void setUp() throws Exception {
        engine = RagEngine.fromDirectory(
                Path.of("..", "knowledge"),
                new HashingEmbeddingProvider(512),
                new HeuristicReranker()
        );
        cases = loadCases();
    }

    @Test
    void comparesStaticAndBalancedRecallWidths() {
        List<RetrievalProfile> profiles = List.of(
                new RetrievalProfile("uniform-50", 50, 50, 50, 30, 10),
                new RetrievalProfile("lean-10-30-30", 10, 30, 30, 20, 10),
                new RetrievalProfile("balanced-20-30-30", 20, 30, 30, 20, 10),
                new RetrievalProfile("balanced-20-30-30-24", 20, 30, 30, 24, 10),
                new RetrievalProfile("medium-30-30-30", 30, 30, 30, 24, 10)
        );

        assertThat(cases).hasSizeGreaterThanOrEqualTo(60);
        Map<String, Metrics> metricsByProfile = new java.util.LinkedHashMap<>();
        for (RetrievalProfile profile : profiles) {
            Metrics metrics = evaluate(profile);
            metricsByProfile.put(profile.name(), metrics);
            System.out.printf(
                    Locale.ROOT,
                    "RAG_BENCH profile=%s cases=%d finalRecall@10=%.4f "
                            + "fusedRecall@10=%.4f strictGoldRecall@10=%.4f MRR=%.4f "
                            + "Recall@1/3/5=%.4f/%.4f/%.4f Precision@10=%.4f "
                            + "avgUnique=%.1f p95Ms=%.3f "
                            + "channelRecall(entity=%.4f,bm25=%.4f,dense=%.4f)%n",
                    metrics.profile(),
                    metrics.caseCount(),
                    metrics.finalHitRate(),
                    metrics.fusedHitRate(),
                    metrics.strictGoldRecall(),
                    metrics.mrr(),
                    metrics.finalRecallAt1(),
                    metrics.finalRecallAt3(),
                    metrics.finalRecallAt5(),
                    metrics.precisionAt10(),
                    metrics.averageUniqueCandidates(),
                    metrics.p95Millis(),
                    metrics.entityChannelRecall(),
                    metrics.bm25ChannelRecall(),
                    metrics.denseChannelRecall()
            );
            if (!metrics.misses().isEmpty()) {
                System.out.printf(
                        Locale.ROOT,
                        "RAG_BENCH_MISSES profile=%s caseIds=%s%n",
                        metrics.profile(),
                        String.join(",", metrics.misses())
                );
            }
        }

        Metrics balanced = metricsByProfile.get("balanced-20-30-30");
        assertThat(balanced.finalHitRate()).isEqualTo(1.0);
        assertThat(balanced.strictGoldRecall()).isGreaterThanOrEqualTo(0.98);
        assertThat(balanced.mrr()).isGreaterThanOrEqualTo(0.938);
        assertThat(balanced.precisionAt10()).isGreaterThanOrEqualTo(0.360);
        assertThat(balanced.averageUniqueCandidates())
                .isLessThan(metricsByProfile.get("uniform-50").averageUniqueCandidates());

        printLayeredMetrics(profiles.get(2), balanced);
    }

    private Metrics evaluate(RetrievalProfile profile) {
        int finalHits = 0;
        int finalHitsAt1 = 0;
        int finalHitsAt3 = 0;
        int finalHitsAt5 = 0;
        int fusedHits = 0;
        double precisionAt10 = 0;
        int entityApplicable = 0;
        int entityHits = 0;
        int bm25Hits = 0;
        int denseHits = 0;
        double reciprocalRank = 0;
        double strictGoldRecall = 0;
        double unionTotal = 0;
        List<String> misses = new ArrayList<>();
        List<Long> latencies = new ArrayList<>();
        RetrievalOptions options = new RetrievalOptions(
                Set.of("DEMO", "CITED", "VERIFIED"),
                AS_OF.toInstant()
        );

        // 先做一次不计入指标的预热，降低 JVM 首次加载对时延比较的干扰。
        if (!cases.isEmpty()) {
            runCase(cases.get(0), options, profile);
        }

        for (EvaluationCase testCase : cases) {
            long start = System.nanoTime();
            var result = runCase(testCase, options, profile);
            latencies.add(System.nanoTime() - start);

            List<String> fusedIds = topDocumentIds(result.fusedCandidates().stream()
                    .map(item -> item.chunk().metadata().docId())
                    .toList(), 10);
            List<String> finalIds = topDocumentIds(result.results().stream()
                    .map(item -> item.chunk().metadata().docId())
                    .toList(), 10);

            if (containsGold(fusedIds, testCase.goldDocIds())) {
                fusedHits++;
            }
            if (containsGold(finalIds, testCase.goldDocIds())) {
                finalHits++;
            } else {
                misses.add(testCase.id());
                System.out.printf(
                        Locale.ROOT,
                        "RAG_BENCH_MISS_DETAIL profile=%s case=%s entities=%s topDocs=%s%n",
                        profile.name(),
                        testCase.id(),
                        result.queryPlan().entityIds(),
                        finalIds
                );
                System.out.printf(
                        Locale.ROOT,
                        "RAG_BENCH_MISS_FUSED profile=%s case=%s intent=%s facts=%s fusedDocs=%s%n",
                        profile.name(),
                        testCase.id(),
                        result.queryPlan().intent(),
                        result.queryPlan().factTypes(),
                        result.fusedCandidates().stream()
                                .map(item -> item.chunk().metadata().docId())
                                .toList()
                );
            }
            if (containsGold(finalIds.subList(0, Math.min(1, finalIds.size())), testCase.goldDocIds())) {
                finalHitsAt1++;
            }
            if (containsGold(finalIds.subList(0, Math.min(3, finalIds.size())), testCase.goldDocIds())) {
                finalHitsAt3++;
            }
            if (containsGold(finalIds.subList(0, Math.min(5, finalIds.size())), testCase.goldDocIds())) {
                finalHitsAt5++;
            }
            long relevantRetrieved = testCase.goldDocIds().stream().filter(finalIds::contains).count();
            precisionAt10 += (double) relevantRetrieved / Math.max(1, finalIds.size());
            if (!result.queryPlan().entityIds().isEmpty()) {
                entityApplicable++;
                if (containsGold(channelDocumentIds(result, RagModels.RetrievalChannel.ENTITY), testCase.goldDocIds())) {
                    entityHits++;
                }
            }
            if (containsGold(channelDocumentIds(result, RagModels.RetrievalChannel.BM25), testCase.goldDocIds())) {
                bm25Hits++;
            }
            if (containsGold(channelDocumentIds(result, RagModels.RetrievalChannel.DENSE), testCase.goldDocIds())) {
                denseHits++;
            }

            int rank = firstGoldRank(finalIds, testCase.goldDocIds());
            if (rank > 0) {
                reciprocalRank += 1.0 / rank;
            }
            long matchedGold = testCase.goldDocIds().stream().filter(finalIds::contains).count();
            strictGoldRecall += (double) matchedGold / testCase.goldDocIds().size();
            unionTotal += result.uniqueCandidateCount();
        }

        int count = cases.size();
        latencies.sort(Comparator.naturalOrder());
        long p95 = latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1));
        return new Metrics(
                profile.name(),
                count,
                (double) finalHits / count,
                (double) fusedHits / count,
                strictGoldRecall / count,
                reciprocalRank / count,
                (double) finalHitsAt1 / count,
                (double) finalHitsAt3 / count,
                (double) finalHitsAt5 / count,
                precisionAt10 / count,
                unionTotal / count,
                p95 / 1_000_000.0,
                List.copyOf(misses),
                entityApplicable == 0 ? 0 : (double) entityHits / entityApplicable,
                (double) bm25Hits / count,
                (double) denseHits / count
        );
    }

    private RagModels.RagSearchResult runCase(
            EvaluationCase testCase,
            RetrievalOptions options,
            RetrievalProfile profile
    ) {
        QueryContext context = new QueryContext(
                "上海",
                testCase.currentPoiId(),
                testCase.currentPoiName(),
                testCase.nextPoiId(),
                testCase.nextPoiName(),
                Map.of(),
                AS_OF.toInstant()
        );
        return engine.search(testCase.query(), context, options, profile);
    }

    private boolean containsGold(List<String> retrieved, List<String> gold) {
        return retrieved.stream().anyMatch(gold::contains);
    }

    private int firstGoldRank(List<String> retrieved, List<String> gold) {
        for (int index = 0; index < retrieved.size(); index++) {
            if (gold.contains(retrieved.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private List<String> distinctDocuments(List<String> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private List<String> topDocumentIds(List<String> ids, int limit) {
        return distinctDocuments(ids).stream().limit(limit).toList();
    }

    private void printLayeredMetrics(RetrievalProfile profile, Metrics hybridMetrics) {
        RetrievalOptions options = new RetrievalOptions(
                Set.of("DEMO", "CITED", "VERIFIED"),
                AS_OF.toInstant()
        );
        Map<RetrievalChannel, RankingTotals> channels = new EnumMap<>(RetrievalChannel.class);
        for (RetrievalChannel channel : RetrievalChannel.values()) {
            channels.put(channel, new RankingTotals());
        }
        RankingTotals fused = new RankingTotals();
        RankingTotals reranked = new RankingTotals();

        for (EvaluationCase testCase : cases) {
            var result = runCase(testCase, options, profile);
            for (RetrievalChannel channel : RetrievalChannel.values()) {
                channels.get(channel).add(
                        result.channelResults().getOrDefault(channel, List.of()).stream()
                                .map(item -> item.chunk().metadata().docId())
                                .toList(),
                        testCase.goldDocIds()
                );
            }
            fused.add(result.fusedCandidates().stream()
                    .map(item -> item.chunk().metadata().docId())
                    .toList(), testCase.goldDocIds());
            reranked.add(result.results().stream()
                    .map(item -> item.chunk().metadata().docId())
                    .toList(), testCase.goldDocIds());
        }

        for (RetrievalChannel channel : RetrievalChannel.values()) {
            System.out.printf(
                    Locale.ROOT,
                    "RAG_LAYER profile=%s stage=%s Recall@1/3/5/10=%.4f/%.4f/%.4f/%.4f "
                            + "Precision@10=%.4f MRR@10=%.4f%n",
                    profile.name(),
                    channel,
                    channels.get(channel).recall(cases.size(), 1),
                    channels.get(channel).recall(cases.size(), 3),
                    channels.get(channel).recall(cases.size(), 5),
                    channels.get(channel).recall(cases.size(), 10),
                    channels.get(channel).precision(cases.size(), 10),
                    channels.get(channel).mrr(cases.size())
            );
        }
        for (var entry : Map.of("RRF", fused, "RERANK", reranked).entrySet()) {
            System.out.printf(
                    Locale.ROOT,
                    "RAG_LAYER profile=%s stage=%s Recall@1/3/5/10=%.4f/%.4f/%.4f/%.4f "
                            + "Precision@10=%.4f MRR@10=%.4f%n",
                    profile.name(),
                    entry.getKey(),
                    entry.getValue().recall(cases.size(), 1),
                    entry.getValue().recall(cases.size(), 3),
                    entry.getValue().recall(cases.size(), 5),
                    entry.getValue().recall(cases.size(), 10),
                    entry.getValue().precision(cases.size(), 10),
                    entry.getValue().mrr(cases.size())
            );
        }
    }

    private List<String> channelDocumentIds(
            RagModels.RagSearchResult result,
            RagModels.RetrievalChannel channel
    ) {
        return result.channelResults().getOrDefault(channel, List.of()).stream()
                .map(item -> item.chunk().metadata().docId())
                .toList();
    }

    private static List<EvaluationCase> loadCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream stream = RagRetrievalBenchmarkTest.class
                .getResourceAsStream("/rag/eval-cases.json")) {
            assertThat(stream).as("找不到 RAG 评测集").isNotNull();
            JsonNode root = mapper.readTree(stream);
            List<EvaluationCase> result = new ArrayList<>();
            for (JsonNode node : root) {
                result.add(new EvaluationCase(
                        node.path("id").asText(),
                        node.path("query").asText(),
                        textValues(node.path("goldDocIds")),
                        nullableText(node, "currentPoiId"),
                        nullableText(node, "currentPoiName"),
                        nullableText(node, "nextPoiId"),
                        nullableText(node, "nextPoiName")
                ));
            }
            return List.copyOf(result);
        }
    }

    private static List<String> textValues(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(item -> values.add(item.asText()));
        return List.copyOf(values);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private record EvaluationCase(
            String id,
            String query,
            List<String> goldDocIds,
            String currentPoiId,
            String currentPoiName,
            String nextPoiId,
            String nextPoiName
    ) {
    }

    private record Metrics(
            String profile,
            int caseCount,
            double finalHitRate,
            double fusedHitRate,
            double strictGoldRecall,
            double mrr,
            double finalRecallAt1,
            double finalRecallAt3,
            double finalRecallAt5,
            double precisionAt10,
            double averageUniqueCandidates,
            double p95Millis,
            List<String> misses,
            double entityChannelRecall,
            double bm25ChannelRecall,
            double denseChannelRecall
    ) {
    }

    private static final class RankingTotals {
        private int cases;
        private int hitAt1;
        private int hitAt3;
        private int hitAt5;
        private int hitAt10;
        private double reciprocalRank;
        private double precisionAt10;

        private void add(List<String> retrieved, List<String> gold) {
            List<String> unique = new ArrayList<>(new LinkedHashSet<>(retrieved));
            cases++;
            if (contains(unique, gold, 1)) {
                hitAt1++;
            }
            if (contains(unique, gold, 3)) {
                hitAt3++;
            }
            if (contains(unique, gold, 5)) {
                hitAt5++;
            }
            if (contains(unique, gold, 10)) {
                hitAt10++;
            }
            for (int index = 0; index < Math.min(10, unique.size()); index++) {
                if (gold.contains(unique.get(index))) {
                    reciprocalRank += 1.0 / (index + 1);
                    break;
                }
            }
            long relevant = gold.stream().filter(unique.subList(0, Math.min(10, unique.size()))::contains).count();
            precisionAt10 += (double) relevant / Math.max(1, Math.min(10, unique.size()));
        }

        private double recall(int denominator, int k) {
            int hits = switch (k) {
                case 1 -> hitAt1;
                case 3 -> hitAt3;
                case 5 -> hitAt5;
                default -> hitAt10;
            };
            return denominator == 0 ? 0 : (double) hits / denominator;
        }

        private double precision(int denominator, int k) {
            return denominator == 0 ? 0 : precisionAt10 / denominator;
        }

        private double mrr(int denominator) {
            return denominator == 0 ? 0 : reciprocalRank / denominator;
        }

        private static boolean contains(List<String> retrieved, List<String> gold, int k) {
            return retrieved.stream().limit(k).anyMatch(gold::contains);
        }
    }
}
