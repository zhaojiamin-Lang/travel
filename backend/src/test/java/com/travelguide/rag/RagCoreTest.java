package com.travelguide.rag;

import com.travelguide.rag.RagModels.Intent;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.RagSearchResult;
import com.travelguide.rag.RagModels.RetrievalChannel;
import com.travelguide.rag.query.AnswerScopePolicy;
import com.travelguide.rag.retrieval.HashingEmbeddingProvider;
import com.travelguide.rag.retrieval.HeuristicReranker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class RagCoreTest {

    private static final OffsetDateTime AS_OF = OffsetDateTime.of(
            2026, 10, 29, 12, 0, 0, 0, ZoneOffset.ofHours(8)
    );
    private static RagEngine engine;

    @BeforeAll
    static void loadKnowledge() throws Exception {
        engine = RagEngine.fromDirectory(
                Path.of("..", "knowledge"),
                new HashingEmbeddingProvider(512),
                new HeuristicReranker()
        );
    }

    @Test
    void loadsAllDocumentsAndKeepsParentContextForAtomicChunks() {
        assertThat(engine.documents()).hasSize(150);
        assertThat(engine.chunks()).isNotEmpty();

        var visitingChunks = engine.chunks().stream()
                .filter(chunk -> "kb.poi.shanghai-museum.visit".equals(chunk.metadata().docId()))
                .filter(chunk -> chunk.rawText().contains("9:00"))
                .toList();

        assertThat(visitingChunks).isNotEmpty();
        assertThat(visitingChunks.get(0).parentId()).isNotBlank();
        assertThat(engine.parentContext(visitingChunks.get(0))).isPresent();
    }

    @Test
    void resolvesPronounsAndImplicitCurrentPoi() {
        var context = QueryContext.atPoi(
                "上海",
                "shanghai-museum",
                "上海博物馆人民广场馆",
                AS_OF.toInstant()
        );

        var plan = engine.plan("这里几点关门？", context);
        assertThat(plan.intent()).isEqualTo(Intent.OPEN_HOURS);
        assertThat(plan.standaloneQuery()).contains("上海博物馆人民广场馆");
        assertThat(plan.entityIds()).contains("poi:shanghai-museum");
        assertThat(plan.rewritten()).isTrue();
    }

    @Test
    void resolvesNextStopUsingPlanContext() {
        var context = new RagModels.QueryContext(
                "上海",
                "bund",
                "外滩",
                "lujiazui",
                "陆家嘴滨江",
                java.util.Map.of(),
                AS_OF.toInstant()
        );

        var plan = engine.plan("下一站怎么去？", context);
        assertThat(plan.standaloneQuery()).contains("陆家嘴滨江");
        assertThat(plan.poiIds()).contains("lujiazui");
        assertThat(plan.intent()).isEqualTo(Intent.ROUTE);
    }

    @Test
    void hybridSearchReturnsCitedOperationalEvidence() {
        RagSearchResult result = engine.search(
                "城隍庙几点开门？",
                QueryContext.of("上海", AS_OF.toInstant())
        );

        assertThat(result.queryPlan().entityIds()).contains("poi:city-god");
        assertThat(result.channelCandidateCounts().get(RetrievalChannel.ENTITY)).isPositive();
        assertThat(result.channelCandidateCounts().get(RetrievalChannel.BM25)).isPositive();
        assertThat(result.channelCandidateCounts().get(RetrievalChannel.DENSE)).isPositive();
        assertThat(result.results()).isNotEmpty();
        assertThat(result.results())
                .anyMatch(item -> "kb.poi.city-god.visit".equals(item.chunk().metadata().docId()));
    }

    @Test
    void rerankKeepsHistoryResultWithinEvidencePackage() {
        RagSearchResult result = engine.search(
                "外滩为什么是重要的历史街区？",
                QueryContext.of("上海", AS_OF.toInstant())
        );

        assertThat(result.results()).isNotEmpty();
        assertThat(result.results())
                .anyMatch(item -> item.chunk().metadata().docId().startsWith("kb.poi.bund."));
    }

    @Test
    void distinguishesTravelDurationFromVisitDuration() {
        QueryContext context = QueryContext.of("上海", AS_OF.toInstant());

        var travelPlan = engine.plan("浦东机场坐地铁到市区要多久？", context);
        assertThat(travelPlan.intent()).isEqualTo(Intent.TRAVEL_DURATION);
        assertThat(travelPlan.factTypes()).contains("TRANSIT").doesNotContain("DURATION");

        RagSearchResult travelResult = engine.search(
                "浦东机场坐地铁到市区要多久？",
                context
        );
        assertThat(travelResult.results())
                .anyMatch(item -> "kb.transit.pvg-to-city".equals(item.chunk().metadata().docId()));

        var visitPlan = engine.plan("上海博物馆建议停留多久？", context);
        assertThat(visitPlan.intent()).isEqualTo(Intent.DURATION);
        assertThat(visitPlan.factTypes()).containsExactly("DURATION");

        var contextualPlan = engine.plan(
                "请介绍一讲。",
                QueryContext.atPoi(
                        "上海",
                        "shanghai-museum",
                        "上海博物馆人民广场馆",
                        AS_OF.toInstant()
                )
        );
        assertThat(contextualPlan.entityIds()).contains("poi:shanghai-museum");
    }

    @Test
    void rejectsUnrelatedKnowledgeDespiteVectorTopK() {
        var context = QueryContext.atPoi(
                "上海",
                "bund",
                "外滩",
                AS_OF.toInstant()
        );
        RagSearchResult unrelated = engine.search("量子纠缠是什么？", context);
        RagSearchResult inScope = engine.search("外滩有哪些建筑风格？", context);

        assertThat(unrelated.results()).isNotEmpty();
        assertThat(AnswerScopePolicy.hasRelevantEvidence(
                "量子纠缠是什么？",
                unrelated.queryPlan(),
                unrelated.results()
        )).isFalse();
        assertThat(AnswerScopePolicy.hasRelevantEvidence(
                "外滩有哪些建筑风格？",
                inScope.queryPlan(),
                inScope.results()
        )).isTrue();
        assertThat(AnswerScopePolicy.isClearlyInScope("量子纠缠是什么？", "外滩")).isFalse();
        assertThat(AnswerScopePolicy.isClearlyInScope("这里几点关门？", "外滩")).isTrue();
    }

    @Test
    void resolvesKnowledgeDirectoryFromEitherRepositoryWorkingDirectory() {
        assertThat(RagConfiguration.resolveKnowledgePath("knowledge"))
                .isDirectory()
                .satisfies(path -> assertThat(path.resolve("poi")).isDirectory());
        assertThat(RagConfiguration.resolveKnowledgePath("../knowledge"))
                .isDirectory()
                .satisfies(path -> assertThat(path.resolve("poi")).isDirectory());
    }
}
