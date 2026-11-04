package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.SignalStatus;
import com.travelguide.memory.MemoryModels.SnapshotEntity;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.ingestion.KnowledgeLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryEntityTest {

    private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");

    private InMemoryMemoryStore store;
    private MemoryService memoryService;
    private EntityResolver resolver;
    private List<KnowledgeDocument> documents;

    @BeforeEach
    void setUp() throws Exception {
        store = new InMemoryMemoryStore();
        memoryService = new MemoryService(store);
        documents = new KnowledgeLoader().loadDirectory(Path.of("..", "knowledge"));
        resolver = new EntityResolver(documents, store);
    }

    @Test
    void explicitCorrectionSupersedesOldPreferenceAndKeepsAudit() {
        memoryService.rememberExplicit("u1", "pace", "default", "RELAXED", "chat", NOW);
        memoryService.rememberExplicit("u1", "pace", "default", "BALANCED", "chat", NOW.plusSeconds(10));

        var signals = store.findPreferenceSignals("u1");
        assertThat(signals).hasSize(2);
        assertThat(signals).extracting(item -> item.status())
                .containsExactlyInAnyOrder(SignalStatus.SUPERSEDED, SignalStatus.ACTIVE);
        assertThat(memoryService.recall("u1", "pace", NOW.plusSeconds(20), 5))
                .singleElement()
                .extracting(item -> item.signal().value())
                .isEqualTo("BALANCED");
        assertThat(store.audits()).isNotEmpty();
    }

    @Test
    void rejectsLowConfidenceInferredMemory() {
        assertThatThrownBy(() -> memoryService.rememberInferred(
                "u1", "interest", "quiet", "high", 0.5, "inference", null, NOW
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesPreviousHotelToStableEntityId() {
        store.saveTripSnapshot(new TripSnapshot(
                "snap-1",
                "u1",
                "trip-1",
                "plan-1",
                1,
                "上海两日游",
                List.of(new SnapshotEntity("HOTEL", "hotel:amap:B001", "外滩英迪格酒店")),
                NOW.minusSeconds(86400)
        ));

        var result = resolver.resolve(
                "u1",
                "session-1",
                "上次那家酒店附近有什么？",
                QueryContext.of("上海", NOW)
        );

        assertThat(result.resolvedEntityIds()).containsExactly("hotel:amap:B001");
        assertThat(result.standaloneQuery()).contains("外滩英迪格酒店");
        assertThat(result.unresolvedMentions()).isEmpty();
        assertThat(store.findRecentEntityReferences("u1", "session-1", 10)).isNotEmpty();
    }

    @Test
    void asksForDisambiguationWhenMultipleHotelsMatch() {
        store.saveTripSnapshot(new TripSnapshot(
                "snap-1",
                "u1",
                "trip-1",
                "plan-1",
                1,
                "上海住宿",
                List.of(
                        new SnapshotEntity("HOTEL", "hotel:amap:B001", "酒店甲"),
                        new SnapshotEntity("HOTEL", "hotel:amap:B002", "酒店乙")
                ),
                NOW.minusSeconds(86400)
        ));

        var result = resolver.resolve(
                "u1",
                "session-1",
                "上次那家酒店在哪？",
                QueryContext.of("上海", NOW)
        );

        assertThat(result.resolvedEntityIds()).isEmpty();
        assertThat(result.ambiguousCandidates()).extracting(item -> item.entityId())
                .containsExactlyInAnyOrder("hotel:amap:B001", "hotel:amap:B002");
        assertThat(result.unresolvedMentions()).isNotEmpty();
    }

    @Test
    void memoryResolutionIsMergedIntoQueryPlan() {
        store.saveTripSnapshot(new TripSnapshot(
                "snap-1",
                "u1",
                "trip-1",
                "plan-1",
                1,
                "上海两日游",
                List.of(new SnapshotEntity("POI", "poi:bund", "外滩")),
                NOW.minusSeconds(86400)
        ));
        var planner = new MemoryAwareQueryPlanner(documents, resolver);

        var plan = planner.plan(
                "u1",
                "session-1",
                "上次那个景点几点开门？",
                QueryContext.of("上海", NOW)
        );

        assertThat(plan.standaloneQuery()).contains("外滩");
        assertThat(plan.poiIds()).contains("bund");
        assertThat(plan.rewriteSource()).isEqualTo("MEMORY_ENTITY_RULES");
    }
}
