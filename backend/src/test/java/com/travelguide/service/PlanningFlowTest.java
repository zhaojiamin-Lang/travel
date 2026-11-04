package com.travelguide.service;

import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.RegisterRequest;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import com.travelguide.web.ApiDtos.SwapRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import com.travelguide.repo.PoiRepository;
import com.travelguide.rag.query.AnswerScopePolicy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @Autowired
    private PoiRepository poiRepository;

    @Autowired
    private GuideNarrativeComposer guideNarrativeComposer;

    @Test
    void generatesAValidatedPlanAndLoadsGuideContent() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "test-session",
                "2030年5月18日去上海玩两天，预算 3000 元，想去外滩和上海博物馆，不吃辣，不想走太累。"
        ));

        assertThat(draft.missingFields()).isEmpty();
        assertThat(draft.mustSee()).contains("bund", "shanghai-museum");

        PlanView plan = planningService.generatePlan(draft.requestId());
        assertThat(plan.passed())
                .withFailMessage("Plan issues: %s", plan.issues())
                .isTrue();
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

        GuideSessionView museumSession = guideService.create(new GuideCreateRequest(plan.id()));
        GuideSessionView museum = guideService.select(museumSession.id(), "shanghai-museum");
        assertThat(museum.currentPoi().narrative())
                .contains("各位朋友，上午好")
                .contains("世界树之巅")
                .contains("南京路");
        var shortMuseumGuide = guideService.ask(
                museumSession.id(),
                "给我一段更短的讲解。"
        );
        assertThat(shortMuseumGuide.answer())
                .contains("世界树之巅")
                .contains("1.5到2.5小时")
                .doesNotContain("城隍庙");

        var answer = guideService.ask(session.id(), "附近还能看什么？");
        assertThat(answer.answer()).isNotBlank();

        var grounded = guideService.ask(session.id(), "这里门票多少钱？");
        assertThat(grounded.answer()).isNotBlank();
        assertThat(grounded.citations()).isNotEmpty();
        assertThat(grounded.citations().get(0).evidenceId()).startsWith("E");
        assertThat(grounded.claims()).anyMatch(claim -> "CITED".equals(claim.support()));
        assertThat(grounded.retrieval()).isNotNull();
        assertThat(grounded.retrieval().indexedDocuments()).isPositive();

        var offTopic = guideService.ask(
                session.id(),
                "量子纠缠是什么？"
        );
        assertThat(offTopic.answer()).isEqualTo(AnswerScopePolicy.UNSUPPORTED_ANSWER);
        assertThat(offTopic.citations()).isEmpty();
        assertThat(offTopic.claims()).isEmpty();
    }

    @Test
    void reportsBudgetViolationInsteadOfMarkingPlanAsPassed() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "test-budget",
                "去上海玩一天，想吃点清淡的。"
        ));

        planningService.updateRequirements(draft.requestId(), new RequirementsUpdate(
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

    @Test
    void isolatesPlansAndGuideSessionsBetweenUsers() {
        MockHttpSession ownerSession = new MockHttpSession();
        MockHttpSession strangerSession = new MockHttpSession();
        var owner = authService.register(
                ownerSession,
                new RegisterRequest(
                        "owner-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                        "password123",
                        "行程所有者"
                )
        );
        var stranger = authService.register(
                strangerSession,
                new RegisterRequest(
                        "stranger-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com",
                        "password123",
                        "其他用户"
                )
        );
        DraftResponse draft = planningService.createDraft(
                new DraftRequest("owner-test", "2030年8月1日去上海玩一天，预算 1000 元。"),
                owner.id()
        );
        PlanView plan = planningService.generatePlan(draft.requestId(), owner.id());
        GuideSessionView guide = guideService.create(
                new GuideCreateRequest(plan.id()),
                owner.id()
        );

        assertThatThrownBy(() -> planningService.getPlan(plan.id(), stranger.id()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN)
                );
        assertThatThrownBy(() -> guideService.get(guide.id(), stranger.id()))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        exception -> assertThat(exception.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN)
                );
    }

    @Test
    void usesNaturalLanguageInterestsInsteadOfAFixedRoute() {
        var architecture = planningService.createDraft(new DraftRequest(
                "interest-architecture",
                "2030年4月6日去上海玩一天，预算 1600 元，喜欢建筑和梧桐街区，想拍照。"
        ));
        var nature = planningService.createDraft(new DraftRequest(
                "interest-nature",
                "2030年4月7日去上海玩一天，预算 1600 元，喜欢自然、公园和亲子活动。"
        ));

        assertThat(architecture.interestTags()).contains("建筑", "街区");
        assertThat(nature.interestTags()).contains("公园", "亲子");

        PlanView architecturePlan = planningService.generatePlan(architecture.requestId());
        PlanView naturePlan = planningService.generatePlan(nature.requestId());
        var architectureNames = architecturePlan.mapPoints().stream().map(point -> point.name()).toList();
        var natureNames = naturePlan.mapPoints().stream().map(point -> point.name()).toList();

        assertThat(architectureNames).isNotEqualTo(natureNames);
        assertThat(architectureNames).anyMatch(name ->
                name.contains("武康") || name.contains("思南") || name.contains("张园")
        );
        assertThat(natureNames).anyMatch(name ->
                name.contains("自然") || name.contains("公园") || name.contains("迪士尼")
        );
    }

    @Test
    void excludesExplicitlyRejectedPlaces() {
        var draft = planningService.createDraft(new DraftRequest(
                "exclude-test",
                "2030年4月8日去上海玩一天，预算 1600 元，想去外滩，但不去上海博物馆。"
        ));
        assertThat(draft.mustSee()).contains("bund").doesNotContain("shanghai-museum");
        assertThat(draft.excludedPoiIds()).contains("shanghai-museum");
        assertThat(planningService.generatePlan(draft.requestId()).mapPoints())
                .noneMatch(point -> point.poiId().equals("shanghai-museum"));
    }

    @Test
    void unsupportedDestinationKeepsSlotFormOpenUntilUserChoosesAvailableCity() {
        DraftResponse draft = planningService.createDraft(new DraftRequest(
                "unsupported-city",
                "我想去山西玩"
        ));
        assertThat(draft.city()).isNull();
        assertThat(draft.missingFields()).contains("city", "startDate", "days", "budget");

        DraftResponse completed = planningService.updateRequirements(
                draft.requestId(),
                new RequirementsUpdate(
                        "上海",
                        LocalDate.of(2031, 5, 1),
                        1,
                        1,
                        BigDecimal.valueOf(1200),
                        "RELAXED",
                        true,
                        null
                )
        );
        assertThat(completed.city()).isEqualTo("上海");
        assertThat(planningService.generatePlan(completed.requestId()).passed()).isTrue();
    }

    @Test
    void everyPoiUsesTheSameStructuredGuideNarrative() {
        var pois = poiRepository.findAll();
        assertThat(pois).hasSizeGreaterThanOrEqualTo(32);

        for (var poi : pois) {
            assertThat(guideNarrativeComposer.compose(poi))
                    .as("景点 %s 的讲解", poi.getName())
                    .startsWith("各位朋友")
                    .contains("我是今天的讲解员")
                    .contains("建议")
                    .contains(poi.getName());
        }
    }
}
