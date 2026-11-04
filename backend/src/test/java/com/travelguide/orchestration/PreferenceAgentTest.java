package com.travelguide.orchestration;

import com.travelguide.memory.InMemoryMemoryStore;
import com.travelguide.memory.MemoryService;
import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.PreferenceResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreferenceAgentTest {

    @Test
    void remembersOnlyExplicitStablePreferencesAndDoesNotDuplicateThem() {
        PreferenceAgent agent = new PreferenceAgent(new MemoryService(new InMemoryMemoryStore()));
        AgentInvocation invocation = invocation(
                "我喜欢安静的建筑，不吃辣，行程轻松一点，帮我安排"
        );

        PreferenceResult first = agent.execute(invocation);
        PreferenceResult second = agent.execute(invocation);
        PreferenceResult third = agent.execute(invocation("我还喜欢自然"));

        assertThat(first.updated()).hasSize(3);
        assertThat(first.available())
                .extracting(item -> item.key())
                .contains("travel_pace", "spicy", "travel_interest");
        assertThat(second.updated()).isEmpty();
        assertThat(second.available()).hasSize(3);
        assertThat(third.available())
                .filteredOn(item -> "travel_interest".equals(item.key()))
                .singleElement()
                .extracting(item -> item.value())
                .asString()
                .contains("建筑", "自然");
    }

    @Test
    void preferenceQuestionOnlyRecallsAndDoesNotCreateNewMemory() {
        PreferenceAgent agent = new PreferenceAgent(new MemoryService(new InMemoryMemoryStore()));
        PreferenceResult result = agent.execute(invocation("我喜欢什么节奏？"));

        assertThat(result.updated()).isEmpty();
        assertThat(result.available()).isEmpty();
    }

    private AgentInvocation invocation(String message) {
        return new AgentInvocation(
                "run_test",
                "user_test",
                "conversation_test",
                message,
                null,
                null,
                null,
                List.of()
        );
    }
}
