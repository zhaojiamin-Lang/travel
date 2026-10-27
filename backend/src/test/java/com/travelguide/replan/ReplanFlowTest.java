package com.travelguide.replan;

import com.travelguide.replan.ReplanModels.ClosureEventRequest;
import com.travelguide.replan.ReplanModels.DelayEventRequest;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key=",
        "travel.weather.initial-delay-ms=3600000"
})
class ReplanFlowTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private ReplanEventFacade replanEventFacade;

    @Test
    void closureEventCreatesAcceptablePlanVersion() {
        PlanView base = planFor("closure-test");
        PlanItem closed = base.dayPlans().get(0).items().stream()
                .filter(item -> "POI".equals(item.type()))
                .findFirst()
                .orElseThrow();

        var proposal = replanEventFacade.reportClosure(
                new ClosureEventRequest(base.id(), closed.poiId(), "临时闭馆", "TEST")
        );

        assertThat(proposal.status()).isEqualTo("PENDING");
        assertThat(proposal.changes()).isNotEmpty();
        assertThat(proposal.proposedPlan().mapPoints())
                .noneMatch(point -> point.poiId().equals(closed.poiId()));

        PlanView accepted = replanEventFacade.accept(proposal.id());
        assertThat(accepted.version()).isEqualTo(base.version() + 1);
        assertThat(accepted.generatedBy()).contains("CLOSURE");
    }

    @Test
    void delayEventReplansOnlyRemainingStops() {
        PlanView base = planFor("delay-test");
        PlanItem first = base.dayPlans().get(0).items().stream()
                .filter(item -> "POI".equals(item.type()))
                .findFirst()
                .orElseThrow();

        var proposal = replanEventFacade.reportDelay(
                new DelayEventRequest(base.id(), first.id(), 35, "路上耽误", "TEST")
        );

        assertThat(proposal.status()).isEqualTo("PENDING");
        assertThat(proposal.proposedPlan().dayPlans().get(0).items().get(0).id())
                .isEqualTo(first.id());
        assertThat(proposal.changes())
                .anyMatch(change -> "DELAY".equals(change.type()));
    }

    private PlanView planFor(String session) {
        var draft = planningService.createDraft(new DraftRequest(
                session,
                "2030年9月7日去上海玩两天，预算 3000 元，想去外滩，不想走太累。"
        ));
        return planningService.generatePlan(draft.requestId());
    }
}
