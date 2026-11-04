package com.travelguide.harness.resilience;

import com.travelguide.harness.StepResult;

public record StepExecution(StepResult result, int attempts) {
}
