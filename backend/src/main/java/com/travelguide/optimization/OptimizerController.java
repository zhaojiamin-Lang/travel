package com.travelguide.optimization;

import com.travelguide.optimization.OptimizerModels.DayOptimizationResult;
import com.travelguide.optimization.OptimizerModels.OptimizeDayRequest;
import com.travelguide.service.AccessControlService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/optimizer")
public class OptimizerController {

    private final CpSatDayOptimizer optimizer;
    private final AccessControlService accessControl;

    public OptimizerController(
            CpSatDayOptimizer optimizer,
            AccessControlService accessControl
    ) {
        this.optimizer = optimizer;
        this.accessControl = accessControl;
    }

    @PostMapping("/plans/{planId}/days/{dayNumber}")
    public DayOptimizationResult optimizeDay(
            @PathVariable String planId,
            @PathVariable int dayNumber,
            @Valid @RequestBody OptimizeDayRequest request,
            HttpSession session
    ) {
        accessControl.requirePlan(session, planId);
        return optimizer.optimize(planId, dayNumber, request);
    }
}
