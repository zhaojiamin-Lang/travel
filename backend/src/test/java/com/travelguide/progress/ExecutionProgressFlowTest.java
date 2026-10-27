package com.travelguide.progress;

import com.travelguide.progress.ExecutionProgressModels.SaveProgressRequest;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key=",
        "travel.weather.initial-delay-ms=3600000"
})
class ExecutionProgressFlowTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private ExecutionProgressService progressService;

    @Test
    void persistsAndUpdatesExecutionProgress() {
        var draft = planningService.createDraft(new DraftRequest(
                "progress-test",
                "2030年10月5日去上海玩两天，预算 2600 元，想去外滩，不想走太累。"
        ));
        PlanView plan = planningService.generatePlan(draft.requestId());
        PlanItem first = plan.dayPlans().get(0).items().get(0);
        PlanItem second = plan.dayPlans().get(0).items().get(1);

        var initial = progressService.getOrCreate(plan.id());
        assertThat(initial.status()).isEqualTo("NOT_STARTED");

        var updated = progressService.save(plan.id(), new SaveProgressRequest(
                second.id(),
                List.of(first.id()),
                "IN_PROGRESS",
                Instant.now(),
                null,
                20,
                "event_test",
                initial.revision()
        ));

        assertThat(updated.currentItemId()).isEqualTo(second.id());
        assertThat(updated.completedItemIds()).containsExactly(first.id());
        assertThat(updated.delayMinutes()).isEqualTo(20);
        assertThat(progressService.getOrCreate(plan.id()).revision()).isEqualTo(updated.revision());
    }
}
