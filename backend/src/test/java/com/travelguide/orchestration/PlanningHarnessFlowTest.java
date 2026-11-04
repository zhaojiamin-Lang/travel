package com.travelguide.orchestration;

import com.travelguide.harness.AgentHarness;
import com.travelguide.harness.RunState;
import com.travelguide.harness.store.CheckpointStore;
import com.travelguide.harness.store.IdempotencyStore;
import com.travelguide.harness.store.InMemoryCheckpointStore;
import com.travelguide.harness.store.InMemoryIdempotencyStore;
import com.travelguide.orchestration.OrchestrationModels.ExecuteRequest;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationCommand;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationResponse;
import com.travelguide.orchestration.OrchestrationModels.PlanningResult;
import com.travelguide.orchestration.OrchestrationModels.Surface;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key=",
        "travel.harness.store=memory"
})
@Import(PlanningHarnessFlowTest.TestStores.class)
@AutoConfigureMockMvc
class PlanningHarnessFlowTest {
    @Autowired
    private MultiAgentOrchestrator orchestrator;

    @Autowired
    private AgentHarness harness;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void planningRunsAreCheckpointedThroughHarness() {
        OrchestrationResponse response = orchestrator.execute(new OrchestrationCommand(
                "user_harness",
                new ExecuteRequest(
                        "2030年5月18日去上海玩两天，预算 3000 元，想去外滩，不赶。",
                        "conversation_harness",
                        null,
                        null,
                        null,
                        Surface.GENERAL
                )
        ));

        assertThat(response.primaryResult()).isInstanceOf(PlanningResult.class);
        assertThat(((PlanningResult) response.primaryResult()).plan()).isNotNull();
        assertThat(harness.checkpoint(response.runId()).state())
                .isEqualTo(RunState.SUCCEEDED);
        assertThat(harness.checkpoint(response.runId()).completedSteps())
                .extracting(record -> record.stepId())
                .containsExactly("normalize-requirements", "solve-and-validate");
        assertThat(orchestrator.status(response.runId(), "user_harness").state())
                .isEqualTo("SUCCEEDED");
        assertThatThrownBy(() -> orchestrator.status(
                response.runId(),
                "different_user"
        )).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test
    void acceptsClientRunIdAndExposesRecoveryStatus() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String runId = "run_frontend_recovery";

        mockMvc.perform(post("/api/orchestration/runs")
                        .session(session)
                        .header("X-Run-Id", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "message": "2030年6月18日去上海玩两天，预算 3000 元，不赶。",
                                  "conversationId": "conversation_frontend",
                                  "surface": "GENERAL"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId))
                .andExpect(jsonPath("$.primaryResult.plan").isNotEmpty());

        mockMvc.perform(get("/api/orchestration/runs/{runId}", runId)
                        .session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SUCCEEDED"))
                .andExpect(jsonPath("$.completedSteps.length()").value(2));
    }

    @Test
    void regeneratesTheCurrentPlanFromTheRefinePanel() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String createRunId = "run_refine_create";
        var created = mockMvc.perform(post("/api/orchestration/runs")
                        .session(session)
                        .header("X-Run-Id", createRunId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "2030年7月6日去上海玩两天，预算 3200 元，想去外滩，不想走太累。",
                                "conversationId", "conversation_refine",
                                "surface", "GENERAL"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        var firstPlan = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("primaryResult").path("plan");

        mockMvc.perform(post("/api/orchestration/runs")
                        .session(session)
                        .header("X-Run-Id", "run_refine_update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "message", "预算改成 2800 元，轻松一点",
                                "conversationId", "plan-" + firstPlan.path("id").asText(),
                                "planningRequestId", firstPlan.path("requestId").asText(),
                                "planId", firstPlan.path("id").asText(),
                                "surface", "PLAN"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryResult.plan.version").value(2))
                .andExpect(jsonPath("$.primaryResult.plan.budget").value(2800.0));
    }

    @TestConfiguration
    static class TestStores {
        @Bean
        @Primary
        CheckpointStore testCheckpointStore() {
            return new InMemoryCheckpointStore();
        }

        @Bean
        @Primary
        IdempotencyStore testIdempotencyStore() {
            return new InMemoryIdempotencyStore();
        }
    }
}
