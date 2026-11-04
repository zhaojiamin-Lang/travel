package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.EntityReference;
import com.travelguide.memory.MemoryModels.MemoryAudit;
import com.travelguide.memory.MemoryModels.MemoryEpisode;
import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.memory.MemoryModels.UserProfile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试和本地开发使用的内存实现。
 *
 * <p>生产实现应替换为 MongoMemoryStore，并复用相同查询语义。</p>
 */
public final class InMemoryMemoryStore implements MemoryStore {

    private final Map<String, UserProfile> profiles = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<PreferenceSignal>> signals = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<TripSnapshot>> snapshots = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<EntityReference>> references = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<MemoryEpisode>> episodes = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<MemoryAudit> audits = new CopyOnWriteArrayList<>();

    @Override
    public Optional<UserProfile> findProfile(String userId) {
        return Optional.ofNullable(profiles.get(userId));
    }

    @Override
    public void saveProfile(UserProfile profile) {
        profiles.put(profile.userId(), profile);
    }

    @Override
    public List<PreferenceSignal> findPreferenceSignals(String userId) {
        return List.copyOf(signals.getOrDefault(userId, new CopyOnWriteArrayList<>()));
    }

    @Override
    public void savePreferenceSignal(PreferenceSignal signal) {
        CopyOnWriteArrayList<PreferenceSignal> values =
                signals.computeIfAbsent(signal.userId(), ignored -> new CopyOnWriteArrayList<>());
        values.removeIf(item -> item.signalId().equals(signal.signalId()));
        values.add(signal);
    }

    @Override
    public List<TripSnapshot> findRecentTripSnapshots(String userId, int limit) {
        return snapshots.getOrDefault(userId, new CopyOnWriteArrayList<>()).stream()
                .sorted(Comparator.comparing(TripSnapshot::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void saveTripSnapshot(TripSnapshot snapshot) {
        snapshots.computeIfAbsent(snapshot.userId(), ignored -> new CopyOnWriteArrayList<>()).add(snapshot);
    }

    @Override
    public List<EntityReference> findRecentEntityReferences(String userId, String sessionId, int limit) {
        return references.getOrDefault(userId, new CopyOnWriteArrayList<>()).stream()
                .filter(reference -> sessionId == null || sessionId.equals(reference.sessionId()))
                .sorted(Comparator.comparing(EntityReference::createdAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void saveEntityReference(EntityReference reference) {
        references.computeIfAbsent(reference.userId(), ignored -> new CopyOnWriteArrayList<>()).add(reference);
    }

    @Override
    public List<MemoryEpisode> findRecentEpisodes(String userId, int limit) {
        return episodes.getOrDefault(userId, new CopyOnWriteArrayList<>()).stream()
                .sorted(Comparator.comparing(MemoryEpisode::eventAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public void saveEpisode(MemoryEpisode episode) {
        episodes.computeIfAbsent(episode.userId(), ignored -> new CopyOnWriteArrayList<>()).add(episode);
    }

    @Override
    public void saveAudit(MemoryAudit audit) {
        audits.add(audit);
    }

    public List<MemoryAudit> audits() {
        return List.copyOf(audits);
    }
}
