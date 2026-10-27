package com.travelguide.replan;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.replan.ReplanModels.ClosureEventRequest;
import com.travelguide.replan.ReplanModels.ClosureReportedEvent;
import com.travelguide.replan.ReplanModels.DelayEventRequest;
import com.travelguide.replan.ReplanModels.DelayReportedEvent;
import com.travelguide.replan.ReplanModels.PlanChange;
import com.travelguide.replan.ReplanModels.ProposalStatus;
import com.travelguide.replan.ReplanModels.ReplanProposalView;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ReplanEventFacade {

    private final ApplicationEventPublisher eventPublisher;
    private final TravelEventRepository eventRepository;
    private final ReplanProposalRepository proposalRepository;
    private final ReplanEventListener listener;
    private final ObjectMapper objectMapper;

    public ReplanEventFacade(
            ApplicationEventPublisher eventPublisher,
            TravelEventRepository eventRepository,
            ReplanProposalRepository proposalRepository,
            ReplanEventListener listener,
            ObjectMapper objectMapper
    ) {
        this.eventPublisher = eventPublisher;
        this.eventRepository = eventRepository;
        this.proposalRepository = proposalRepository;
        this.listener = listener;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ReplanProposalView reportClosure(ClosureEventRequest request) {
        Instant now = Instant.now();
        TravelEventEntity event = createEvent(
                request.planId(),
                ReplanModels.EventType.CLOSURE.name(),
                request.poiId(),
                null,
                null,
                request.reason(),
                request.source(),
                now
        );
        eventPublisher.publishEvent(new ClosureReportedEvent(
                event.getId(),
                request.planId(),
                request.poiId(),
                request.reason(),
                source(request.source()),
                now
        ));
        return findByEventId(event.getId());
    }

    @Transactional
    public ReplanProposalView reportDelay(DelayEventRequest request) {
        Instant now = Instant.now();
        TravelEventEntity event = createEvent(
                request.planId(),
                ReplanModels.EventType.DELAY.name(),
                null,
                request.currentItemId(),
                request.delayMinutes(),
                request.reason(),
                request.source(),
                now
        );
        eventPublisher.publishEvent(new DelayReportedEvent(
                event.getId(),
                request.planId(),
                request.currentItemId(),
                request.delayMinutes(),
                request.reason(),
                source(request.source()),
                now
        ));
        return findByEventId(event.getId());
    }

    public ReplanProposalView get(String proposalId) {
        return toView(proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "重规划提案不存在")));
    }

    @Transactional
    public PlanView accept(String proposalId) {
        return listener.accept(proposalId);
    }

    private TravelEventEntity createEvent(
            String planId,
            String eventType,
            String poiId,
            String currentItemId,
            Integer delayMinutes,
            String reason,
            String source,
            Instant occurredAt
    ) {
        TravelEventEntity event = new TravelEventEntity();
        event.setId("event_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        event.setPlanId(planId);
        event.setEventType(eventType);
        event.setPoiId(poiId);
        event.setCurrentItemId(currentItemId);
        event.setDelayMinutes(delayMinutes);
        event.setReason(reason);
        event.setSource(source(source));
        event.setStatus("RECEIVED");
        event.setOccurredAt(occurredAt);
        return eventRepository.save(event);
    }

    private ReplanProposalView findByEventId(String eventId) {
        return toView(proposalRepository.findByEventId(eventId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "事件已接收，但未能生成重规划提案"
                )));
    }

    private ReplanProposalView toView(ReplanProposalEntity entity) {
        try {
            PlanView plan = objectMapper.readValue(entity.getProposedPlanJson(), PlanView.class);
            List<PlanChange> changes = objectMapper.readValue(
                    entity.getChangesJson(),
                    new TypeReference<>() {
                    }
            );
            return new ReplanProposalView(
                    entity.getId(),
                    entity.getEventId(),
                    entity.getPlanId(),
                    entity.getEventType(),
                    entity.getBaseVersion(),
                    entity.getStatus(),
                    entity.getSummary(),
                    changes,
                    plan,
                    entity.getCreatedAt()
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "重规划提案读取失败");
        }
    }

    private String source(String source) {
        return source == null || source.isBlank() ? "USER_REPORT" : source;
    }
}
