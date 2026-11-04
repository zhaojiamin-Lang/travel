package com.travelguide.fact;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 动态事实模块的数据模型。
 */
public final class FactModels {

    private FactModels() {
    }

    public enum SourceAuthority {
        OFFICIAL_LIVE(5),
        OFFICIAL_STATIC(4),
        AUTHORIZED_THIRD_PARTY(3),
        PUBLIC_EDITORIAL(2),
        UGC(1);

        private final int priority;

        SourceAuthority(int priority) {
            this.priority = priority;
        }

        public int priority() {
            return priority;
        }
    }

    public enum FactStatus {
        VALID,
        STALE,
        CONFLICT,
        UNAVAILABLE
    }

    public record FactQuery(
            String entityId,
            String factType,
            Instant asOf,
            LocalDate applicableDate,
            Map<String, String> context
    ) {
        public FactQuery {
            if (entityId == null || entityId.isBlank() || factType == null || factType.isBlank()) {
                throw new IllegalArgumentException("动态事实必须指定实体和事实类型");
            }
            asOf = asOf == null ? Instant.now() : asOf;
            context = Map.copyOf(context == null ? Map.of() : context);
        }
    }

    public record FactRecord(
            String factId,
            String entityId,
            String factType,
            Map<String, Object> value,
            String sourceId,
            String sourceName,
            SourceAuthority authority,
            Instant observedAt,
            Instant validFrom,
            Instant validTo,
            Map<String, String> conditions,
            double confidence
    ) {
        public FactRecord {
            if (factId == null || factId.isBlank() || entityId == null || entityId.isBlank()) {
                throw new IllegalArgumentException("事实必须包含 factId 和 entityId");
            }
            factType = factType == null ? "" : factType;
            value = Map.copyOf(value == null ? Map.of() : value);
            sourceId = sourceId == null ? "" : sourceId;
            sourceName = sourceName == null ? "" : sourceName;
            authority = authority == null ? SourceAuthority.PUBLIC_EDITORIAL : authority;
            observedAt = observedAt == null ? Instant.now() : observedAt;
            conditions = Map.copyOf(conditions == null ? Map.of() : conditions);
        }
    }

    public record FactCollection(
            List<FactRecord> facts,
            Map<String, String> toolFailures
    ) {
        public FactCollection {
            facts = List.copyOf(facts);
            toolFailures = Map.copyOf(toolFailures);
        }
    }

    public record FactResolution(
            String entityId,
            String factType,
            FactStatus status,
            FactRecord selected,
            List<FactRecord> validFacts,
            List<FactRecord> staleFacts,
            List<FactRecord> conflicts,
            List<String> reasons
    ) {
        public FactResolution {
            validFacts = List.copyOf(validFacts);
            staleFacts = List.copyOf(staleFacts);
            conflicts = List.copyOf(conflicts);
            reasons = List.copyOf(reasons);
        }

        public boolean usable() {
            return status == FactStatus.VALID && selected != null;
        }
    }
}
