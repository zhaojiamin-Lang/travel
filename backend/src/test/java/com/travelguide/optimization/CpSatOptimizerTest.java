package com.travelguide.optimization;

import com.travelguide.optimization.OptimizerModels.OptimizeDayRequest;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key=",
        "travel.weather.initial-delay-ms=3600000"
})
class CpSatOptimizerTest {

    @Autowired
    private PlanningService planningService;

    @Autowired
    private CpSatDayOptimizer optimizer;

    @Test
    void producesAFeasibleOptimizedDayWithoutChangingTheBasePlan() {
        var draft = planningService.createDraft(new DraftRequest(
                "cpsat-test",
                "2030年11月2日去上海玩两天，预算 3000 元，想去外滩，不想走太累。"
        ));
        PlanView base = planningService.generatePlan(draft.requestId());
        PlanItem first = base.dayPlans().get(0).items().stream()
                .filter(item -> "POI".equals(item.type()))
                .findFirst()
                .orElseThrow();

        var result = optimizer.optimize(base.id(), 1, new OptimizeDayRequest(
                20,
                List.of(first.id()),
                List.of(),
                3.0
        ));

        assertThat(result.solver()).isEqualTo("OR-Tools CP-SAT");
        assertThat(result.status()).isIn("OPTIMAL", "FEASIBLE");
        assertThat(result.stops()).isNotEmpty();
        assertThat(result.stops().get(0).itemId()).isEqualTo(first.id());
        assertThat(planningService.getPlan(base.id()).version()).isEqualTo(base.version());
    }
}
