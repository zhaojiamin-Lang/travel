package com.travelguide.progress;

import com.travelguide.progress.ExecutionProgressModels.ProgressView;
import com.travelguide.progress.ExecutionProgressModels.SaveProgressRequest;
import com.travelguide.service.AuthService;
import com.travelguide.service.PlanningService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/progress")
public class ExecutionProgressController {

    private final ExecutionProgressService progressService;
    private final PlanningService planningService;
    private final AuthService authService;

    public ExecutionProgressController(
            ExecutionProgressService progressService,
            PlanningService planningService,
            AuthService authService
    ) {
        this.progressService = progressService;
        this.planningService = planningService;
        this.authService = authService;
    }

    @GetMapping("/{planId}")
    public ProgressView get(
            @PathVariable String planId,
            HttpSession session
    ) {
        requirePlan(planId, session);
        return progressService.getOrCreate(planId);
    }

    @PutMapping("/{planId}")
    public ProgressView save(
            @PathVariable String planId,
            @Valid @RequestBody SaveProgressRequest request,
            HttpSession session
    ) {
        requirePlan(planId, session);
        return progressService.save(planId, request);
    }

    private void requirePlan(String planId, HttpSession session) {
        planningService.getPlan(
                planId,
                authService.ensureUser(session).id()
        );
    }
}
