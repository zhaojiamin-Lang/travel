package com.travelguide.intelligence;

import com.travelguide.web.ApiDtos.PlanView;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class PlanDiffModels {

    private PlanDiffModels() {
    }

    public record SemanticChange(
            String type,
            int dayNumber,
            String itemId,
            String before,
            String after,
            String description
    ) {
    }

    public record DiffSummary(
            int additions,
            int removals,
            int replacements,
            int timeChanges,
            int otherChanges,
            BigDecimal costDelta
    ) {
    }

    public record PlanDiffView(
            String fromPlanId,
            String toPlanId,
            int fromVersion,
            int toVersion,
            boolean identical,
            List<SemanticChange> changes,
            DiffSummary summary
    ) {
    }

    public record RollbackResult(
            boolean createdNewVersion,
            PlanView currentPlan,
            PlanDiffView appliedChanges
    ) {
    }

    public record IntentRequest(
            @NotBlank(message = "请输入要理解的旅行指令") String message
    ) {
    }

    public record IntentView(
            String intent,
            double confidence,
            boolean needsConfirmation,
            Map<String, Object> parameters,
            String note
    ) {
    }

    public record ExplanationView(
            String explanation,
            String source,
            PlanDiffView diff
    ) {
    }
}
