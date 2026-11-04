package com.travelguide.memory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import org.bson.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.travelguide.memory.MemoryModels.EntityReference;
import com.travelguide.memory.MemoryModels.MemoryAudit;
import com.travelguide.memory.MemoryModels.MemoryEpisode;
import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.SignalStatus;
import com.travelguide.memory.MemoryModels.SnapshotEntity;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.memory.MemoryModels.UserProfile;

/**
 * MongoDB 长期记忆存储实现。
 *
 * <p>所有查询都以 userId 为边界；entity_references 和 preference_signals
 * 使用 TTL 索引自动清理过期记录。</p>
 */
public final class MongoMemoryStore implements MemoryStore {

    public static final String USER_PROFILES = "user_profiles";
    public static final String PREFERENCE_SIGNALS = "preference_signals";
    public static final String MEMORY_EPISODES = "memory_episodes";
    public static final String ENTITY_REFERENCES = "entity_references";
    public static final String TRIP_SNAPSHOTS = "trip_snapshots";
    public static final String MEMORY_AUDIT = "memory_audit";

    private final MongoCollection<Document> profiles;
    private final MongoCollection<Document> signals;
    private final MongoCollection<Document> episodes;
    private final MongoCollection<Document> references;
    private final MongoCollection<Document> snapshots;
    private final MongoCollection<Document> audits;

    public MongoMemoryStore(MongoClient client, String databaseName) {
        MongoDatabase database = client.getDatabase(databaseName);
        this.profiles = database.getCollection(USER_PROFILES);
        this.signals = database.getCollection(PREFERENCE_SIGNALS);
        this.episodes = database.getCollection(MEMORY_EPISODES);
        this.references = database.getCollection(ENTITY_REFERENCES);
        this.snapshots = database.getCollection(TRIP_SNAPSHOTS);
        this.audits = database.getCollection(MEMORY_AUDIT);
        ensureIndexes();
    }

    @Override
    public Optional<UserProfile> findProfile(String userId) {
        Document document = profiles.find(Filters.eq("userId", userId)).first();
        return Optional.ofNullable(document).map(this::profile);
    }

    @Override
    public void saveProfile(UserProfile profile) {
        profiles.replaceOne(
                Filters.eq("userId", profile.userId()),
                new Document("userId", profile.userId())
                        .append("explicitPreferences", new Document(profile.explicitPreferences()))
                        .append("consentVersion", profile.consentVersion())
                        .append("updatedAt", date(profile.updatedAt())),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public List<PreferenceSignal> findPreferenceSignals(String userId) {
        return signals.find(Filters.eq("userId", userId))
                .sort(Sorts.descending("observedAt"))
                .into(new ArrayList<>())
                .stream()
                .map(this::signal)
                .toList();
    }

    @Override
    public void savePreferenceSignal(PreferenceSignal signal) {
        signals.replaceOne(
                Filters.eq("signalId", signal.signalId()),
                new Document("signalId", signal.signalId())
                        .append("userId", signal.userId())
                        .append("type", signal.type())
                        .append("key", signal.key())
                        .append("value", signal.value())
                        .append("confidence", signal.confidence())
                        .append("sourceId", signal.sourceId())
                        .append("observedAt", date(signal.observedAt()))
                        .append("expiresAt", date(signal.expiresAt()))
                        .append("status", signal.status().name()),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public List<TripSnapshot> findRecentTripSnapshots(String userId, int limit) {
        return snapshots.find(Filters.eq("userId", userId))
                .sort(Sorts.descending("createdAt"))
                .limit(limit)
                .into(new ArrayList<>())
                .stream()
                .map(this::snapshot)
                .toList();
    }

    @Override
    public void saveTripSnapshot(TripSnapshot snapshot) {
        snapshots.replaceOne(
                Filters.eq("snapshotId", snapshot.snapshotId()),
                new Document("snapshotId", snapshot.snapshotId())
                        .append("userId", snapshot.userId())
                        .append("tripId", snapshot.tripId())
                        .append("planId", snapshot.planId())
                        .append("planVersion", snapshot.planVersion())
                        .append("summary", snapshot.summary())
                        .append("entities", snapshot.entities().stream()
                                .map(this::snapshotEntity)
                                .toList())
                        .append("createdAt", date(snapshot.createdAt())),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public List<EntityReference> findRecentEntityReferences(String userId, String sessionId, int limit) {
        Document filter = new Document("userId", userId);
        if (sessionId != null) {
            filter.append("sessionId", sessionId);
        }
        return references.find(filter)
                .sort(Sorts.descending("createdAt"))
                .limit(limit)
                .into(new ArrayList<>())
                .stream()
                .map(this::reference)
                .toList();
    }

    @Override
    public void saveEntityReference(EntityReference reference) {
        references.replaceOne(
                Filters.eq("referenceId", reference.referenceId()),
                new Document("referenceId", reference.referenceId())
                        .append("userId", reference.userId())
                        .append("sessionId", reference.sessionId())
                        .append("surface", reference.surface())
                        .append("resolvedEntityId", reference.resolvedEntityId())
                        .append("candidateEntityIds", reference.candidateEntityIds())
                        .append("confidence", reference.confidence())
                        .append("resolutionMethod", reference.resolutionMethod())
                        .append("contextHash", reference.contextHash())
                        .append("createdAt", date(reference.createdAt()))
                        .append("expiresAt", date(reference.expiresAt())),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public List<MemoryEpisode> findRecentEpisodes(String userId, int limit) {
        return episodes.find(Filters.eq("userId", userId))
                .sort(Sorts.descending("eventAt"))
                .limit(limit)
                .into(new ArrayList<>())
                .stream()
                .map(this::episode)
                .toList();
    }

    @Override
    public void saveEpisode(MemoryEpisode episode) {
        episodes.replaceOne(
                Filters.eq("episodeId", episode.episodeId()),
                new Document("episodeId", episode.episodeId())
                        .append("userId", episode.userId())
                        .append("tripId", episode.tripId())
                        .append("summary", episode.summary())
                        .append("eventAt", date(episode.eventAt()))
                        .append("entityIds", episode.entityIds())
                        .append("sourceMessageIds", episode.sourceMessageIds()),
                new ReplaceOptions().upsert(true)
        );
    }

    @Override
    public void saveAudit(MemoryAudit audit) {
        audits.insertOne(new Document("auditId", audit.auditId())
                .append("userId", audit.userId())
                .append("memoryId", audit.memoryId())
                .append("action", audit.action())
                .append("beforeHash", audit.beforeHash())
                .append("afterHash", audit.afterHash())
                .append("reason", audit.reason())
                .append("createdAt", date(audit.createdAt())));
    }

    private void ensureIndexes() {
        profiles.createIndex(
                Indexes.ascending("userId"),
                new IndexOptions().unique(true).name("uk_user_profiles_user")
        );
        signals.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("userId"),
                        Indexes.ascending("type"),
                        Indexes.ascending("key"),
                        Indexes.ascending("status")
                ),
                new IndexOptions().name("idx_preference_lookup")
        );
        signals.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().name("ttl_preference_expiry").expireAfter(0L, TimeUnit.SECONDS)
        );
        episodes.createIndex(
                Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("eventAt")),
                new IndexOptions().name("idx_episode_recent")
        );
        references.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("userId"),
                        Indexes.ascending("sessionId"),
                        Indexes.descending("createdAt")
                ),
                new IndexOptions().name("idx_entity_reference_recent")
        );
        references.createIndex(
                Indexes.ascending("expiresAt"),
                new IndexOptions().name("ttl_entity_reference").expireAfter(0L, TimeUnit.SECONDS)
        );
        snapshots.createIndex(
                Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_trip_snapshot_recent")
        );
        snapshots.createIndex(
                Indexes.compoundIndex(
                        Indexes.ascending("userId"),
                        Indexes.ascending("tripId"),
                        Indexes.ascending("planId"),
                        Indexes.ascending("planVersion")
                ),
                new IndexOptions().unique(true).name("uk_trip_snapshot_version")
        );
        audits.createIndex(
                Indexes.compoundIndex(Indexes.ascending("userId"), Indexes.descending("createdAt")),
                new IndexOptions().name("idx_memory_audit_user")
        );
    }

    private UserProfile profile(Document document) {
        Document preferences = document.get("explicitPreferences", Document.class);
        Map<String, String> values = new LinkedHashMap<>();
        if (preferences != null) {
            preferences.forEach((key, value) -> values.put(key, String.valueOf(value)));
        }
        return new UserProfile(
                document.getString("userId"),
                values,
                document.getString("consentVersion"),
                instant(document.getDate("updatedAt"))
        );
    }

    private PreferenceSignal signal(Document document) {
        return new PreferenceSignal(
                document.getString("signalId"),
                document.getString("userId"),
                document.getString("type"),
                document.getString("key"),
                document.getString("value"),
                number(document, "confidence"),
                document.getString("sourceId"),
                instant(document.getDate("observedAt")),
                instant(document.getDate("expiresAt")),
                SignalStatus.valueOf(document.getString("status"))
        );
    }

    private TripSnapshot snapshot(Document document) {
        List<Document> entities = document.getList("entities", Document.class);
        return new TripSnapshot(
                document.getString("snapshotId"),
                document.getString("userId"),
                document.getString("tripId"),
                document.getString("planId"),
                document.getInteger("planVersion", 0),
                document.getString("summary"),
                entities == null ? List.of() : entities.stream().map(this::snapshotEntity).toList(),
                instant(document.getDate("createdAt"))
        );
    }

    private Document snapshotEntity(SnapshotEntity entity) {
        return new Document("role", entity.role())
                .append("entityId", entity.entityId())
                .append("displayName", entity.displayName());
    }

    private SnapshotEntity snapshotEntity(Document document) {
        return new SnapshotEntity(
                document.getString("role"),
                document.getString("entityId"),
                document.getString("displayName")
        );
    }

    private EntityReference reference(Document document) {
        return new EntityReference(
                document.getString("referenceId"),
                document.getString("userId"),
                document.getString("sessionId"),
                document.getString("surface"),
                document.getString("resolvedEntityId"),
                document.getList("candidateEntityIds", String.class),
                number(document, "confidence"),
                document.getString("resolutionMethod"),
                document.getString("contextHash"),
                instant(document.getDate("createdAt")),
                instant(document.getDate("expiresAt"))
        );
    }

    private MemoryEpisode episode(Document document) {
        return new MemoryEpisode(
                document.getString("episodeId"),
                document.getString("userId"),
                document.getString("tripId"),
                document.getString("summary"),
                instant(document.getDate("eventAt")),
                document.getList("entityIds", String.class),
                document.getList("sourceMessageIds", String.class)
        );
    }

    private Date date(Instant value) {
        return value == null ? null : Date.from(value);
    }

    private Instant instant(Date value) {
        return value == null ? null : value.toInstant();
    }

    private double number(Document document, String key) {
        Number value = document.get(key, Number.class);
        return value == null ? 0 : value.doubleValue();
    }
}
