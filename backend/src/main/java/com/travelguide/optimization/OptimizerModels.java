package com.travelguide.optimization;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

public final class OptimizerModels {

    private OptimizerModels() {
    }

    public record OptimizeDayRequest(
            @Min(value = 0, message = "延误时间不能为负数")
            @Max(value = 720, message = "延误时间不能超过 720 分钟")
            Integer delayMinutes,
            List<String> lockedItemIds,
            List<String> excludedItemIds,
            @Min(value = 1, message = "求解时间至少 1 秒")
            @Max(value = 10, message = "求解时间最多 10 秒")
            Double maxSolveSeconds
    ) {
    }

    public record OptimizedStop(
            String itemId,
            String poiId,
            String name,
            String startTime,
            String endTime,
            int durationMinutes,
            int travelMinutesFromPrevious,
            int originalPosition,
            int optimizedPosition
    ) {
    }

    public record DayOptimizationResult(
            String planId,
            int dayNumber,
            String solver,
            String status,
            double objectiveValue,
            double solveSeconds,
            int stopCount,
            List<OptimizedStop> stops,
            List<String> notes
    ) {
    }
}
