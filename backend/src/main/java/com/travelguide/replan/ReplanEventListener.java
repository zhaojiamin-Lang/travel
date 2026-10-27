package com.travelguide.replan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.domain.TripPlanEntity;
import com.travelguide.repo.TripPlanRepository;
import com.travelguide.replan.ReplanModels.ClosureReportedEvent;
import com.travelguide.replan.ReplanModels.DelayReportedEvent;
import com.travelguide.replan.ReplanModels.PlanChange;
import com.travelguide.replan.ReplanModels.ProposalStatus;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class ReplanEventListener {

    private final RemainingRouteReplanner replanner;
    private final com.travelguide.service.PlanningService planningService;
    private final TripPlanRepository planRepository;
    private final TravelEventRepository eventRepository;
    private final ReplanProposalRepository proposalRepository;
    private final ObjectMapper objectMapper;

    public ReplanEventListener(
            RemainingRouteReplanner replanner,
            com.travelguide.service.PlanningService planningService,
            TripPlanRepository planRepository,
            TravelEventRepository eventRepository,
            ReplanProposalRepository proposalRepository,
            ObjectMapper objectMapper
    ) {
        this.replanner = replanner;
        this.planningService = planningService;
        this.planRepository = planRepository;
        this.eventRepository = eventRepository;
        this.proposalRepository = proposalRepository;
        this.objectMapper = objectMapper;
    }

    @EventListener
    @Transactional
    public void onClosure(ClosureReportedEvent event) {
        PlanView base = planningService.getPlan(event.planId());
        RemainingRouteReplanner.Outcome outcome = replanner.rebuildForClosure(base, event.poiId());
        saveProposal(
                event.eventId(),
                event.planId(),
                ReplanModels.EventType.CLOSURE.name(),
                base,
                outcome,
                "景点闭馆，已生成跳过闭馆点并重算剩余路线的方案。"
        );
    }

    @EventListener
    @Transactional
    public void onDelay(DelayReportedEvent event) {
        PlanView base = planningService.getPlan(event.planId());
        RemainingRouteReplanner.Outcome outcome = replanner.rebuildForDelay(
                base, event.currentItemId(), event.delayMinutes()
        );
        saveProposal(
                event.eventId(),
                event.planId(),
                ReplanModels.EventType.DELAY.name(),
                base,
                outcome,
                "游客延误 " + event.delayMinutes() + " 分钟，已重新计算后续路线与时间。"
        );
    }

    private void saveProposal(
            String eventId,
            String planId,
            String eventType,
            PlanView base,
            RemainingRouteReplanner.Outcome outcome,
            String summary
    ) {
        try {
            ReplanProposalEntity proposal = new ReplanProposalEntity();
            proposal.setId("proposal_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            proposal.setEventId(eventId);
            proposal.setPlanId(planId);
            proposal.setEventType(eventType);
            proposal.setBaseVersion(base.version());
            proposal.setStatus(ProposalStatus.PENDING.name());
            proposal.setSummary(summary);
            proposal.setProposedPlanJson(objectMapper.writeValueAsString(outcome.proposedPlan()));
            proposal.setChangesJson(objectMapper.writeValueAsString(outcome.changes()));
            proposal.setCreatedAt(Instant.now());
            proposalRepository.save(proposal);

            TravelEventEntity event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "事件不存在"));
            event.setStatus("PROCESSED");
            eventRepository.save(event);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "重规划提案保存失败");
        }
    }

    public PlanView accept(String proposalId) {
        ReplanProposalEntity proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "重规划提案不存在"));
        if (!ProposalStatus.PENDING.name().equals(proposal.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该提案已经处理");
        }
        try {
            PlanView proposed = objectMapper.readValue(proposal.getProposedPlanJson(), PlanView.class);
            List<TripPlanEntity> versions = planRepository.findByRequestIdOrderByVersionDesc(proposed.requestId());
            int latestVersion = versions.isEmpty() ? 0 : versions.get(0).getVersion();
            if (latestVersion > proposal.getBaseVersion()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "行程已经更新，请基于最新版本重新生成提案");
            }
            int nextVersion = latestVersion + 1;
            PlanView accepted = new PlanView(
                    "plan_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                    proposed.requestId(),
                    nextVersion,
                    proposed.title(),
                    proposed.subtitle(),
                    proposed.city(),
                    proposed.startDate(),
                    proposed.days(),
                    proposed.people(),
                    proposed.budget(),
                    proposed.estimatedCost(),
                    proposed.pace(),
                    proposed.mustSee(),
                    proposed.dayPlans(),
                    proposed.issues(),
                    proposed.passed(),
                    proposed.mapPoints(),
                    "事件重规划 · " + proposal.getEventType()
            );
            TripPlanEntity entity = new TripPlanEntity();
            entity.setId(accepted.id());
            entity.setRequestId(accepted.requestId());
            entity.setVersion(accepted.version());
            entity.setPassed(accepted.passed());
            entity.setPlanJson(objectMapper.writeValueAsString(accepted));
            entity.setIssuesJson(objectMapper.writeValueAsString(accepted.issues()));
            entity.setCreatedAt(java.time.LocalDateTime.now());
            planRepository.save(entity);
            proposal.setStatus(ProposalStatus.ACCEPTED.name());
            proposalRepository.save(proposal);
            return accepted;
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "重规划提案读取失败");
        }
    }
}
