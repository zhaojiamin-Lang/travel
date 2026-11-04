package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.EntityCandidate;
import com.travelguide.memory.MemoryModels.EntityReference;
import com.travelguide.memory.MemoryModels.EntityResolution;
import com.travelguide.memory.MemoryModels.ResolvedMention;
import com.travelguide.memory.MemoryModels.SnapshotEntity;
import com.travelguide.memory.MemoryModels.TripSnapshot;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.QueryContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 基于稳定实体 ID 的指代消解器。
 *
 * <p>当前景点、别名和最近行程实体优先；候选落在 0.60–0.85 时返回歧义，
 * 不把向量最近邻直接写回长期记忆。</p>
 */
public final class EntityResolver {

    private static final Pattern HERE = Pattern.compile("这里|这儿|这边|这个地方|这个景点|它");
    private static final Pattern MEMORY_OBJECT = Pattern.compile(
            "(?:上次|之前|刚才)?(?:那家|那个|去过的)\\s*(酒店|餐厅|景点|地方)"
    );

    private final MemoryStore store;
    private final Map<String, List<EntityCandidate>> aliasCandidates;
    private final List<String> aliasesByLength;

    public EntityResolver(List<KnowledgeDocument> documents, MemoryStore store) {
        this.store = store;
        Map<String, Map<String, EntityCandidate>> index = new LinkedHashMap<>();
        for (KnowledgeDocument document : documents) {
            for (String alias : document.metadata().aliases()) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                for (String entityId : document.metadata().entityIds()) {
                    index.computeIfAbsent(alias.strip(), ignored -> new LinkedHashMap<>())
                            .put(entityId, new EntityCandidate(
                                    entityId,
                                    document.metadata().title(),
                                    document.metadata().docType().name(),
                                    0.95,
                                    "DOCUMENT_ALIAS"
                            ));
                }
            }
        }
        this.aliasCandidates = index.entrySet().stream().collect(
                LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), List.copyOf(entry.getValue().values())),
                LinkedHashMap::putAll
        );
        this.aliasesByLength = aliasCandidates.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public EntityResolution resolve(
            String userId,
            String sessionId,
            String query,
            QueryContext context
    ) {
        String standalone = query == null ? "" : query.strip();
        Instant now = context.asOf() == null ? Instant.now() : context.asOf();
        List<ResolvedMention> resolved = new ArrayList<>();
        List<EntityCandidate> ambiguous = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();

        if (HERE.matcher(standalone).find()) {
            if (hasText(context.currentPoiId()) && hasText(context.currentPoiName())) {
                standalone = HERE.matcher(standalone).replaceAll(context.currentPoiName());
                resolved.add(new ResolvedMention(
                        "当前景点",
                        context.currentPoiId(),
                        context.currentPoiName(),
                        0.98,
                        "GUIDE_CONTEXT"
                ));
            } else {
                unresolved.add("当前景点指代");
            }
        }

        if (standalone.contains("下一站")) {
            if (hasText(context.nextPoiId()) && hasText(context.nextPoiName())) {
                standalone = standalone.replace("下一站", context.nextPoiName());
                resolved.add(new ResolvedMention(
                        "下一站",
                        context.nextPoiId(),
                        context.nextPoiName(),
                        0.98,
                        "PLAN_CONTEXT"
                ));
            } else {
                unresolved.add("下一站");
            }
        }

        Matcher memoryMatcher = MEMORY_OBJECT.matcher(standalone);
        while (memoryMatcher.find()) {
            String surface = memoryMatcher.group();
            String role = role(memoryMatcher.group(1));
            List<EntityCandidate> candidates = recentTripCandidates(userId, role);
            if (candidates.size() == 1) {
                EntityCandidate candidate = candidates.get(0);
                standalone = standalone.replace(surface, candidate.displayName());
                resolved.add(new ResolvedMention(
                        surface,
                        candidate.entityId(),
                        candidate.displayName(),
                        0.90,
                        "TRIP_SNAPSHOT"
                ));
            } else if (candidates.size() > 1) {
                ambiguous.addAll(candidates);
                unresolved.add(surface);
            } else {
                unresolved.add(surface);
            }
        }

        List<Span> spans = new ArrayList<>();
        for (String alias : aliasesByLength) {
            int cursor = 0;
            while ((cursor = standalone.indexOf(alias, cursor)) >= 0) {
                int end = cursor + alias.length();
                if (!overlaps(spans, cursor, end)) {
                    // 已由“上次那家酒店”等记忆结果替换出的完整名称，不再按其中子串重复绑定 POI。
                    boolean insideResolvedLabel = false;
                    for (ResolvedMention mention : resolved) {
                        if (mention.displayName().contains(alias)
                                && standalone.contains(mention.displayName())) {
                            insideResolvedLabel = true;
                            break;
                        }
                    }
                    if (insideResolvedLabel) {
                        cursor = end;
                        continue;
                    }
                    List<EntityCandidate> candidates = aliasCandidates.getOrDefault(alias, List.of());
                    if (candidates.size() == 1) {
                        EntityCandidate candidate = candidates.get(0);
                        resolved.add(new ResolvedMention(
                                alias,
                                candidate.entityId(),
                                candidate.displayName(),
                                candidate.confidence(),
                                "DOCUMENT_ALIAS"
                        ));
                    } else if (candidates.size() > 1) {
                        ambiguous.addAll(candidates);
                    }
                    spans.add(new Span(cursor, end));
                }
                cursor = end;
            }
        }

        EntityResolution result = new EntityResolution(
                query,
                standalone,
                deduplicateMentions(resolved),
                deduplicateCandidates(ambiguous),
                unresolved.stream().distinct().toList()
        );
        recordReferences(userId, sessionId, result, now);
        return result;
    }

    private List<EntityCandidate> recentTripCandidates(String userId, String role) {
        if (!hasText(userId)) {
            return List.of();
        }
        Map<String, EntityCandidate> candidates = new LinkedHashMap<>();
        for (TripSnapshot snapshot : store.findRecentTripSnapshots(userId, 10)) {
            for (SnapshotEntity entity : snapshot.entities()) {
                if (role.equalsIgnoreCase(entity.role()) || entity.entityId().startsWith(rolePrefix(role))) {
                    candidates.putIfAbsent(entity.entityId(), new EntityCandidate(
                            entity.entityId(),
                            entity.displayName(),
                            role,
                            0.88,
                            "TRIP_SNAPSHOT"
                    ));
                }
            }
            if (!candidates.isEmpty()) {
                break;
            }
        }
        return List.copyOf(candidates.values());
    }

    private String role(String word) {
        return switch (word) {
            case "酒店" -> "HOTEL";
            case "餐厅" -> "RESTAURANT";
            default -> "POI";
        };
    }

    private String rolePrefix(String role) {
        return switch (role) {
            case "HOTEL" -> "hotel:";
            case "RESTAURANT" -> "restaurant:";
            default -> "poi:";
        };
    }

    private void recordReferences(
            String userId,
            String sessionId,
            EntityResolution resolution,
            Instant now
    ) {
        if (!hasText(userId)) {
            return;
        }
        for (ResolvedMention mention : resolution.resolvedMentions()) {
            store.saveEntityReference(new EntityReference(
                    "ref_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    userId,
                    sessionId,
                    mention.surface(),
                    mention.entityId(),
                    List.of(mention.entityId()),
                    mention.confidence(),
                    mention.source(),
                    MemoryService.hash(resolution.originalQuery()),
                    now,
                    now.plus(Duration.ofDays(30))
            ));
        }
    }

    private List<ResolvedMention> deduplicateMentions(List<ResolvedMention> values) {
        Map<String, ResolvedMention> unique = new LinkedHashMap<>();
        for (ResolvedMention value : values) {
            String key = value.surface() + "|" + value.entityId();
            unique.putIfAbsent(key, value);
        }
        return List.copyOf(unique.values());
    }

    private List<EntityCandidate> deduplicateCandidates(List<EntityCandidate> values) {
        Map<String, EntityCandidate> unique = new LinkedHashMap<>();
        values.forEach(value -> unique.putIfAbsent(value.entityId(), value));
        return List.copyOf(unique.values());
    }

    private boolean overlaps(List<Span> spans, int start, int end) {
        return spans.stream().anyMatch(span -> start < span.end() && end > span.start());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record Span(int start, int end) {
    }
}
