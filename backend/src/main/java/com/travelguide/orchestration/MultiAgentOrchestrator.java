package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentResult;
import com.travelguide.orchestration.OrchestrationModels.AgentType;
import com.travelguide.orchestration.OrchestrationModels.ClarificationResult;
import com.travelguide.orchestration.OrchestrationModels.ExecuteRequest;
import com.travelguide.orchestration.OrchestrationModels.Intent;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationCommand;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationResponse;
import com.travelguide.orchestration.OrchestrationModels.PreferenceResult;
import com.travelguide.orchestration.OrchestrationModels.PreferenceView;
import com.travelguide.orchestration.OrchestrationModels.PlanningResult;
import com.travelguide.orchestration.OrchestrationModels.RoutingContext;
import com.travelguide.orchestration.OrchestrationModels.RoutingDecision;
import com.travelguide.orchestration.OrchestrationModels.RunStatus;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Supervisor 多 Agent 编排器。
 *
 * <p>负责唯一一次路由、偏好前置、业务 Agent 分发和结果汇总。
 * 各 Agent 不互相调用，避免出现自由对话、重复调用和无法终止的执行环。</p>
 */
@Service
public class MultiAgentOrchestrator {

    private final IntentRouter intentRouter;
    private final PreferenceAgent preferenceAgent;
    private final PlanningAgent planningAgent;
    private final GuideAgent guideAgent;

    public MultiAgentOrchestrator(
            IntentRouter intentRouter,
            PreferenceAgent preferenceAgent,
            PlanningAgent planningAgent,
            GuideAgent guideAgent
    ) {
        this.intentRouter = intentRouter;
        this.preferenceAgent = preferenceAgent;
        this.planningAgent = planningAgent;
        this.guideAgent = guideAgent;
    }

    public OrchestrationResponse execute(OrchestrationCommand command) {
        return execute(command, null);
    }

    public OrchestrationResponse execute(
            OrchestrationCommand command,
            String requestedRunId
    ) {
        return ToolExecutionContext.runAs(
                command.userId(),
                () -> executeInternal(command, requestedRunId)
        );
    }

    private OrchestrationResponse executeInternal(
            OrchestrationCommand command,
            String requestedRunId
    ) {
        long startedAt = System.nanoTime();
        Instant createdAt = Instant.now();
        String runId = requestedRunId == null || requestedRunId.isBlank()
                ? "run_" + UUID.randomUUID().toString()
                        .replace("-", "")
                        .substring(0, 16)
                : validateRunId(requestedRunId);
        ExecuteRequest request = command.request();
        String message = request.message().trim();

        RoutingDecision routing = intentRouter.route(new RoutingContext(
                message,
                request.surface(),
                request.planId(),
                request.guideSessionId()
        ));
        List<AgentType> agentPath = new ArrayList<>();
        agentPath.add(AgentType.ROUTER);

        if (routing.primary() == Intent.CLARIFY) {
            return response(
                    runId,
                    createdAt,
                    startedAt,
                    routing,
                    agentPath,
                    List.of(),
                    new ClarificationResult(
                            "你是想规划行程、咨询当前景点，还是设置旅行偏好？",
                            "我还不能确定你的目标，请补充一句具体想做什么。"
                    ),
                    List.of(),
                    routerNotice(routing)
            );
        }

        AgentInvocation emptyPreferenceInvocation = invocation(
                runId,
                command,
                message,
                List.of()
        );
        PreferenceResult preferenceResult = null;
        if (needsPreferencePass(routing, command.userId())) {
            // 规划需要偏好注入；明确导游追问由 GuideService 直接召回记忆，避免重复读取。
            preferenceResult = preferenceAgent.execute(emptyPreferenceInvocation);
            agentPath.add(AgentType.PREFERENCE);
        }

        AgentInvocation businessInvocation = invocation(
                runId,
                command,
                message,
                preferenceResult == null ? List.of() : preferenceResult.available()
        );
        AgentResult primaryResult = switch (routing.primary()) {
            case PLANNING -> {
                agentPath.add(AgentType.PLANNING);
                yield planningAgent.execute(businessInvocation);
            }
            case GUIDE -> {
                agentPath.add(AgentType.GUIDE);
                yield guideAgent.execute(businessInvocation);
            }
            case PREFERENCE -> preferenceResult == null
                    ? new ClarificationResult(
                            "请先登录，再告诉我希望长期保存的旅行偏好。",
                            "登录后才能保存偏好。"
                    )
                    : preferenceResult;
            case CLARIFY -> throw new IllegalStateException("澄清意图不应进入业务分发");
        };

        List<AgentResult> supportingResults = preferenceResult == null
                || routing.primary() == Intent.PREFERENCE
                ? List.of()
                : List.of(preferenceResult);
        return response(
                runId,
                createdAt,
                startedAt,
                routing,
                agentPath,
                preferenceResult == null ? List.of() : preferenceResult.available(),
                primaryResult,
                supportingResults,
                routerNotice(routing)
        );
    }

    public OrchestrationResponse resume(String runId, OrchestrationCommand command) {
        return ToolExecutionContext.runAs(
                command.userId(),
                () -> resumeInternal(runId, command)
        );
    }

    public RunStatus status(String runId, String userId) {
        return planningAgent.status(runId, userId);
    }

    private OrchestrationResponse resumeInternal(
            String runId,
            OrchestrationCommand command
    ) {
        long startedAt = System.nanoTime();
        Instant createdAt = Instant.now();
        ExecuteRequest request = command.request();
        RoutingDecision routing = intentRouter.route(new RoutingContext(
                request.message(),
                request.surface(),
                request.planId(),
                request.guideSessionId()
        ));
        if (routing.primary() != Intent.PLANNING) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "当前 runId 不是可恢复的规划任务"
            );
        }

        AgentInvocation empty = invocation(runId, command, request.message(), List.of());
        PreferenceResult preferences = needsPreferencePass(routing, command.userId())
                ? preferenceAgent.execute(empty)
                : null;
        AgentInvocation planning = invocation(
                runId,
                command,
                request.message(),
                preferences == null ? List.of() : preferences.available()
        );
        PlanningResult result = planningAgent.resume(planning);
        List<AgentType> path = new ArrayList<>();
        path.add(AgentType.ROUTER);
        if (preferences != null) {
            path.add(AgentType.PREFERENCE);
        }
        path.add(AgentType.PLANNING);
        return response(
                runId,
                createdAt,
                startedAt,
                routing,
                path,
                preferences == null ? List.of() : preferences.available(),
                result,
                preferences == null ? List.of() : List.of(preferences),
                List.of("已从检查点恢复规划任务。")
        );
    }

    private AgentInvocation invocation(
            String runId,
            OrchestrationCommand command,
            String message,
            List<PreferenceView> preferences
    ) {
        ExecuteRequest request = command.request();
        return new AgentInvocation(
                runId,
                command.userId(),
                request.conversationId(),
                message,
                request.planningRequestId(),
                request.planId(),
                request.guideSessionId(),
                preferences
        );
    }

    private OrchestrationResponse response(
            String runId,
            Instant createdAt,
            long startedAt,
            RoutingDecision routing,
            List<AgentType> agentPath,
            List<PreferenceView> preferences,
            AgentResult primaryResult,
            List<AgentResult> supportingResults,
            List<String> notices
    ) {
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        return new OrchestrationResponse(
                runId,
                createdAt,
                elapsedMs,
                routing,
                agentPath,
                preferences,
                primaryResult,
                supportingResults,
                notices
        );
    }

    private List<String> routerNotice(RoutingDecision routing) {
        if ("RULE_FALLBACK".equals(routing.decidedBy())) {
            return List.of("路由模型暂不可用，本次已使用规则降级。");
        }
        return List.of();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String validateRunId(String value) {
        String runId = value.trim();
        if (!runId.matches("[A-Za-z0-9_-]{8,64}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "runId 格式不合法"
            );
        }
        return runId;
    }

    private boolean needsPreferencePass(RoutingDecision routing, String userId) {
        if (!hasText(userId)) {
            return false;
        }
        return routing.primary() == Intent.PLANNING
                || routing.primary() == Intent.PREFERENCE
                || routing.secondary().contains(Intent.PREFERENCE);
    }
}
