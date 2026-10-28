package com.travelguide.intelligence;

import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import com.travelguide.web.ApiDtos.PlanView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key=",
        "travel.weather.initial-delay-ms=3600000"
})
class PlanIntelligenceFlowTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private PlanDiffService diffService;

    @Autowired
    private PlanRollbackService rollbackService;

    @Autowired
    private PlanIntelligenceService intelligenceService;

    @Test
    void calculatesSemanticDiffAndRollsBackAsANewVersion() {
        var draft = planningService.createDraft(new DraftRequest(
                "diff-test",
                "2030年12月1日去上海玩两天，预算 3000 元，想去外滩，不想走太累。"
        ));
        PlanView first = planningService.generatePlan(draft.requestId());

        planningService.updateRequirements(draft.requestId(), new RequirementsUpdate(
                null, null, null, BigDecimal.valueOf(2500), "RELAXED", true,
                java.util.List.of("bund")
        ));
        PlanView second = planningService.generatePlan(draft.requestId());

        var diff = diffService.diff(first, second);
        assertThat(diff.identical()).isFalse();
        assertThat(diff.changes()).anyMatch(change -> "BUDGET_CHANGED".equals(change.type()));

        var intent = intelligenceService.interpret(second.id(), "预算改成 2200 元");
        assertThat(intent.intent()).isEqualTo("SET_BUDGET");
        assertThat(intent.parameters()).containsEntry("budget", 2200);

        var firstRollback = rollbackService.rollback(first.id());
        assertThat(firstRollback.createdNewVersion()).isTrue();
        assertThat(firstRollback.currentPlan().version()).isEqualTo(second.version() + 1);

        var repeatedRollback = rollbackService.rollback(first.id());
        assertThat(repeatedRollback.createdNewVersion()).isFalse();
    }

    @Test
    void explanationFallsBackWhenAiIsDisabled() {
        var draft = planningService.createDraft(new DraftRequest(
                "explain-test",
                "2030年12月8日去上海玩一天，预算 1800 元。"
        ));
        PlanView first = planningService.generatePlan(draft.requestId());
        var explanation = intelligenceService.explainDiff(first.id(), first.id());
        assertThat(explanation.source()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(explanation.explanation()).isNotBlank();
    }
}
