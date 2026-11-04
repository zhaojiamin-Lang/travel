package com.travelguide.memory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 长期记忆和实体消解使用的数据模型。
 */
public final class MemoryModels {

    private MemoryModels() {
    }

    public enum SignalStatus {
        ACTIVE,
        SUPERSEDED,
        DELETED
    }

    public record UserProfile(
            String userId,
            Map<String, String> explicitPreferences,
            String consentVersion,
            Instant updatedAt
    ) {
        public UserProfile {
            explicitPreferences = Map.copyOf(explicitPreferences == null ? Map.of() : explicitPreferences);
        }
    }

    public record PreferenceSignal(
            String signalId,
            String userId,
            String type,
            String key,
            String value,
            double confidence,
            String sourceId,
            Instant observedAt,
            Instant expiresAt,
            SignalStatus status
    ) {
        public PreferenceSignal {
            status = status == null ? SignalStatus.ACTIVE : status;
        }
    }

    public record MemoryEpisode(
            String episodeId,
            String userId,
            String tripId,
            String summary,
            Instant eventAt,
            List<String> entityIds,
            List<String> sourceMessageIds
    ) {
        public MemoryEpisode {
            entityIds = List.copyOf(entityIds == null ? List.of() : entityIds);
            sourceMessageIds = List.copyOf(sourceMessageIds == null ? List.of() : sourceMessageIds);
        }
    }

    public record SnapshotEntity(
            String role,
            String entityId,
            String displayName
    ) {
    }

    public record TripSnapshot(
            String snapshotId,
            String userId,
            String tripId,
            String planId,
            int planVersion,
            String summary,
            List<SnapshotEntity> entities,
            Instant createdAt
    ) {
        public TripSnapshot {
            entities = List.copyOf(entities == null ? List.of() : entities);
        }
    }

    public record EntityReference(
            String referenceId,
            String userId,
            String sessionId,
            String surface,
            String resolvedEntityId,
            List<String> candidateEntityIds,
            double confidence,
            String resolutionMethod,
            String contextHash,
            Instant createdAt,
            Instant expiresAt
    ) {
        public EntityReference {
            candidateEntityIds = List.copyOf(candidateEntityIds == null ? List.of() : candidateEntityIds);
        }
    }

    public record MemoryAudit(
            String auditId,
            String userId,
            String memoryId,
            String action,
            String beforeHash,
            String afterHash,
            String reason,
            Instant createdAt
    ) {
    }

    public record EntityCandidate(
            String entityId,
            String displayName,
            String entityType,
            double confidence,
            String source
    ) {
    }

    public record ResolvedMention(
            String surface,
            String entityId,
            String displayName,
            double confidence,
            String source
    ) {
    }

    public record EntityResolution(
            String originalQuery,
            String standaloneQuery,
            List<ResolvedMention> resolvedMentions,
            List<EntityCandidate> ambiguousCandidates,
            List<String> unresolvedMentions
    ) {
        public EntityResolution {
            resolvedMentions = List.copyOf(resolvedMentions);
            ambiguousCandidates = List.copyOf(ambiguousCandidates);
            unresolvedMentions = List.copyOf(unresolvedMentions);
        }

        public List<String> resolvedEntityIds() {
            return resolvedMentions.stream().map(ResolvedMention::entityId).distinct().toList();
        }
    }

    public record RecalledMemory(
            PreferenceSignal signal,
            double score,
            String reason
    ) {
    }
}
