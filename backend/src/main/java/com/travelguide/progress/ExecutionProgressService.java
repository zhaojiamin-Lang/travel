package com.travelguide.progress;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.progress.ExecutionProgressModels.ProgressView;
import com.travelguide.progress.ExecutionProgressModels.SaveProgressRequest;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 持久化用户当前的行程执行进度。
 *
 * <p>每个行程对应一条进度记录，并通过乐观锁版本号
 * 防止并发更新互相覆盖。</p>
 */
@Service
public class ExecutionProgressService {

    private final ExecutionProgressRepository repository;
    private final PlanningService planningService;
    private final ObjectMapper objectMapper;

    public ExecutionProgressService(
            ExecutionProgressRepository repository,
            PlanningService planningService,
            ObjectMapper objectMapper
    ) {
        this.repository = repository;
        this.planningService = planningService;
        this.objectMapper = objectMapper;
    }

    /**
     * 当用户尚未上报进度时，创建初始的“未开始”记录。
     */
    @Transactional
    public ProgressView getOrCreate(String planId) {
        ExecutionProgressEntity entity = repository.findByPlanId(planId).orElse(null);
        if (entity == null) {
            PlanView plan = planningService.getPlan(planId);
            entity = new ExecutionProgressEntity();
            entity.setId("progress_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            entity.setPlanId(plan.id());
            entity.setStatus("NOT_STARTED");
            entity.setCompletedItemIdsJson("[]");
            entity.setDelayMinutes(0);
            entity.setUpdatedAt(Instant.now());
            entity = repository.saveAndFlush(entity);
        }
        return toView(entity);
    }

    /**
     * 先校验行程项是否属于当前计划，再持久化最新执行进度。
     */
    @Transactional
    public ProgressView save(String planId, SaveProgressRequest request) {
        PlanView plan = planningService.getPlan(planId);
        Set<String> validItems = new LinkedHashSet<>();
        plan.dayPlans().stream()
                .flatMap(day -> day.items().stream())
                .map(PlanItem::id)
                .forEach(validItems::add);

        if (request.currentItemId() != null
                && !request.currentItemId().isBlank()
                && !validItems.contains(request.currentItemId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前行程项不属于该行程");
        }
        List<String> completed = request.completedItemIds() == null
                ? List.of()
                : request.completedItemIds().stream().distinct().toList();
        if (!validItems.containsAll(completed)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "完成项中包含不属于该行程的项目");
        }

        ExecutionProgressEntity entity = repository.findByPlanId(planId).orElseGet(() -> {
            ExecutionProgressEntity created = new ExecutionProgressEntity();
            created.setId("progress_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            created.setPlanId(planId);
            created.setStatus("NOT_STARTED");
            created.setCompletedItemIdsJson("[]");
            created.setDelayMinutes(0);
            return created;
        });

        if (request.expectedRevision() != null
                && entity.getRevision() != null
                && !request.expectedRevision().equals(entity.getRevision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "执行进度已被其他操作更新，请刷新后重试");
        }

        entity.setCurrentItemId(blankToNull(request.currentItemId()));
        entity.setCompletedItemIdsJson(writeJson(completed));
        entity.setStatus(request.status() == null ? "IN_PROGRESS" : request.status());
        entity.setCurrentItemStartedAt(request.currentItemStartedAt());
        entity.setActualEndedAt(request.actualEndedAt());
        entity.setDelayMinutes(request.delayMinutes() == null ? 0 : request.delayMinutes());
        entity.setLastEventId(blankToNull(request.lastEventId()));
        entity.setUpdatedAt(Instant.now());
        return toView(repository.saveAndFlush(entity));
    }

    /**
     * 自动记录事件驱动重规划发布的迟到信息。
     */
    @Transactional
    public void recordDelayEvent(com.travelguide.replan.ReplanModels.DelayReportedEvent event) {
        ExecutionProgressEntity entity = repository.findByPlanId(event.planId()).orElseGet(() -> {
            ExecutionProgressEntity created = new ExecutionProgressEntity();
            created.setId("progress_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            created.setPlanId(event.planId());
            created.setCompletedItemIdsJson("[]");
            created.setStatus("IN_PROGRESS");
            created.setDelayMinutes(0);
            return created;
        });
        entity.setCurrentItemId(event.currentItemId());
        entity.setDelayMinutes(event.delayMinutes());
        entity.setLastEventId(event.eventId());
        entity.setStatus("IN_PROGRESS");
        entity.setCurrentItemStartedAt(entity.getCurrentItemStartedAt() == null ? event.occurredAt() : entity.getCurrentItemStartedAt());
        entity.setUpdatedAt(Instant.now());
        repository.save(entity);
    }

    public ProgressView toView(ExecutionProgressEntity entity) {
        List<String> completed;
        try {
            completed = objectMapper.readValue(
                    entity.getCompletedItemIdsJson(),
                    new TypeReference<>() {
                    }
            );
        } catch (JsonProcessingException exception) {
            completed = List.of();
        }
        return new ProgressView(
                entity.getId(),
                entity.getPlanId(),
                entity.getCurrentItemId(),
                completed == null ? new ArrayList<>() : completed,
                entity.getStatus(),
                entity.getCurrentItemStartedAt(),
                entity.getActualEndedAt(),
                entity.getDelayMinutes() == null ? 0 : entity.getDelayMinutes(),
                entity.getLastEventId(),
                entity.getRevision() == null ? 0 : entity.getRevision(),
                entity.getUpdatedAt()
        );
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "执行进度保存失败");
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
