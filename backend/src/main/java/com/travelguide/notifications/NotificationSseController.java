package com.travelguide.notifications;

import com.travelguide.service.AccessControlService;
import com.travelguide.weather.WeatherSchedulerService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/notifications")
public class NotificationSseController {

    private final ReplanNotificationHub notificationHub;
    private final WeatherSchedulerService weatherService;
    private final AccessControlService accessControl;

    public NotificationSseController(
            ReplanNotificationHub notificationHub,
            WeatherSchedulerService weatherService,
            AccessControlService accessControl
    ) {
        this.notificationHub = notificationHub;
        this.weatherService = weatherService;
        this.accessControl = accessControl;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam(required = false) String planId,
            HttpSession session
    ) {
        accessControl.requireUser(session);
        if (planId != null && !planId.isBlank()) {
            accessControl.requirePlan(session, planId);
        }
        return notificationHub.subscribe(planId, weatherService.latest());
    }
}
