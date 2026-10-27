package com.travelguide.service;

import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class PlanningFlowTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private GuideService guideService;

    @Test
    void generatesAValidatedPlanAndLoadsGuideContent() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "test-session",
                "2030年5月18日去上海玩两天，预算 3000 元，想去外滩和上海博物馆，不吃辣，不想走太累。"
        ));

        assertThat(draft.missingFields()).isEmpty();
        assertThat(draft.mustSee()).contains("bund", "shanghai-museum");

        PlanView plan = planningService.generatePlan(draft.requestId());
        assertThat(plan.passed()).isTrue();
        assertThat(plan.dayPlans()).hasSize(2);
        assertThat(plan.mapPoints()).isNotEmpty();

        for (var day : plan.dayPlans()) {
            LocalTime previousEnd = LocalTime.MIN;
            for (PlanItem item : day.items()) {
                LocalTime start = LocalTime.parse(item.startTime());
                LocalTime end = LocalTime.parse(item.endTime());
                assertThat(start).isAfterOrEqualTo(previousEnd);
                assertThat(end).isAfter(start);
                previousEnd = end;
            }
        }

        GuideSessionView session = guideService.create(new GuideCreateRequest(plan.id()));
        GuideSessionView selected = guideService.select(session.id(), "bund");
        assertThat(selected.currentPoi()).isNotNull();
        assertThat(selected.currentPoi().name()).isEqualTo("外滩");

        var answer = guideService.ask(session.id(), "附近还能看什么？");
        assertThat(answer.answer()).isNotBlank();
    }

    @Test
    void reportsBudgetViolationInsteadOfMarkingPlanAsPassed() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "test-budget",
                "去上海玩一天，想吃点清淡的。"
        ));

        planningService.updateRequirements(draft.requestId(), new com.travelguide.web.ApiDtos.RequirementsUpdate(
                LocalDate.of(2030, 6, 1),
                1,
                1,
                BigDecimal.valueOf(20),
                "RELAXED",
                true,
                null
        ));

        PlanView plan = planningService.generatePlan(draft.requestId());
        assertThat(plan.passed()).isFalse();
        assertThat(plan.issues()).anyMatch(issue -> "BUDGET_EXCEEDED".equals(issue.code()));
    }
}
