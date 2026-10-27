package com.travelguide.replan;

import com.travelguide.domain.PoiEntity;
import com.travelguide.repo.PoiRepository;
import com.travelguide.replan.ReplanModels.PlanChange;
import com.travelguide.service.AmapRouteService;
import com.travelguide.web.ApiDtos.DayPlan;
import com.travelguide.web.ApiDtos.MapPoint;
import com.travelguide.web.ApiDtos.PlanIssue;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.TransitView;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RemainingRouteReplanner {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final PoiRepository poiRepository;
    private final AmapRouteService routeService;

    public RemainingRouteReplanner(PoiRepository poiRepository, AmapRouteService routeService) {
        this.poiRepository = poiRepository;
        this.routeService = routeService;
    }

    public Outcome rebuildForClosure(PlanView base, String closedPoiId) {
        LocatedItem target = locateByPoi(base, closedPoiId)
                .orElseThrow(() -> new IllegalArgumentException("闭馆景点不在当前行程中"));
        return rebuild(base, target.dayIndex(), target.itemIndex(), 0, closedPoiId, "CLOSURE");
    }

    public Outcome rebuildForDelay(PlanView base, String currentItemId, int delayMinutes) {
        LocatedItem anchor = locateByItem(base, currentItemId)
                .orElseThrow(() -> new IllegalArgumentException("当前行程项不存在"));
        return rebuild(base, anchor.dayIndex(), anchor.itemIndex() + 1, delayMinutes, null, "DELAY");
    }

    private Outcome rebuild(
            PlanView base,
            int startDayIndex,
            int startItemIndex,
            int delayMinutes,
            String removedPoiId,
            String eventType
    ) {
        Map<String, PoiEntity> poiById = new LinkedHashMap<>();
        poiRepository.findAll().forEach(poi -> poiById.put(poi.getId(), poi));
        List<PlanChange> changes = new ArrayList<>();
        List<PlanIssue> issues = new ArrayList<>(base.issues());

        if ("DELAY".equals(eventType)) {
            changes.add(new PlanChange(
                    "DELAY",
                    null,
                    null,
                    "游客延误 " + delayMinutes + " 分钟，重新计算当前天剩余安排。"
            ));
        }
        if ("CLOSURE".equals(eventType)) {
            changes.add(new PlanChange(
                    "REMOVE_POI",
                    null,
                    removedPoiId,
                    "移除状态为不可游览的景点，并重新计算后续路线。"
            ));
        }

        List<DayPlan> rebuiltDays = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < base.dayPlans().size(); dayIndex++) {
            DayPlan day = base.dayPlans().get(dayIndex);
            if (dayIndex < startDayIndex) {
                rebuiltDays.add(day);
                continue;
            }

            int firstChangedIndex = dayIndex == startDayIndex ? startItemIndex : 0;
            int shift = dayIndex == startDayIndex && "DELAY".equals(eventType) ? delayMinutes : 0;
            DayPlan rebuilt = reflowDay(
                    day,
                    firstChangedIndex,
                    shift,
                    dayIndex == startDayIndex ? removedPoiId : null,
                    poiById,
                    changes,
                    issues
            );
            rebuiltDays.add(rebuilt);
        }

        List<MapPoint> mapPoints = rebuildMapPoints(rebuiltDays);
        BigDecimal estimatedCost = rebuiltDays.stream()
                .flatMap(day -> day.items().stream())
                .map(PlanItem::cost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean passed = issues.stream().noneMatch(issue -> "ERROR".equals(issue.severity()));
        String reason = "CLOSURE".equals(eventType) ? "实时重规划 · 闭馆" : "实时重规划 · 延误";
        PlanView proposed = new PlanView(
                "preview_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                base.requestId(),
                base.version() + 1,
                base.title(),
                base.subtitle(),
                base.city(),
                base.startDate(),
                base.days(),
                base.people(),
                base.budget(),
                estimatedCost,
                base.pace(),
                base.mustSee(),
                rebuiltDays,
                issues,
                passed,
                mapPoints,
                reason
        );
        return new Outcome(proposed, List.copyOf(changes));
    }

    private DayPlan reflowDay(
            DayPlan day,
            int startIndex,
            int shiftMinutes,
            String removedPoiId,
            Map<String, PoiEntity> poiById,
            List<PlanChange> changes,
            List<PlanIssue> issues
    ) {
        List<PlanItem> original = day.items();
        List<PlanItem> output = new ArrayList<>();
        int prefixEnd = Math.min(startIndex, original.size());
        output.addAll(original.subList(0, prefixEnd));

        LocalTime cursor = prefixEnd == 0
                ? LocalTime.of(9, 0)
                : LocalTime.parse(original.get(prefixEnd - 1).endTime());
        PoiEntity previousPoi = null;
        for (int index = prefixEnd - 1; index >= 0; index--) {
            PlanItem item = original.get(index);
            if (item.poiId() != null) {
                previousPoi = poiById.get(item.poiId());
                break;
            }
        }

        for (int index = prefixEnd; index < original.size(); index++) {
            PlanItem item = original.get(index);
            if (removedPoiId != null && removedPoiId.equals(item.poiId())) {
                changes.add(new PlanChange(
                        "REMOVE",
                        item.id(),
                        item.poiId(),
                        "移除 " + item.name()
                ));
                continue;
            }

            LocalTime originalStart = LocalTime.parse(item.startTime());
            LocalTime targetStart = originalStart.plusMinutes(shiftMinutes);
            int routeMinutes = 0;
            TransitView transit = item.transitFromPrevious();
            PoiEntity currentPoi = item.poiId() == null ? null : poiById.get(item.poiId());

            if (currentPoi != null && previousPoi != null) {
                AmapRouteService.RouteEstimate route = routeService.between(previousPoi, currentPoi);
                routeMinutes = route.minutes();
                transit = new TransitView(route.mode(), route.label(), routeMinutes);
                if (item.transitFromPrevious() == null
                        || item.transitFromPrevious().minutes() != routeMinutes) {
                    changes.add(new PlanChange(
                            "ROUTE",
                            item.id(),
                            item.poiId(),
                            item.name() + " 前一段路线更新为 " + routeMinutes + " 分钟"
                    ));
                }
            }

            LocalTime earliest = cursor.plusMinutes(routeMinutes);
            LocalTime start = targetStart.isAfter(earliest) ? targetStart : earliest;
            LocalTime end = start.plusMinutes(item.durationMinutes());
            if (!start.equals(originalStart)) {
                changes.add(new PlanChange(
                        "TIME",
                        item.id(),
                        item.poiId(),
                        item.name() + " 调整为 " + start.format(TIME_FORMAT) + "–" + end.format(TIME_FORMAT)
                ));
            }
            if (currentPoi != null && currentPoi.getCloseTime() != null
                    && currentPoi.getCloseTime().matches("\\d{1,2}:\\d{2}")
                    && end.isAfter(LocalTime.parse(currentPoi.getCloseTime()))) {
                issues.add(new PlanIssue(
                        "OPENING_HOURS",
                        "ERROR",
                        item.name() + " 的重规划时间超过记录关闭时间 " + currentPoi.getCloseTime(),
                        "需要省略该站或调整到另一天。"
                ));
            }

            output.add(new PlanItem(
                    item.id(),
                    item.type(),
                    item.poiId(),
                    item.name(),
                    item.shortName(),
                    item.area(),
                    start.format(TIME_FORMAT),
                    end.format(TIME_FORMAT),
                    item.durationMinutes(),
                    item.cost(),
                    item.note(),
                    item.sourceName(),
                    item.sourceUrl(),
                    item.accent(),
                    item.latitude(),
                    item.longitude(),
                    transit
            ));
            cursor = end;
            if (currentPoi != null) {
                previousPoi = currentPoi;
            }
        }
        return new DayPlan(
                day.dayNumber(),
                day.date(),
                day.label(),
                day.title(),
                day.summary(),
                output
        );
    }

    private List<MapPoint> rebuildMapPoints(List<DayPlan> days) {
        List<MapPoint> points = new ArrayList<>();
        int order = 1;
        for (DayPlan day : days) {
            for (PlanItem item : day.items()) {
                if ("POI".equals(item.type()) && item.poiId() != null) {
                    points.add(new MapPoint(
                            item.poiId(),
                            item.name(),
                            item.latitude(),
                            item.longitude(),
                            day.dayNumber(),
                            order++
                    ));
                }
            }
        }
        return points;
    }

    private java.util.Optional<LocatedItem> locateByItem(PlanView plan, String itemId) {
        for (int dayIndex = 0; dayIndex < plan.dayPlans().size(); dayIndex++) {
            List<PlanItem> items = plan.dayPlans().get(dayIndex).items();
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                if (items.get(itemIndex).id().equals(itemId)) {
                    return java.util.Optional.of(new LocatedItem(dayIndex, itemIndex));
                }
            }
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<LocatedItem> locateByPoi(PlanView plan, String poiId) {
        for (int dayIndex = 0; dayIndex < plan.dayPlans().size(); dayIndex++) {
            List<PlanItem> items = plan.dayPlans().get(dayIndex).items();
            for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
                if (poiId.equals(items.get(itemIndex).poiId())) {
                    return java.util.Optional.of(new LocatedItem(dayIndex, itemIndex));
                }
            }
        }
        return java.util.Optional.empty();
    }

    public record Outcome(PlanView proposedPlan, List<PlanChange> changes) {
    }

    private record LocatedItem(int dayIndex, int itemIndex) {
    }
}
