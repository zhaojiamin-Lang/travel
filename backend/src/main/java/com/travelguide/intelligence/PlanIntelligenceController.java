package com.travelguide.intelligence;

import com.travelguide.intelligence.PlanDiffModels.ExplanationView;
import com.travelguide.intelligence.PlanDiffModels.IntentRequest;
import com.travelguide.intelligence.PlanDiffModels.IntentView;
import com.travelguide.intelligence.PlanDiffModels.PlanDiffView;
import com.travelguide.intelligence.PlanDiffModels.RollbackResult;
import com.travelguide.service.AccessControlService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/plan-intelligence")
public class PlanIntelligenceController {

    private final PlanDiffService diffService;
    private final PlanRollbackService rollbackService;
    private final PlanIntelligenceService intelligenceService;
    private final com.travelguide.service.PlanningService planningService;
    private final AccessControlService accessControl;

    public PlanIntelligenceController(
            PlanDiffService diffService,
            PlanRollbackService rollbackService,
            PlanIntelligenceService intelligenceService,
            com.travelguide.service.PlanningService planningService,
            AccessControlService accessControl
    ) {
        this.diffService = diffService;
        this.rollbackService = rollbackService;
        this.intelligenceService = intelligenceService;
        this.planningService = planningService;
        this.accessControl = accessControl;
    }

    @GetMapping("/plans/{planId}/diff")
    public PlanDiffView diff(
            @PathVariable String planId,
            @RequestParam String against,
            HttpSession session
    ) {
        accessControl.requirePlan(session, planId);
        accessControl.requirePlan(session, against);
        return diffService.diff(
                diffPlan(against),
                diffPlan(planId)
        );
    }

    @PostMapping("/plans/{planId}/rollback")
    public RollbackResult rollback(
            @PathVariable String planId,
            HttpSession session
    ) {
        accessControl.requirePlan(session, planId);
        return rollbackService.rollback(planId);
    }

    @PostMapping("/plans/{planId}/interpret")
    public IntentView interpret(
            @PathVariable String planId,
            @Valid @RequestBody IntentRequest request,
            HttpSession session
    ) {
        accessControl.requirePlan(session, planId);
        return intelligenceService.interpret(planId, request.message());
    }

    @PostMapping("/plans/{planId}/diff/{againstPlanId}/explain")
    public ExplanationView explain(
            @PathVariable String planId,
            @PathVariable String againstPlanId,
            HttpSession session
    ) {
        accessControl.requirePlan(session, planId);
        accessControl.requirePlan(session, againstPlanId);
        return intelligenceService.explainDiff(planId, againstPlanId);
    }

    private com.travelguide.web.ApiDtos.PlanView diffPlan(String planId) {
        return planningService.getPlan(planId);
    }
}
