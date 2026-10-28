package com.travelguide.notifications;

import com.travelguide.notifications.NotificationModels.ProposalNotification;
import com.travelguide.weather.WeatherModels.WeatherSnapshot;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ReplanNotificationHub {

    private static final long EMITTER_TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> planEmitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String planId, WeatherSnapshot initialWeather) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        String channel = channel(planId);
        planEmitters.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> remove(channel, emitter));
        emitter.onTimeout(() -> remove(channel, emitter));
        emitter.onError(error -> remove(channel, emitter));

        if (initialWeather != null) {
            send(emitter, "weather", initialWeather);
        }
        send(emitter, "connected", Map.of(
                "planId", planId == null ? "" : planId,
                "at", Instant.now().toString()
        ));
        return emitter;
    }

    public void broadcastWeather(WeatherSnapshot snapshot) {
        sendToAll("weather", snapshot);
    }

    public void broadcastProposal(ProposalNotification proposal) {
        List<SseEmitter> global = planEmitters.getOrDefault("all", new CopyOnWriteArrayList<>());
        List<SseEmitter> specific = planEmitters.getOrDefault(channel(proposal.planId()), new CopyOnWriteArrayList<>());

        Set<SseEmitter> targets = ConcurrentHashMap.newKeySet();
        targets.addAll(global);
        targets.addAll(specific);
        targets.forEach(emitter -> send(emitter, "replan-proposal", proposal));
    }

    @Scheduled(fixedDelay = 15_000)
    public void heartbeat() {
        sendToAll("heartbeat", Map.of("at", Instant.now().toString()));
    }

    private void sendToAll(String eventName, Object payload) {
        planEmitters.values().forEach(emitters ->
                emitters.forEach(emitter -> send(emitter, eventName, payload))
        );
    }

    private void send(SseEmitter emitter, String eventName, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(payload));
        } catch (IOException | IllegalStateException exception) {
            removeEmitter(emitter);
        }
    }

    private void remove(String channel, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = planEmitters.get(channel);
        if (emitters != null) {
            emitters.remove(emitter);
            if (emitters.isEmpty()) {
                planEmitters.remove(channel, emitters);
            }
        }
    }

    private void removeEmitter(SseEmitter emitter) {
        planEmitters.values().forEach(emitters -> {
            emitters.remove(emitter);
        });
    }

    private String channel(String planId) {
        return planId == null || planId.isBlank() ? "all" : planId;
    }
}
