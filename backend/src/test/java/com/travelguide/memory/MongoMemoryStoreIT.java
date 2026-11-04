package com.travelguide.memory;

import com.mongodb.client.MongoClients;
import com.travelguide.memory.MemoryModels.EntityReference;
import com.travelguide.memory.MemoryModels.MemoryAudit;
import com.travelguide.memory.MemoryModels.MemoryEpisode;
import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.SignalStatus;
import com.travelguide.memory.MemoryModels.SnapshotEntity;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.memory.MemoryModels.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地 MongoDB 可选集成测试。使用随机测试库，结束后删除该测试库。
 */
@EnabledIfEnvironmentVariable(named = "RUN_MONGO_INTEGRATION_TEST", matches = "true")
class MongoMemoryStoreIT {

    private static final Instant NOW = Instant.parse("2026-10-29T04:00:00Z");

    private com.mongodb.client.MongoClient client;
    private String databaseName;
    private MongoMemoryStore store;

    @BeforeEach
    void setUp() {
        client = MongoClients.create("mongodb://localhost:27017");
        databaseName = "travel_memory_test_" + UUID.randomUUID().toString().replace("-", "");
        store = new MongoMemoryStore(client, databaseName);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            if (databaseName != null) {
                client.getDatabase(databaseName).drop();
            }
            client.close();
        }
    }

    @Test
    void persistsAndReadsMemoryDocuments() {
        store.saveProfile(new UserProfile(
                "u1", Map.of("diet", "no-spicy"), "v1", NOW
        ));
        store.savePreferenceSignal(new PreferenceSignal(
                "s1", "u1", "pace", "default", "RELAXED", 1.0,
                "explicit", NOW, NOW.plusSeconds(3600), SignalStatus.ACTIVE
        ));
        store.saveTripSnapshot(new TripSnapshot(
                "snap1", "u1", "trip1", "plan1", 1, "上海两日",
                List.of(new SnapshotEntity("HOTEL", "hotel:amap:B001", "酒店甲")),
                NOW
        ));
        store.saveEntityReference(new EntityReference(
                "ref1", "u1", "session1", "上次那家酒店",
                "hotel:amap:B001", List.of("hotel:amap:B001"),
                0.9, "TRIP_SNAPSHOT", "context-hash", NOW, NOW.plusSeconds(3600)
        ));
        store.saveEpisode(new MemoryEpisode(
                "episode1", "u1", "trip1", "用户偏好轻松节奏",
                NOW, List.of("poi:bund"), List.of("message1")
        ));
        store.saveAudit(new MemoryAudit(
                "audit1", "u1", "s1", "UPSERT", null, null, "test", NOW
        ));

        assertThat(store.findProfile("u1")).get()
                .extracting(UserProfile::explicitPreferences)
                .isEqualTo(Map.of("diet", "no-spicy"));
        assertThat(store.findPreferenceSignals("u1")).singleElement()
                .extracting(PreferenceSignal::value)
                .isEqualTo("RELAXED");
        assertThat(store.findRecentTripSnapshots("u1", 5)).singleElement()
                .extracting(TripSnapshot::snapshotId)
                .isEqualTo("snap1");
        assertThat(store.findRecentEntityReferences("u1", "session1", 5)).singleElement()
                .extracting(EntityReference::resolvedEntityId)
                .isEqualTo("hotel:amap:B001");
        assertThat(store.findRecentEpisodes("u1", 5)).singleElement()
                .extracting(MemoryEpisode::episodeId)
                .isEqualTo("episode1");
    }
}
