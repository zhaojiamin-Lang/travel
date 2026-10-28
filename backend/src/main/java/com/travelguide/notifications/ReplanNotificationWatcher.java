package com.travelguide.notifications;

import com.travelguide.notifications.NotificationModels.ProposalNotification;
import com.travelguide.replan.ReplanProposalEntity;
import com.travelguide.replan.ReplanProposalRepository;
import com.travelguide.weather.WeatherModels.WeatherSnapshot;
import com.travelguide.weather.WeatherSchedulerService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Detects durable weather and proposal changes and forwards them to SSE.
 *
 * <p>Watching persisted state avoids coupling notification delivery to the
 * existing weather and replan business services.</p>
 */
@Service
public class ReplanNotificationWatcher {

    private final WeatherSchedulerService weatherService;
    private final ReplanProposalRepository proposalRepository;
    private final ReplanNotificationHub notificationHub;

    private Instant lastWeatherSuccessAt;
    private Set<String> knownProposalIds = Set.of();

    public ReplanNotificationWatcher(
            WeatherSchedulerService weatherService,
            ReplanProposalRepository proposalRepository,
            ReplanNotificationHub notificationHub
    ) {
        this.weatherService = weatherService;
        this.proposalRepository = proposalRepository;
        this.notificationHub = notificationHub;
    }

    /**
     * Polls durable state rather than modifying the existing weather or replan
     * services. This keeps notification delivery independent from business logic.
     */
    @Scheduled(
            fixedDelayString = "${travel.notifications.poll-interval-ms:1000}",
            initialDelayString = "${travel.notifications.initial-delay-ms:2000}"
    )
    public synchronized void pollChanges() {
        WeatherSnapshot weather = weatherService.latest();
        if (weather != null
                && weather.lastSuccessAt() != null
                && !weather.lastSuccessAt().equals(lastWeatherSuccessAt)) {
            lastWeatherSuccessAt = weather.lastSuccessAt();
            notificationHub.broadcastWeather(weather);
        }

        var proposals = proposalRepository.findAll();
        if (knownProposalIds.isEmpty()) {
            knownProposalIds = new HashSet<>(proposals.stream().map(ReplanProposalEntity::getId).toList());
            return;
        }

        Set<String> currentIds = new HashSet<>();
        for (ReplanProposalEntity proposal : proposals) {
            currentIds.add(proposal.getId());
            if (!knownProposalIds.contains(proposal.getId()) && "PENDING".equals(proposal.getStatus())) {
                notificationHub.broadcastProposal(new ProposalNotification(
                        proposal.getId(),
                        proposal.getPlanId(),
                        proposal.getEventType(),
                        proposal.getSummary(),
                        proposal.getStatus(),
                        proposal.getCreatedAt()
                ));
            }
        }
        knownProposalIds = currentIds;
    }
}
