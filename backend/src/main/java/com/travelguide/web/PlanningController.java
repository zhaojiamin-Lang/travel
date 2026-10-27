package com.travelguide.web;

import com.travelguide.service.PlanningService;
import com.travelguide.service.AuthService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.HistoryItem;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import com.travelguide.web.ApiDtos.PlanView;
import com.travelguide.web.ApiDtos.PoiView;
import com.travelguide.web.ApiDtos.RequirementsUpdate;
import com.travelguide.web.ApiDtos.SwapRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PlanningController {

    private final PlanningService planningService;
    private final AuthService authService;

    public PlanningController(PlanningService planningService, AuthService authService) {
        this.planningService = planningService;
        this.authService = authService;
    }

    @PostMapping("/trips/draft")
    public DraftResponse draft(@Valid @RequestBody DraftRequest request, HttpSession session) {
        var user = authService.ensureUser(session);
        return planningService.createDraft(request, user.id());
    }

    @PostMapping("/trips/{requestId}/messages")
    public DraftResponse updateFromNaturalLanguage(
            @PathVariable String requestId,
            @Valid @RequestBody NaturalLanguageUpdateRequest request
    ) {
        return planningService.updateFromNaturalLanguage(requestId, request);
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

    @GetMapping("/trips/{requestId}/plans")
    public List<PlanView> versions(@PathVariable String requestId) {
        return planningService.listVersions(requestId);
    }

    @GetMapping("/plans/{planId}")
    public PlanView plan(@PathVariable String planId) {
        return planningService.getPlan(planId);
    }

    @GetMapping("/plans/{planId}/items/{itemId}/alternatives")
    public List<PoiView> alternatives(@PathVariable String planId, @PathVariable String itemId) {
        return planningService.alternatives(planId, itemId);
    }

    @PostMapping("/plans/{planId}/swap")
    public PlanView swap(@PathVariable String planId, @Valid @RequestBody SwapRequest request) {
        return planningService.swapPoi(planId, request);
    }

    @PostMapping("/plans/{planId}/restore")
    public PlanView restore(@PathVariable String planId) {
        return planningService.restoreVersion(planId);
    }

    @GetMapping("/history")
    public List<HistoryItem> history(HttpSession session) {
        var user = authService.current(session);
        return planningService.history(user == null ? null : user.id());
    }
}
