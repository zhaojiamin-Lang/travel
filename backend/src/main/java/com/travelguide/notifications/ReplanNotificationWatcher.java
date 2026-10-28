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
 * 检测已持久化的天气与提案变化，并转发到 SSE 通知中心。
 *
 * <p>通过监听数据库状态实现通知，避免把消息推送逻辑耦合进
 * 原有天气和重规划业务服务。</p>
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
     * 轮询已持久化状态，而不是修改现有天气或重规划服务，
     * 从而让消息推送与核心业务逻辑保持解耦。
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
