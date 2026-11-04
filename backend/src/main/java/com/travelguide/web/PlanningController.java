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
            @Valid @RequestBody NaturalLanguageUpdateRequest request,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.updateFromNaturalLanguage(
                requestId,
                request,
                user.id()
        );
    }

    @PatchMapping("/trips/{requestId}/requirements")
    public DraftResponse update(
            @PathVariable String requestId,
            @RequestBody RequirementsUpdate update,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.updateRequirements(requestId, update, user.id());
    }

    @PostMapping("/trips/{requestId}/plans")
    public PlanView generate(
            @PathVariable String requestId,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.generatePlan(requestId, user.id());
    }

    @GetMapping("/trips/{requestId}/plans")
    public List<PlanView> versions(
            @PathVariable String requestId,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.listVersions(requestId, user.id());
    }

    @GetMapping("/plans/{planId}")
    public PlanView plan(@PathVariable String planId, HttpSession session) {
        var user = authService.ensureUser(session);
        return planningService.getPlan(planId, user.id());
    }

    @GetMapping("/plans/{planId}/items/{itemId}/alternatives")
    public List<PoiView> alternatives(
            @PathVariable String planId,
            @PathVariable String itemId,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.alternatives(planId, itemId, user.id());
    }

    @PostMapping("/plans/{planId}/swap")
    public PlanView swap(
            @PathVariable String planId,
            @Valid @RequestBody SwapRequest request,
            HttpSession session
    ) {
        var user = authService.ensureUser(session);
        return planningService.swapPoi(planId, request, user.id());
    }

    @PostMapping("/plans/{planId}/restore")
    public PlanView restore(@PathVariable String planId, HttpSession session) {
        var user = authService.ensureUser(session);
        return planningService.restoreVersion(planId, user.id());
    }

    @GetMapping("/history")
    public List<HistoryItem> history(HttpSession session) {
        return planningService.history(authService.ensureUser(session).id());
    }
}
