package com.travelguide.harness;

@FunctionalInterface
public interface StepFallback {
    StepResult execute(StepContext context, Exception cause) throws Exception;
}
