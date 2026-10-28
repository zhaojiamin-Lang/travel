package com.travelguide.notifications;

import com.travelguide.weather.WeatherSchedulerService;
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

    public NotificationSseController(
            ReplanNotificationHub notificationHub,
            WeatherSchedulerService weatherService
    ) {
        this.notificationHub = notificationHub;
        this.weatherService = weatherService;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(required = false) String planId) {
        return notificationHub.subscribe(planId, weatherService.latest());
    }
}
