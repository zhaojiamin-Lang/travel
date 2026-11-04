package com.travelguide.service;

import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Service;

/**
 * 统一从服务端会话取得身份，并校验行程和需求所有权。
 */
@Service
public class AccessControlService {
    private final AuthService authService;
    private final PlanningService planningService;

    public AccessControlService(
            AuthService authService,
            PlanningService planningService
    ) {
        this.authService = authService;
        this.planningService = planningService;
    }

    public String requireUser(HttpSession session) {
        return authService.ensureUser(session).id();
    }

    public String requirePlan(HttpSession session, String planId) {
        String userId = requireUser(session);
        planningService.requirePlanOwner(planId, userId);
        return userId;
    }

    public String requireRequest(HttpSession session, String requestId) {
        String userId = requireUser(session);
        planningService.requireRequestOwner(requestId, userId);
        return userId;
    }
}
