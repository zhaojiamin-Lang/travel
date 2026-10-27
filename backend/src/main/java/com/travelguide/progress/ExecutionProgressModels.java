package com.travelguide.progress;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;
import java.util.List;

public final class ExecutionProgressModels {

    private ExecutionProgressModels() {
    }

    public record SaveProgressRequest(
            String currentItemId,
            List<String> completedItemIds,
            @Pattern(
                    regexp = "NOT_STARTED|IN_PROGRESS|PAUSED|COMPLETED",
                    message = "进度状态不合法"
            )
            String status,
            Instant currentItemStartedAt,
            Instant actualEndedAt,
            @Min(value = 0, message = "延误时间不能为负数")
            @Max(value = 1440, message = "延误时间不能超过 1440 分钟")
            Integer delayMinutes,
            String lastEventId,
            Long expectedRevision
    ) {
    }

    public record ProgressView(
            String id,
            String planId,
            String currentItemId,
            List<String> completedItemIds,
            String status,
            Instant currentItemStartedAt,
            Instant actualEndedAt,
            int delayMinutes,
            String lastEventId,
            long revision,
            Instant updatedAt
    ) {
    }
}
