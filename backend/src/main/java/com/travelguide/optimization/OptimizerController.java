package com.travelguide.optimization;

import com.travelguide.optimization.OptimizerModels.DayOptimizationResult;
import com.travelguide.optimization.OptimizerModels.OptimizeDayRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/optimizer")
public class OptimizerController {

    private final CpSatDayOptimizer optimizer;

    public OptimizerController(CpSatDayOptimizer optimizer) {
        this.optimizer = optimizer;
    }

    @PostMapping("/plans/{planId}/days/{dayNumber}")
    public DayOptimizationResult optimizeDay(
            @PathVariable String planId,
            @PathVariable int dayNumber,
            @Valid @RequestBody OptimizeDayRequest request
    ) {
        return optimizer.optimize(planId, dayNumber, request);
    }
}
