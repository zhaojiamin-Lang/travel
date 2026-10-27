package com.travelguide.service;

import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.RegisterRequest;
import com.travelguide.web.ApiDtos.SwapRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key="
})
class PlanningFlowTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private GuideService guideService;

    @Autowired
    private AuthService authService;

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

    @Test
    void supportsNaturalLanguageUpdateSwapAndRestore() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "test-versions",
                "2030年7月6日去上海玩两天，预算 3200 元，想去外滩，不想走太累。"
        ));
        PlanView first = planningService.generatePlan(draft.requestId());
        assertThat(first.version()).isEqualTo(1);

        String budgetCommand = planningService.updateFromNaturalLanguage(
                draft.requestId(),
                new NaturalLanguageUpdateRequest("预算改成 2800 元，第二天轻松一点")
        ).requestId();
        PlanView second = planningService.generatePlan(budgetCommand);
        assertThat(second.version()).isEqualTo(2);
        assertThat(second.budget()).isEqualByComparingTo("2800");

        PlanItem replaceable = second.dayPlans().get(0).items().stream()
                .filter(item -> "POI".equals(item.type()))
                .findFirst()
                .orElseThrow();
        var alternatives = planningService.alternatives(second.id(), replaceable.id());
        assertThat(alternatives).isNotEmpty();
        PlanView third = planningService.swapPoi(
                second.id(),
                new SwapRequest(replaceable.id(), alternatives.get(0).id())
        );
        assertThat(third.version()).isEqualTo(3);

        PlanView restored = planningService.restoreVersion(first.id());
        assertThat(restored.version()).isEqualTo(4);
        assertThat(planningService.listVersions(draft.requestId())).hasSize(4);
    }

    @Test
    void registersUserAndReturnsOwnedHistory() {
        MockHttpSession session = new MockHttpSession();
        var user = authService.register(
                session,
                new RegisterRequest(
                        "traveler-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                        "password123",
                        "旅行者"
                )
        );
        DraftResponse draft = planningService.createDraft(
                new DraftRequest("auth-test", "2030年8月1日去上海玩一天，预算 1000 元。"),
                user.id()
        );
        planningService.generatePlan(draft.requestId());

        var history = planningService.history(user.id());
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).latestPlanId()).isNotBlank();
    }
}
