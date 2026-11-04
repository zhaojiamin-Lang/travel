package com.travelguide.orchestration;

import com.travelguide.web.ApiDtos.AskResponse;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.PlanView;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 多 Agent 编排模块对外的数据契约。
 *
 * <p>所有状态对象都是不可变记录，避免 Agent 之间通过可变共享对象互相污染。
 * 路由结果和偏好会显式传给下游 Agent，调用链可以完整回放。</p>
 */
public final class OrchestrationModels {

    private OrchestrationModels() {
    }

    public enum AgentType {
        ROUTER,
        PLANNING,
        GUIDE,
        PREFERENCE
    }

    public enum Intent {
        PLANNING,
        GUIDE,
        PREFERENCE,
        CLARIFY
    }

    public enum Surface {
        GENERAL,
        PLAN,
        GUIDE
    }

    /**
     * 前端提交给编排入口的请求。用户身份只从服务端会话读取，不接受客户端伪造。
     */
    public record ExecuteRequest(
            @NotBlank(message = "请输入要处理的内容")
            @Size(max = 2000, message = "单次输入不能超过 2000 个字符")
            String message,
            @Size(max = 64, message = "会话标识过长")
            String conversationId,
            @Size(max = 64, message = "规划请求标识过长")
            String planningRequestId,
            @Size(max = 64, message = "行程标识过长")
            String planId,
            @Size(max = 64, message = "导游会话标识过长")
            String guideSessionId,
            Surface surface
    ) {
        public ExecuteRequest {
            surface = surface == null ? Surface.GENERAL : surface;
        }
    }

    /**
     * 服务端可信身份与前端请求合并后的内部命令。
     */
    public record OrchestrationCommand(
            String userId,
            ExecuteRequest request
    ) {
        public OrchestrationCommand {
            if (request == null) {
                throw new IllegalArgumentException("编排请求不能为空");
            }
        }
    }

    public record RoutingContext(
            String message,
            Surface surface,
            String planId,
            String guideSessionId
    ) {
        public RoutingContext {
            surface = surface == null ? Surface.GENERAL : surface;
        }
    }

    public record RoutingDecision(
            Intent primary,
            Set<Intent> secondary,
            double confidence,
            String reason,
            String decidedBy
    ) {
        public RoutingDecision {
            if (primary == null) {
                throw new IllegalArgumentException("主意图不能为空");
            }
            LinkedHashSet<Intent> normalized = new LinkedHashSet<>(
                    secondary == null ? Set.of() : secondary
            );
            normalized.remove(primary);
            secondary = Collections.unmodifiableSet(normalized);
            confidence = Math.max(0, Math.min(1, confidence));
        }
    }

    public record PreferenceView(
            String signalId,
            String type,
            String key,
            String value,
            String label,
            double confidence
    ) {
    }

    /**
     * Agent 的统一输入。偏好是只读快照，业务 Agent 无权修改记忆。
     */
    public record AgentInvocation(
            String runId,
            String userId,
            String conversationId,
            String message,
            String planningRequestId,
            String planId,
            String guideSessionId,
            List<PreferenceView> preferences
    ) {
        public AgentInvocation {
            preferences = List.copyOf(preferences == null ? List.of() : preferences);
        }
    }

    public sealed interface AgentResult permits
            PlanningResult,
            GuideResult,
            PreferenceResult,
            ClarificationResult {

        AgentType agent();

        String reply();

        boolean needsInput();
    }

    public record PlanningResult(
            DraftResponse draft,
            PlanView plan,
            String reply,
            boolean needsInput
    ) implements AgentResult {
        @Override
        public AgentType agent() {
            return AgentType.PLANNING;
        }
    }

    public record GuideResult(
            AskResponse answer,
            String reply,
            boolean needsInput
    ) implements AgentResult {
        @Override
        public AgentType agent() {
            return AgentType.GUIDE;
        }
    }

    public record PreferenceResult(
            List<PreferenceView> available,
            List<PreferenceView> updated,
            String reply,
            boolean needsInput
    ) implements AgentResult {
        public PreferenceResult {
            available = List.copyOf(available == null ? List.of() : available);
            updated = List.copyOf(updated == null ? List.of() : updated);
        }

        @Override
        public AgentType agent() {
            return AgentType.PREFERENCE;
        }
    }

    public record ClarificationResult(
            String question,
            String reply
    ) implements AgentResult {
        @Override
        public AgentType agent() {
            return AgentType.ROUTER;
        }

        @Override
        public boolean needsInput() {
            return true;
        }
    }

    /**
     * 一次编排的完整可观测结果，包含路由判断、实际执行路径和最终业务载荷。
     */
    public record OrchestrationResponse(
            String runId,
            Instant createdAt,
            long elapsedMs,
            RoutingDecision routing,
            List<AgentType> agentPath,
            List<PreferenceView> preferences,
            AgentResult primaryResult,
            List<AgentResult> supportingResults,
            List<String> notices
    ) {
        public OrchestrationResponse {
            agentPath = List.copyOf(agentPath == null ? List.of() : agentPath);
            preferences = List.copyOf(preferences == null ? List.of() : preferences);
            supportingResults = List.copyOf(
                    supportingResults == null ? List.of() : supportingResults
            );
            notices = List.copyOf(notices == null ? List.of() : notices);
        }
    }

    public record RunStatus(
            String runId,
            String workflowId,
            String state,
            int nextStepIndex,
            List<String> completedSteps,
            String errorCode,
            String errorMessage
    ) {
        public RunStatus {
            completedSteps = List.copyOf(
                    completedSteps == null ? List.of() : completedSteps
            );
        }
    }
}
