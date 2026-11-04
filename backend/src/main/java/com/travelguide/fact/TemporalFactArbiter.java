package com.travelguide.fact;

import com.travelguide.fact.FactModels.FactCollection;
import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactRecord;
import com.travelguide.fact.FactModels.FactResolution;
import com.travelguide.fact.FactModels.FactStatus;
import com.travelguide.fact.FactModels.SourceAuthority;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * 动态事实的 As-Of 有效性和冲突仲裁。
 *
 * <p>先按适用条件筛选，再比较条件具体程度、来源权威性和观测时间。
 * 同等权威的不同来源给出不同值时保留冲突，不静默选择其中一条。</p>
 */
public final class TemporalFactArbiter {

    public FactResolution resolve(FactQuery query, FactCollection collection) {
        List<FactRecord> matching = collection.facts().stream()
                .filter(fact -> Objects.equals(query.entityId(), fact.entityId()))
                .filter(fact -> Objects.equals(query.factType(), fact.factType()))
                .toList();
        if (matching.isEmpty()) {
            return new FactResolution(
                    query.entityId(),
                    query.factType(),
                    FactStatus.UNAVAILABLE,
                    null,
                    List.of(),
                    List.of(),
                    List.of(),
                    failuresOrUnavailable(collection)
            );
        }

        Map<String, String> queryContext = new LinkedHashMap<>(query.context());
        if (query.applicableDate() != null) {
            queryContext.putIfAbsent("applicableDate", query.applicableDate().toString());
        }

        List<FactRecord> applicable = matching.stream()
                .filter(fact -> conditionsMatch(fact.conditions(), queryContext))
                .toList();
        List<FactRecord> valid = applicable.stream()
                .filter(fact -> validAt(fact, query.asOf()))
                .sorted(arbitrationOrder(queryContext))
                .toList();
        List<FactRecord> stale = applicable.stream()
                .filter(fact -> !validAt(fact, query.asOf()))
                .sorted(Comparator.comparing(FactRecord::observedAt).reversed())
                .toList();

        if (valid.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            reasons.add("没有在 asOf 时间有效的事实");
            reasons.addAll(collection.toolFailures().entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .toList());
            return new FactResolution(
                    query.entityId(),
                    query.factType(),
                    FactStatus.STALE,
                    null,
                    List.of(),
                    stale,
                    List.of(),
                    reasons
            );
        }

        FactRecord selected = valid.get(0);
        int selectedSpecificity = specificity(selected, queryContext);
        List<FactRecord> conflicts = valid.stream()
                .skip(1)
                // 条件和权威性都相同的不同来源如果值不同，必须保留冲突。
                .filter(candidate -> specificity(candidate, queryContext) == selectedSpecificity)
                .filter(candidate -> candidate.authority() == selected.authority())
                .filter(candidate -> !candidate.sourceId().equals(selected.sourceId()))
                .filter(candidate -> !valuesEqual(candidate.value(), selected.value()))
                .toList();

        if (!conflicts.isEmpty()) {
            List<FactRecord> allConflicts = Stream.concat(Stream.of(selected), conflicts.stream()).toList();
            return new FactResolution(
                    query.entityId(),
                    query.factType(),
                    FactStatus.CONFLICT,
                    null,
                    valid,
                    stale,
                    allConflicts,
                    List.of("同等具体程度和来源权威性的数据源给出了不同值")
            );
        }

        List<String> reasons = new ArrayList<>();
        reasons.add("按条件具体程度、来源权威性和观测时间选择");
        reasons.addAll(collection.toolFailures().entrySet().stream()
                .map(entry -> entry.getKey() + ": " + entry.getValue())
                .toList());
        return new FactResolution(
                query.entityId(),
                query.factType(),
                FactStatus.VALID,
                selected,
                valid,
                stale,
                List.of(),
                reasons
        );
    }

    private Comparator<FactRecord> arbitrationOrder(Map<String, String> queryContext) {
        return Comparator
                .comparingInt((FactRecord fact) -> specificity(fact, queryContext)).reversed()
                .thenComparing(Comparator.comparingInt(
                        (FactRecord fact) -> fact.authority().priority()
                ).reversed())
                .thenComparing(Comparator.comparingDouble(FactRecord::confidence).reversed())
                .thenComparing(Comparator.comparing(FactRecord::observedAt).reversed())
                .thenComparing(FactRecord::factId);
    }

    private boolean conditionsMatch(Map<String, String> conditions, Map<String, String> context) {
        for (Map.Entry<String, String> condition : conditions.entrySet()) {
            String actual = context.get(condition.getKey());
            if (actual == null || !Objects.equals(actual, condition.getValue())) {
                return false;
            }
        }
        return true;
    }

    private int specificity(FactRecord fact, Map<String, String> context) {
        int score = 0;
        for (Map.Entry<String, String> condition : fact.conditions().entrySet()) {
            if (Objects.equals(context.get(condition.getKey()), condition.getValue())) {
                score++;
            }
        }
        return score;
    }

    private boolean validAt(FactRecord fact, Instant asOf) {
        if (fact.validFrom() != null && asOf.isBefore(fact.validFrom())) {
            return false;
        }
        return fact.validTo() == null || !asOf.isAfter(fact.validTo());
    }

    private boolean valuesEqual(Map<String, Object> left, Map<String, Object> right) {
        return normalize(left).equals(normalize(right));
    }

    private String normalize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros().toPlainString();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                    .map(entry -> entry.getKey() + "=" + normalize(entry.getValue()))
                    .toList()
                    .toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<String> values = new ArrayList<>();
            iterable.forEach(item -> values.add(normalize(item)));
            return values.toString();
        }
        return value.toString();
    }

    private List<String> failuresOrUnavailable(FactCollection collection) {
        if (!collection.toolFailures().isEmpty()) {
            return collection.toolFailures().entrySet().stream()
                    .map(entry -> entry.getKey() + ": " + entry.getValue())
                    .toList();
        }
        return List.of("没有动态事实工具返回该事实");
    }
}
