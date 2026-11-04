package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentType;
import com.travelguide.orchestration.OrchestrationModels.ExecuteRequest;
import com.travelguide.orchestration.OrchestrationModels.GuideResult;
import com.travelguide.orchestration.OrchestrationModels.Intent;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationCommand;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationResponse;
import com.travelguide.orchestration.OrchestrationModels.PlanningResult;
import com.travelguide.orchestration.OrchestrationModels.PreferenceResult;
import com.travelguide.orchestration.OrchestrationModels.PreferenceView;
import com.travelguide.orchestration.OrchestrationModels.RoutingDecision;
import com.travelguide.orchestration.OrchestrationModels.Surface;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MultiAgentOrchestratorTest {

    @Mock
    private IntentRouter intentRouter;

    @Mock
    private PreferenceAgent preferenceAgent;

    @Mock
    private PlanningAgent planningAgent;

    @Mock
    private GuideAgent guideAgent;

    @Test
    void loadsPreferenceBeforeDispatchingPlanning() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                intentRouter,
                preferenceAgent,
                planningAgent,
                guideAgent
        );
        PreferenceView preference = new PreferenceView(
                "mem_1",
                "pace",
                "travel_pace",
                "RELAXED",
                "旅行节奏偏轻松",
                1
        );
        when(intentRouter.route(any())).thenReturn(new RoutingDecision(
                Intent.PLANNING,
                Set.of(Intent.PREFERENCE),
                0.95,
                "规划并参考偏好",
                "LLM"
        ));
        when(preferenceAgent.execute(any())).thenReturn(new PreferenceResult(
                List.of(preference),
                List.of(),
                "已加载偏好",
                false
        ));
        when(planningAgent.execute(any())).thenReturn(new PlanningResult(
                null,
                null,
                "需要补充出发日期",
                true
        ));

        OrchestrationResponse response = orchestrator.execute(command(
                "我喜欢轻松一点，帮我安排两天"
        ));

        ArgumentCaptor<AgentInvocation> captor = ArgumentCaptor.forClass(AgentInvocation.class);
        verify(planningAgent).execute(captor.capture());
        assertThat(captor.getValue().preferences()).containsExactly(preference);
        assertThat(response.routing().primary()).isEqualTo(Intent.PLANNING);
        assertThat(response.agentPath()).containsExactly(
                AgentType.ROUTER,
                AgentType.PREFERENCE,
                AgentType.PLANNING
        );
        assertThat(response.supportingResults()).hasSize(1);
    }

    @Test
    void stopsAtRouterWhenIntentNeedsClarification() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                intentRouter,
                preferenceAgent,
                planningAgent,
                guideAgent
        );
        when(intentRouter.route(any())).thenReturn(new RoutingDecision(
                Intent.CLARIFY,
                Set.of(),
                0.35,
                "目标模糊",
                "LLM"
        ));

        OrchestrationResponse response = orchestrator.execute(command("然后呢"));

        assertThat(response.agentPath()).containsExactly(AgentType.ROUTER);
        assertThat(response.primaryResult().needsInput()).isTrue();
        verify(preferenceAgent, never()).execute(any());
        verify(planningAgent, never()).execute(any());
        verify(guideAgent, never()).execute(any());
    }

    @Test
    void clearGuideRequestSkipsDuplicatePreferenceRecall() {
        MultiAgentOrchestrator orchestrator = new MultiAgentOrchestrator(
                intentRouter,
                preferenceAgent,
                planningAgent,
                guideAgent
        );
        when(intentRouter.route(any())).thenReturn(new RoutingDecision(
                Intent.GUIDE,
                Set.of(),
                0.94,
                "明确导游请求",
                "RULE_FAST_PATH"
        ));
        when(guideAgent.execute(any())).thenReturn(new GuideResult(
                null,
                "外滩全天开放。",
                false
        ));

        OrchestrationResponse response = orchestrator.execute(command(
                "这里几点关门？"
        ));

        verify(preferenceAgent, never()).execute(any());
        assertThat(response.agentPath()).containsExactly(
                AgentType.ROUTER,
                AgentType.GUIDE
        );
        assertThat(response.supportingResults()).isEmpty();
    }

    private OrchestrationCommand command(String message) {
        return new OrchestrationCommand(
                "user_test",
                new ExecuteRequest(
                        message,
                        "conversation_test",
                        null,
                        null,
                        null,
                        Surface.GENERAL
                )
        );
    }
}
