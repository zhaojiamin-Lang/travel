package com.travelguide.progress;

import com.travelguide.progress.ExecutionProgressModels.ProgressView;
import com.travelguide.progress.ExecutionProgressModels.SaveProgressRequest;
import jakarta.validation.Valid;
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

    public ExecutionProgressController(ExecutionProgressService progressService) {
        this.progressService = progressService;
    }

    @GetMapping("/{planId}")
    public ProgressView get(@PathVariable String planId) {
        return progressService.getOrCreate(planId);
    }

    @PutMapping("/{planId}")
    public ProgressView save(
            @PathVariable String planId,
            @Valid @RequestBody SaveProgressRequest request
    ) {
        return progressService.save(planId, request);
    }
}
