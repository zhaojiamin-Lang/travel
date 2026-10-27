package com.travelguide.replan;

import com.travelguide.web.ApiDtos.PlanView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.List;

public final class ReplanModels {

    private ReplanModels() {
    }

    public enum EventType {
        CLOSURE,
        DELAY
    }

    public enum ProposalStatus {
        PENDING,
        ACCEPTED,
        REJECTED
    }

    public record ClosureEventRequest(
            @NotBlank(message = "缺少行程 ID") String planId,
            @NotBlank(message = "缺少闭馆景点 ID") String poiId,
            String reason,
            String source
    ) {
    }

    public record DelayEventRequest(
            @NotBlank(message = "缺少行程 ID") String planId,
            @NotBlank(message = "缺少当前行程项 ID") String currentItemId,
            @Min(value = 1, message = "延误时间至少 1 分钟")
            @Max(value = 720, message = "单次延误不能超过 720 分钟")
            int delayMinutes,
            String reason,
            String source
    ) {
    }

    public record ClosureReportedEvent(
            String eventId,
            String planId,
            String poiId,
            String reason,
            String source,
            Instant occurredAt
    ) {
    }

    public record DelayReportedEvent(
            String eventId,
            String planId,
            String currentItemId,
            int delayMinutes,
            String reason,
            String source,
            Instant occurredAt
    ) {
    }

    public record PlanChange(
            String type,
            String itemId,
            String poiId,
            String description
    ) {
    }

    public record ReplanProposalView(
            String id,
            String eventId,
            String planId,
            String eventType,
            int baseVersion,
            String status,
            String summary,
            List<PlanChange> changes,
            PlanView proposedPlan,
            Instant createdAt
    ) {
    }
}
