package com.travelguide.harness;

@FunctionalInterface
public interface AgentStep {
    StepResult execute(StepContext context) throws Exception;
}
