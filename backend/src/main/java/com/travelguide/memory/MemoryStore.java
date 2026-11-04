package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.EntityReference;
import com.travelguide.memory.MemoryModels.MemoryAudit;
import com.travelguide.memory.MemoryModels.MemoryEpisode;
import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.memory.MemoryModels.UserProfile;

import java.util.List;
import java.util.Optional;

/**
 * Mongo 记忆存储适配接口。
 *
 * <p>本模块不直接依赖 Mongo 驱动；生产实现应保证 userId 隔离、TTL 索引和审计写入。</p>
 */
public interface MemoryStore {

    Optional<UserProfile> findProfile(String userId);

    void saveProfile(UserProfile profile);

    List<PreferenceSignal> findPreferenceSignals(String userId);

    void savePreferenceSignal(PreferenceSignal signal);

    List<TripSnapshot> findRecentTripSnapshots(String userId, int limit);

    void saveTripSnapshot(TripSnapshot snapshot);

    List<EntityReference> findRecentEntityReferences(String userId, String sessionId, int limit);

    void saveEntityReference(EntityReference reference);

    List<MemoryEpisode> findRecentEpisodes(String userId, int limit);

    void saveEpisode(MemoryEpisode episode);

    void saveAudit(MemoryAudit audit);
}
