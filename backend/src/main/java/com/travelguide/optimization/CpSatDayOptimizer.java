package com.travelguide.optimization;

import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearArgument;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.Literal;
import com.travelguide.domain.PoiEntity;
import com.travelguide.optimization.OptimizerModels.DayOptimizationResult;
import com.travelguide.optimization.OptimizerModels.OptimizeDayRequest;
import com.travelguide.optimization.OptimizerModels.OptimizedStop;
import com.travelguide.repo.PoiRepository;
import com.travelguide.service.AmapRouteService;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DayPlan;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用于复杂单日重排的可选 CP-SAT 求解器。
 *
 * <p>现有规则排程仍是默认链路；只有显式调用优化接口时才使用 CP-SAT，
 * 且这里只返回预览，不会修改已保存的行程。</p>
 */
@Service
public class CpSatDayOptimizer {

    private static final int DAY_START = 9 * 60;
    private static final int DAY_END = 21 * 60;
    private static final int LUNCH_START = 12 * 60 + 10;
    private static final int LUNCH_END = 13 * 60 + 10;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");

    private final PlanningService planningService;
    private final PoiRepository poiRepository;
    private final AmapRouteService routeService;

    public CpSatDayOptimizer(
            PlanningService planningService,
            PoiRepository poiRepository,
            AmapRouteService routeService
    ) {
        this.planningService = planningService;
        this.poiRepository = poiRepository;
        this.routeService = routeService;
    }

    /**
     * 生成单日 CP-SAT 优化预览，不直接替换当前行程；
     * 调用方应先审核结果，再决定是否应用。
     */
    public DayOptimizationResult optimize(String planId, int dayNumber, OptimizeDayRequest request) {
        PlanView plan = planningService.getPlan(planId);
        DayPlan day = plan.dayPlans().stream()
                .filter(candidate -> candidate.dayNumber() == dayNumber)
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "行程日期不存在"));

        Set<String> excluded = new HashSet<>(safeList(request.excludedItemIds()));
        List<PlanItem> originalPois = day.items().stream()
                .filter(item -> "POI".equals(item.type()))
                .filter(item -> !excluded.contains(item.id()))
                .toList();
        if (originalPois.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该日没有可优化景点");
        }

        Set<String> locked = new HashSet<>(safeList(request.lockedItemIds()));
        Map<String, Integer> originalPosition = new HashMap<>();
        for (int index = 0; index < originalPois.size(); index++) {
            originalPosition.put(originalPois.get(index).id(), index);
        }
        if (!originalPosition.keySet().containsAll(locked)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "锁定项目中包含不属于该日的景点");
        }

        Map<String, PoiEntity> poiById = new LinkedHashMap<>();
        poiRepository.findAllById(originalPois.stream().map(PlanItem::poiId).toList())
                .forEach(poi -> poiById.put(poi.getId(), poi));

        int count = originalPois.size();
        int[][] travelMinutes = new int[count][count];
        for (int i = 0; i < count; i++) {
            for (int j = 0; j < count; j++) {
                if (i == j) {
                    travelMinutes[i][j] = 0;
                } else {
                    PoiEntity from = poiById.get(originalPois.get(i).poiId());
                    PoiEntity to = poiById.get(originalPois.get(j).poiId());
                    travelMinutes[i][j] = from == null || to == null
                            ? 24
                            : routeService.between(from, to).minutes();
                }
            }
        }

        try {
            Loader.loadNativeLibraries();
        } catch (RuntimeException | LinkageError error) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OR-Tools 原生库加载失败，当前规则排程不受影响"
            );
        }

        CpModel model = new CpModel();
        int earliestStart = DAY_START + Math.max(0, valueOrZero(request.delayMinutes()));

        // start[position] 保存从当天零点起的绝对分钟数。
        IntVar[] starts = new IntVar[count];
        IntVar[] ends = new IntVar[count];
        IntVar[] visitDurations = new IntVar[count];
        for (int position = 0; position < count; position++) {
            starts[position] = model.newIntVar(earliestStart, DAY_END, "start_" + position);
            ends[position] = model.newIntVar(earliestStart, DAY_END, "end_" + position);
            visitDurations[position] = model.newIntVar(15, 360, "duration_" + position);
        }

        // x[position][item] 表示某个景点被分配到唯一顺序位置。
        BoolVar[][] assigned = new BoolVar[count][count];
        for (int position = 0; position < count; position++) {
            for (int item = 0; item < count; item++) {
                assigned[position][item] = model.newBoolVar("x_" + position + "_" + item);
            }
            model.addExactlyOne(assigned[position]);
        }
        for (int item = 0; item < count; item++) {
            BoolVar[] byPosition = new BoolVar[count];
            for (int position = 0; position < count; position++) {
                byPosition[position] = assigned[position][item];
            }
            model.addExactlyOne(byPosition);
            if (locked.contains(originalPois.get(item).id())) {
                model.addEquality(assigned[originalPosition.get(originalPois.get(item).id())][item], 1);
            }
        }

        // y[position][previous][current] 表示相邻景点关系，并承载路线时间。
        BoolVar[][][] transition = new BoolVar[count][count][count];
        LinearArgument[] objectiveTerms = new LinearArgument[1 + count * count];
        long[] objectiveWeights = new long[objectiveTerms.length];
        int objectiveIndex = 0;

        for (int position = 1; position < count; position++) {
            List<BoolVar> candidates = new ArrayList<>();
            for (int previous = 0; previous < count; previous++) {
                for (int current = 0; current < count; current++) {
                    BoolVar edge = model.newBoolVar("y_" + position + "_" + previous + "_" + current);
                    transition[position][previous][current] = edge;
                    if (previous != current) {
                        candidates.add(edge);
                        model.addLessOrEqual(edge, assigned[position - 1][previous]);
                        model.addLessOrEqual(edge, assigned[position][current]);
                        model.addEquality(edge, 1).onlyEnforceIf(new com.google.ortools.sat.Literal[]{
                                assigned[position - 1][previous],
                                assigned[position][current]
                        });
                    } else {
                        model.addEquality(edge, 0);
                    }
                }
            }
            model.addExactlyOne(candidates.toArray(new Literal[0]));
        }

        for (int position = 0; position < count; position++) {
            LinearArgument[] durationTerms = new LinearArgument[count];
            long[] durationWeights = new long[count];
            for (int item = 0; item < count; item++) {
                durationTerms[item] = assigned[position][item];
                durationWeights[item] = Math.max(15, originalPois.get(item).durationMinutes());
                PoiEntity poi = poiById.get(originalPois.get(item).poiId());
                if (poi != null) {
                    addOpeningWindow(model, assigned[position][item], starts[position], ends[position], poi);
                }
            }
            LinearExpr duration = LinearExpr.weightedSum(durationTerms, durationWeights);
            model.addEquality(visitDurations[position], duration);
            IntervalVar interval = model.newIntervalVar(
                    starts[position], visitDurations[position], ends[position], "visit_" + position
            );
            model.addNoOverlap(new IntervalVar[]{
                    interval,
                    model.newFixedInterval(LUNCH_START, LUNCH_END - LUNCH_START, "lunch_" + position)
            });

            if (position > 0) {
                LinearArgument[] travelTerms = new LinearArgument[count * count];
                long[] travelWeights = new long[count * count];
                int index = 0;
                for (int previous = 0; previous < count; previous++) {
                    for (int current = 0; current < count; current++) {
                        travelTerms[index] = transition[position][previous][current];
                        travelWeights[index] = travelMinutes[previous][current];
                        index++;
                    }
                }
                model.addGreaterOrEqual(
                        starts[position],
                        LinearExpr.sum(new LinearArgument[]{
                                starts[position - 1],
                                visitDurations[position - 1],
                                LinearExpr.weightedSum(travelTerms, travelWeights)
                        })
                );
            }
        }

        // 优先最小化结束时间，同时对顺序变化增加少量惩罚。
        objectiveTerms[objectiveIndex] = ends[count - 1];
        objectiveWeights[objectiveIndex++] = 1;
        for (int position = 0; position < count; position++) {
            for (int item = 0; item < count; item++) {
                objectiveTerms[objectiveIndex] = assigned[position][item];
                objectiveWeights[objectiveIndex++] = position == originalPosition.get(originalPois.get(item).id()) ? 0 : 5;
            }
        }
        model.minimize(LinearExpr.weightedSum(objectiveTerms, objectiveWeights));
        String validationError = model.validate();
        if (!validationError.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "CP-SAT 模型校验失败：" + validationError
            );
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(
                request.maxSolveSeconds() == null ? 3.0 : request.maxSolveSeconds()
        );
        solver.getParameters().setNumSearchWorkers(4);
        CpSolverStatus status = solver.solve(model);
        if (status != CpSolverStatus.OPTIMAL && status != CpSolverStatus.FEASIBLE) {
            throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "当前约束下没有可行排程（" + status.name() + "），建议减少景点或放宽时间限制"
            );
        }

        List<OptimizedStop> stops = new ArrayList<>();
        int previous = -1;
        for (int position = 0; position < count; position++) {
            int item = 0;
            for (int candidate = 0; candidate < count; candidate++) {
                if (solver.booleanValue(assigned[position][candidate])) {
                    item = candidate;
                    break;
                }
            }
            PlanItem source = originalPois.get(item);
            int start = (int) solver.value(starts[position]);
            int end = (int) solver.value(ends[position]);
            int travel = previous < 0 ? 0 : travelMinutes[previous][item];
            stops.add(new OptimizedStop(
                    source.id(),
                    source.poiId(),
                    source.name(),
                    LocalTime.of(start / 60, start % 60).format(TIME_FORMAT),
                    LocalTime.of(end / 60, end % 60).format(TIME_FORMAT),
                    (int) (end - start),
                    travel,
                    originalPosition.get(source.id()),
                    position
            ));
            previous = item;
        }

        List<String> notes = new ArrayList<>();
        notes.add("CP-SAT 会同时考虑开放时间、景点时长、午休窗口和高德路线时间。");
        notes.add("该结果仅为优化预览，现有规则排程保持不变。");
        return new DayOptimizationResult(
                plan.id(),
                dayNumber,
                "OR-Tools CP-SAT",
                status.name(),
                solver.objectiveValue(),
                solver.wallTime(),
                stops.size(),
                stops,
                notes
        );
    }

    private void addOpeningWindow(
            CpModel model,
            BoolVar assigned,
            IntVar start,
            IntVar end,
            PoiEntity poi
    ) {
        Integer open = parseMinute(poi.getOpenTime());
        Integer close = parseMinute(poi.getCloseTime());
        if (open != null) {
            model.addGreaterOrEqual(start, Math.max(DAY_START, open)).onlyEnforceIf(assigned);
        }
        if (close != null) {
            model.addLessOrEqual(end, Math.min(DAY_END, close)).onlyEnforceIf(assigned);
        }
    }

    private Integer parseMinute(String value) {
        if (value == null || !value.matches("\\d{1,2}:\\d{2}")) {
            return null;
        }
        LocalTime time = LocalTime.parse(value);
        return time.getHour() * 60 + time.getMinute();
    }

    private int valueOrZero(Integer value) {
        return value == null ? 0 : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }
}
