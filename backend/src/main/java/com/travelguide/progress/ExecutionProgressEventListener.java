package com.travelguide.progress;

import com.travelguide.replan.ReplanModels.DelayReportedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ExecutionProgressEventListener {

    private final ExecutionProgressService progressService;

    public ExecutionProgressEventListener(ExecutionProgressService progressService) {
        this.progressService = progressService;
    }

    @EventListener
    @Transactional
    public void onDelay(DelayReportedEvent event) {
        progressService.recordDelayEvent(event);
    }
}
