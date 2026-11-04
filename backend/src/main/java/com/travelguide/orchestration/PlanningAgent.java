package com.travelguide.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.harness.AgentHarness;
import com.travelguide.harness.ContextItem;
import com.travelguide.harness.ContextKind;
import com.travelguide.harness.ExecutionPolicy;
import com.travelguide.harness.HarnessResult;
import com.travelguide.harness.HarnessRunRequest;
import com.travelguide.harness.HarnessConflictException;
import com.travelguide.harness.RetryableStepException;
import com.travelguide.harness.RunCheckpoint;
import com.travelguide.harness.RunState;
import com.travelguide.harness.StepContext;
import com.travelguide.harness.StepRejectedException;
import com.travelguide.harness.StepResult;
import com.travelguide.harness.WorkflowDefinition;
import com.travelguide.harness.WorkflowStep;
import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentType;
import com.travelguide.orchestration.OrchestrationModels.PlanningResult;
import com.travelguide.orchestration.OrchestrationModels.RunStatus;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import com.travelguide.web.ApiDtos.PlanIssue;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 行程规划 Agent。
 *
 * <p>需求补全与确定性规划分别作为 Harness 步骤执行，检查点落库后可按 runId 恢复。
 * 预算、排程和约束校验仍由 PlanningService 的确定性逻辑完成。</p>
 */
@Service
public class PlanningAgent implements Agent {
    private static final String DRAFT_CONTEXT = "planning-draft";
    private static final String PLAN_CONTEXT = "planning-plan";
    private static final String OWNER_CONTEXT = "owner-user";

    private final Tool<PlanningCreateDraftTool.Input, DraftResponse> createDraftTool;
    private final Tool<PlanningUpdateDraftTool.Input, DraftResponse> updateDraftTool;
    private final Tool<PlanningGeneratePlanTool.Input, PlanView> generatePlanTool;
    private final Tool<PlanningGetPlanTool.Input, PlanView> getPlanTool;
    private final PreferenceAgent preferenceAgent;
    private final AgentHarness harness;
    private final ObjectMapper objectMapper;

    public PlanningAgent(
            ToolRegistry toolRegistry,
            PreferenceAgent preferenceAgent,
            AgentHarness harness,
            ObjectMapper objectMapper
    ) {
        this.createDraftTool = toolRegistry.get(PlanningCreateDraftTool.NAME);
        this.updateDraftTool = toolRegistry.get(PlanningUpdateDraftTool.NAME);
        this.generatePlanTool = toolRegistry.get(PlanningGeneratePlanTool.NAME);
        this.getPlanTool = toolRegistry.get(PlanningGetPlanTool.NAME);
        this.preferenceAgent = preferenceAgent;
        this.harness = harness;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentType type() {
        return AgentType.PLANNING;
    }

    @Override
    public PlanningResult execute(AgentInvocation invocation) {
        return run(invocation, false);
    }

    public PlanningResult resume(AgentInvocation invocation) {
        return run(invocation, true);
    }

    public RunStatus status(String runId, String userId) {
        RunCheckpoint checkpoint = requireOwner(runId, userId);
        return new RunStatus(
                checkpoint.runId(),
                checkpoint.workflowId(),
                checkpoint.state().name(),
                checkpoint.nextStepIndex(),
                checkpoint.completedSteps().stream()
                        .map(record -> record.stepId())
                        .toList(),
                checkpoint.lastErrorCode(),
                checkpoint.lastErrorMessage()
        );
    }

    private PlanningResult run(AgentInvocation invocation, boolean resume) {
        WorkflowDefinition workflow = workflow(invocation);
        if (resume) {
            requireOwner(invocation);
        }
        HarnessResult result = resume
                ? harness.resume(workflow, invocation.runId())
                : harness.execute(new HarnessRunRequest(
                        invocation.runId(),
                        workflow,
                        List.of(
                                narrative(invocation.message()),
                                preserved(
                                        OWNER_CONTEXT,
                                        ContextKind.POLICY,
                                        invocation.userId()
                                )
                        )
                ));

        DraftResponse draft = contextValue(result, DRAFT_CONTEXT, DraftResponse.class);
        PlanView plan = contextValue(result, PLAN_CONTEXT, PlanView.class);
        if (result.state() == RunState.SUCCEEDED) {
            return new PlanningResult(
                    draft,
                    plan,
                    planningReply(draft, plan),
                    plan == null
            );
        }
        return new PlanningResult(
                draft,
                plan,
                "规划执行已暂停：" + failure(result) + "。可使用当前 runId 恢复。",
                true
        );
    }

    private WorkflowDefinition workflow(AgentInvocation invocation) {
        return new WorkflowDefinition(
                "travel-planning",
                "v1",
                List.of(
                        new WorkflowStep(
                                "normalize-requirements",
                                context -> ToolExecutionContext.callAs(
                                        invocation.userId(),
                                        context.idempotencyKey(),
                                        () -> saveDraft(invocation)
                                ),
                                ExecutionPolicy.once()
                        ),
                        new WorkflowStep(
                                "solve-and-validate",
                                context -> ToolExecutionContext.callAs(
                                        invocation.userId(),
                                        context.idempotencyKey(),
                                        () -> solvePlan(context)
                                ),
                                ExecutionPolicy.once()
                        )
                )
        );
    }

    private StepResult saveDraft(AgentInvocation invocation)
            throws RetryableStepException, StepRejectedException {
        DraftResponse draft = call(() -> createOrUpdateDraft(
                invocation,
                preferenceAgent.enrichPlanningMessage(
                        invocation.message(),
                        invocation.preferences()
                )
        ));
        return new StepResult(
                write(draft),
                Map.of("requestId", draft.requestId()),
                List.of(preserved(DRAFT_CONTEXT, ContextKind.TOOL_RESULT, write(draft)))
        );
    }

    private StepResult solvePlan(StepContext context)
            throws RetryableStepException, StepRejectedException {
        DraftResponse draft = read(context.context(), DRAFT_CONTEXT, DraftResponse.class);
        if (draft == null || !draft.missingFields().isEmpty()) {
            return StepResult.of("SKIPPED");
        }
        PlanView plan = call(() -> generatePlanTool.execute(
                new PlanningGeneratePlanTool.Input(draft.requestId())
        ));
        return new StepResult(
                write(plan),
                Map.of("planId", plan.id()),
                List.of(preserved(PLAN_CONTEXT, ContextKind.PLAN_IR, write(plan)))
        );
    }

    private DraftResponse createOrUpdateDraft(
            AgentInvocation invocation,
            String enrichedMessage
    ) {
        String requestId = invocation.planningRequestId();
        if (!hasText(requestId) && hasText(invocation.planId())) {
            requestId = getPlanTool.execute(
                    new PlanningGetPlanTool.Input(invocation.planId())
            ).requestId();
        }
        if (hasText(requestId)) {
            return updateDraftTool.execute(new PlanningUpdateDraftTool.Input(
                    requestId,
                    new NaturalLanguageUpdateRequest(enrichedMessage)
            ));
        }
        return createDraftTool.execute(new PlanningCreateDraftTool.Input(
                new DraftRequest(
                        fallbackConversationId(invocation),
                        enrichedMessage
                )
        ));
    }

    private <T> T call(CheckedCall<T> call)
            throws RetryableStepException, StepRejectedException {
        try {
            return call.get();
        } catch (ResponseStatusException exception) {
            String message = exception.getReason() == null
                    ? "规划请求失败"
                    : exception.getReason();
            if (exception.getStatusCode().is4xxClientError()) {
                throw new StepRejectedException("PLANNING_REJECTED", message, exception);
            }
            throw new RetryableStepException("PLANNING_UNAVAILABLE", message, exception);
        } catch (RuntimeException exception) {
            throw new RetryableStepException(
                    "PLANNING_STEP_FAILED",
                    "规划步骤临时失败",
                    exception
            );
        }
    }

    private String planningReply(DraftResponse draft, PlanView plan) {
        if (draft == null) {
            return "规划尚未完成，可使用当前 runId 恢复。";
        }
        if (plan == null) {
            return "还需要补充："
                    + fieldLabels(draft.missingFields())
                    + "。补充后我会继续生成行程。";
        }
        List<PlanIssue> problems = plan.issues().stream()
                .filter(issue -> !"INFO".equals(issue.severity()))
                .toList();
        if (problems.isEmpty()) {
            return "已生成 " + plan.days() + " 天行程，预算与时间约束均已校验。";
        }
        return "行程已生成，但检测到 " + problems.size()
                + " 个需要处理的问题；已保留确定性校验结果供你确认。";
    }

    private String fieldLabels(List<String> fields) {
        return fields.stream()
                .map(this::fieldLabel)
                .reduce((left, right) -> left + "、" + right)
                .orElse("必要旅行信息");
    }

    private String fieldLabel(String field) {
        return switch (field) {
            case "city" -> "目的地";
            case "startDate" -> "出发日期";
            case "days" -> "旅行天数";
            case "budget" -> "预算";
            default -> field;
        };
    }

    private String fallbackConversationId(AgentInvocation invocation) {
        return hasText(invocation.conversationId())
                ? invocation.conversationId()
                : "orchestration-" + invocation.runId();
    }

    private ContextItem narrative(String content) {
        return new ContextItem(
                "user-goal",
                ContextKind.NARRATIVE,
                content,
                0,
                false,
                Instant.now()
        );
    }

    private ContextItem preserved(String id, ContextKind kind, String content) {
        return new ContextItem(
                id,
                kind,
                content,
                Integer.MAX_VALUE,
                true,
                Instant.now()
        );
    }

    private void requireOwner(AgentInvocation invocation) {
        requireOwner(invocation.runId(), invocation.userId());
    }

    private RunCheckpoint requireOwner(String runId, String userId) {
        RunCheckpoint checkpoint;
        try {
            checkpoint = harness.checkpoint(runId);
        } catch (HarnessConflictException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "规划检查点不存在",
                    exception
            );
        }
        String owner = checkpoint.context().stream()
                .filter(item -> OWNER_CONTEXT.equals(item.id()))
                .map(ContextItem::content)
                .findFirst()
                .orElse(null);
        if (!userId.equals(owner)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权恢复该规划任务");
        }
        return checkpoint;
    }

    private String failure(HarnessResult result) {
        return result.failure() == null
                ? result.state().name()
                : result.failure().code() + " " + result.failure().message();
    }

    private <T> T contextValue(HarnessResult result, String id, Class<T> type) {
        return read(result.context(), id, type);
    }

    private <T> T read(List<ContextItem> items, String id, Class<T> type) {
        return items.stream()
                .filter(item -> id.equals(item.id()))
                .reduce((first, second) -> second)
                .map(item -> read(item.content(), type))
                .orElse(null);
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划检查点解析失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划检查点序列化失败", exception);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @FunctionalInterface
    private interface CheckedCall<T> {
        T get();
    }
}
