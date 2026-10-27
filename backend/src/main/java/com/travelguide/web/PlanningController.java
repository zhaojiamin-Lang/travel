package com.travelguide.web;

import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PlanningController {

    private final PlanningService planningService;

    public PlanningController(PlanningService planningService) {
        this.planningService = planningService;
    }

    @PostMapping("/trips/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request) {
        return planningService.createDraft(request);
    }

    @PatchMapping("/trips/{requestId}/requirements")
    public DraftResponse update(
            @PathVariable String requestId,
            @RequestBody RequirementsUpdate update
    ) {
        return planningService.updateRequirements(requestId, update);
    }

    @PostMapping("/trips/{requestId}/plans")
    public PlanView generate(@PathVariable String requestId) {
        return planningService.generatePlan(requestId);
    }

    @GetMapping("/plans/{planId}")
    public PlanView plan(@PathVariable String planId) {
        return planningService.getPlan(planId);
    }
}
