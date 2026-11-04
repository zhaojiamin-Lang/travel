package com.travelguide.harness;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record StepResult(
        String output,
        Map<String, String> metadata,
        List<ContextItem> contextAdditions
) {
    public StepResult {
        output = Objects.requireNonNull(output, "output");
        metadata = Map.copyOf(Objects.requireNonNull(metadata, "metadata"));
        contextAdditions = List.copyOf(
                Objects.requireNonNull(contextAdditions, "contextAdditions")
        );
    }

    public static StepResult of(String output) {
        return new StepResult(output, Map.of(), List.of());
    }
}
