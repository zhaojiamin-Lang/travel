package com.travelguide.harness;

public record HarnessFailure(
        String code,
        String message,
        boolean recoverable
) {
}
