package com.travelguide.intelligence;

import com.travelguide.intelligence.PlanDiffModels.DiffSummary;
import com.travelguide.intelligence.PlanDiffModels.PlanDiffView;
import com.travelguide.intelligence.PlanDiffModels.SemanticChange;
import com.travelguide.web.ApiDtos.DayPlan;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.TransitView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class PlanDiffService {

    public PlanDiffView diff(PlanView from, PlanView to) {
        if (!Objects.equals(from.requestId(), to.requestId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能比较同一行程的版本");
        }

        Map<String, LocatedItem> before = flatten(from);
        Map<String, LocatedItem> after = flatten(to);
        List<SemanticChange> changes = new ArrayList<>();

        for (String itemId : before.keySet()) {
            LocatedItem oldItem = before.get(itemId);
            LocatedItem newItem = after.get(itemId);
            if (newItem == null) {
                changes.add(new SemanticChange(
                        "ITEM_REMOVED",
                        oldItem.dayNumber(),
                        itemId,
                        oldItem.item().name(),
                        null,
                        "移除了 " + oldItem.item().name()
                ));
                continue;
            }
            compareItem(oldItem, newItem, changes);
        }
        for (String itemId : after.keySet()) {
            if (!before.containsKey(itemId)) {
                LocatedItem newItem = after.get(itemId);
                changes.add(new SemanticChange(
                        "ITEM_ADDED",
                        newItem.dayNumber(),
                        itemId,
                        null,
                        newItem.item().name(),
                        "新增了 " + newItem.item().name()
                ));
            }
        }

        changes.addAll(orderChanges(from, to));
        if (from.budget().compareTo(to.budget()) != 0) {
            changes.add(new SemanticChange(
                    "BUDGET_CHANGED",
                    0,
                    null,
                    from.budget().toPlainString(),
                    to.budget().toPlainString(),
                    "行程预算上限发生变化"
            ));
        }
        if (!Objects.equals(from.pace(), to.pace())) {
            changes.add(new SemanticChange(
                    "PACE_CHANGED",
                    0,
                    null,
                    from.pace(),
                    to.pace(),
                    "旅行节奏发生变化"
            ));
        }
        if (!issueSignature(from).equals(issueSignature(to))) {
            changes.add(new SemanticChange(
                    "ISSUE_CHANGED",
                    0,
                    null,
                    String.join(",", issueSignature(from)),
                    String.join(",", issueSignature(to)),
                    "行程校验结果发生变化"
            ));
        }
        changes.sort(Comparator
                .comparingInt(SemanticChange::dayNumber)
                .thenComparing(change -> change.itemId() == null ? "" : change.itemId()));

        BigDecimal costDelta = to.estimatedCost().subtract(from.estimatedCost());
        int additions = count(changes, "ITEM_ADDED");
        int removals = count(changes, "ITEM_REMOVED");
        int replacements = count(changes, "POI_REPLACED");
        int timeChanges = count(changes, "TIME_CHANGED");
        int other = changes.size() - additions - removals - replacements - timeChanges;
        return new PlanDiffView(
                from.id(),
                to.id(),
                from.version(),
                to.version(),
                changes.isEmpty(),
                List.copyOf(changes),
                new DiffSummary(additions, removals, replacements, timeChanges, Math.max(0, other), costDelta)
        );
    }

    private void compareItem(LocatedItem before, LocatedItem after, List<SemanticChange> changes) {
        PlanItem oldItem = before.item();
        PlanItem newItem = after.item();
        if (!Objects.equals(oldItem.poiId(), newItem.poiId())
                || !Objects.equals(oldItem.name(), newItem.name())) {
            changes.add(new SemanticChange(
                    "POI_REPLACED",
                    before.dayNumber(),
                    oldItem.id(),
                    oldItem.name(),
                    newItem.name(),
                    "将 " + oldItem.name() + " 替换为 " + newItem.name()
            ));
        }
        if (!Objects.equals(oldItem.startTime(), newItem.startTime())
                || !Objects.equals(oldItem.endTime(), newItem.endTime())) {
            changes.add(new SemanticChange(
                    "TIME_CHANGED",
                    before.dayNumber(),
                    oldItem.id(),
                    oldItem.startTime() + "–" + oldItem.endTime(),
                    newItem.startTime() + "–" + newItem.endTime(),
                    oldItem.name() + " 的时间发生变化"
            ));
        }
        if (oldItem.durationMinutes() != newItem.durationMinutes()) {
            changes.add(new SemanticChange(
                    "DURATION_CHANGED",
                    before.dayNumber(),
                    oldItem.id(),
                    String.valueOf(oldItem.durationMinutes()),
                    String.valueOf(newItem.durationMinutes()),
                    oldItem.name() + " 的停留时长发生变化"
            ));
        }
        if (oldItem.cost().compareTo(newItem.cost()) != 0) {
            changes.add(new SemanticChange(
                    "COST_CHANGED",
                    before.dayNumber(),
                    oldItem.id(),
                    oldItem.cost().toPlainString(),
                    newItem.cost().toPlainString(),
                    oldItem.name() + " 的费用发生变化"
            ));
        }
        if (!sameTransit(oldItem.transitFromPrevious(), newItem.transitFromPrevious())) {
            changes.add(new SemanticChange(
                    "TRANSIT_CHANGED",
                    before.dayNumber(),
                    oldItem.id(),
                    transitLabel(oldItem.transitFromPrevious()),
                    transitLabel(newItem.transitFromPrevious()),
                    oldItem.name() + " 前的交通时间发生变化"
            ));
        }
        if (before.dayNumber() != after.dayNumber() || before.position() != after.position()) {
            changes.add(new SemanticChange(
                    "ORDER_CHANGED",
                    after.dayNumber(),
                    oldItem.id(),
                    "第" + before.dayNumber() + "天第" + before.position() + "站",
                    "第" + after.dayNumber() + "天第" + after.position() + "站",
                    oldItem.name() + " 的顺序发生变化"
            ));
        }
        if (!Objects.equals(oldItem.sourceName(), newItem.sourceName())) {
            changes.add(new SemanticChange(
                    "EVIDENCE_CHANGED",
                    before.dayNumber(),
                    oldItem.id(),
                    oldItem.sourceName(),
                    newItem.sourceName(),
                    oldItem.name() + " 的资料依据发生变化"
            ));
        }
    }

    private List<SemanticChange> orderChanges(PlanView before, PlanView after) {
        List<SemanticChange> changes = new ArrayList<>();
        Map<Integer, List<String>> beforeOrder = dayPoiOrder(before);
        Map<Integer, List<String>> afterOrder = dayPoiOrder(after);
        for (Integer day : beforeOrder.keySet()) {
            List<String> oldOrder = beforeOrder.get(day);
            List<String> newOrder = afterOrder.getOrDefault(day, List.of());
            if (new HashSet<>(oldOrder).equals(new HashSet<>(newOrder)) && !oldOrder.equals(newOrder)) {
                changes.add(new SemanticChange(
                        "DAY_ORDER_CHANGED",
                        day,
                        null,
                        String.join(" → ", oldOrder),
                        String.join(" → ", newOrder),
                        "第" + day + "天景点顺序发生变化"
                ));
            }
        }
        return changes;
    }

    private Map<Integer, List<String>> dayPoiOrder(PlanView plan) {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        for (DayPlan day : plan.dayPlans()) {
            result.put(
                    day.dayNumber(),
                    day.items().stream()
                            .filter(item -> "POI".equals(item.type()))
                            .map(PlanItem::name)
                            .toList()
            );
        }
        return result;
    }

    private Map<String, LocatedItem> flatten(PlanView plan) {
        Map<String, LocatedItem> result = new HashMap<>();
        for (DayPlan day : plan.dayPlans()) {
            int poiPosition = 0;
            for (PlanItem item : day.items()) {
                int position = "POI".equals(item.type()) ? ++poiPosition : 0;
                result.put(item.id(), new LocatedItem(day.dayNumber(), position, item));
            }
        }
        return result;
    }

    private boolean sameTransit(TransitView before, TransitView after) {
        return Objects.equals(before == null ? null : before.minutes(), after == null ? null : after.minutes())
                && Objects.equals(before == null ? null : before.label(), after == null ? null : after.label());
    }

    private String transitLabel(TransitView transit) {
        return transit == null ? "无" : transit.label() + " " + transit.minutes() + " 分钟";
    }

    private Set<String> issueSignature(PlanView plan) {
        Set<String> result = new HashSet<>();
        plan.issues().forEach(issue -> result.add(issue.code() + ":" + issue.severity()));
        return result;
    }

    private int count(List<SemanticChange> changes, String type) {
        return (int) changes.stream().filter(change -> type.equals(change.type())).count();
    }

    private record LocatedItem(int dayNumber, int position, PlanItem item) {
    }
}
