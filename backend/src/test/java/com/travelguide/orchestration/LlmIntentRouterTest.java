package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.ai.AiGateway;
import com.travelguide.orchestration.OrchestrationModels.Intent;
import com.travelguide.orchestration.OrchestrationModels.RoutingContext;
import com.travelguide.orchestration.OrchestrationModels.RoutingDecision;
import com.travelguide.orchestration.OrchestrationModels.Surface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LlmIntentRouterTest {

    @Mock
    private AiGateway aiGateway;

    private LlmIntentRouter router;

    @BeforeEach
    void setUp() {
        router = new LlmIntentRouter(aiGateway, new ObjectMapper(), 0.60);
    }

    @Test
    void routesMixedPlanningAndPreferenceRequestFromStructuredModelOutput() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.complete(anyString(), anyString())).thenReturn("""
                ```json
                {
                  "primary": "PLANNING",
                  "secondary": ["PREFERENCE"],
                  "confidence": 0.94,
                  "reason": "用户要安排行程，同时表达长期偏好"
                }
                ```
                """);

        RoutingDecision decision = router.route(new RoutingContext(
                "我喜欢安静的建筑，帮我安排两天",
                Surface.GENERAL,
                null,
                null
        ));

        assertThat(decision.primary()).isEqualTo(Intent.PLANNING);
        assertThat(decision.secondary()).containsExactly(Intent.PREFERENCE);
        assertThat(decision.decidedBy()).isEqualTo("LLM");
        assertThat(decision.confidence()).isEqualTo(0.94);
    }

    @Test
    void asksForClarificationWhenModelConfidenceIsTooLow() {
        when(aiGateway.isEnabled()).thenReturn(true);
        when(aiGateway.complete(anyString(), anyString())).thenReturn("""
                {"primary":"GUIDE","secondary":[],"confidence":0.31,"reason":"目标模糊"}
                """);

        RoutingDecision decision = router.route(new RoutingContext(
                "然后呢",
                Surface.GENERAL,
                null,
                null
        ));

        assertThat(decision.primary()).isEqualTo(Intent.CLARIFY);
        assertThat(decision.decidedBy()).isEqualTo("LLM");
    }

    @Test
    void fallsBackToRulesWithoutMisroutingMixedPreference() {
        when(aiGateway.isEnabled()).thenReturn(false);

        RoutingDecision decision = router.route(new RoutingContext(
                "我不吃辣，想轻松一点，帮我规划两天",
                Surface.GENERAL,
                null,
                null
        ));

        assertThat(decision.primary()).isEqualTo(Intent.PLANNING);
        assertThat(decision.secondary()).containsExactly(Intent.PREFERENCE);
        assertThat(decision.decidedBy()).isEqualTo("RULE_FALLBACK");
    }

    @Test
    void clearGuideRequestUsesFastPathWithoutModelCall() {
        RoutingDecision decision = router.route(new RoutingContext(
                "这里门票多少钱？",
                Surface.GUIDE,
                null,
                "guide_test"
        ));

        assertThat(decision.primary()).isEqualTo(Intent.GUIDE);
        assertThat(decision.decidedBy()).isEqualTo("RULE_FAST_PATH");
        verifyNoInteractions(aiGateway);
    }
}
