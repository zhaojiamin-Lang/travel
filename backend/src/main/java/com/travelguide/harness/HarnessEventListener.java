package com.travelguide.harness;

@FunctionalInterface
public interface HarnessEventListener {
    void onEvent(HarnessEvent event);

    HarnessEventListener NOOP = event -> {
    };
}
