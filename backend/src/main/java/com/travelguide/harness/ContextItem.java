package com.travelguide.harness;

import java.time.Instant;
import java.util.Objects;

public record ContextItem(
        String id,
        ContextKind kind,
        String content,
        int priority,
        boolean pinned,
        Instant createdAt
) {
    public ContextItem {
        id = requireText(id, "id");
        kind = Objects.requireNonNull(kind, "kind");
        content = Objects.requireNonNull(content, "content");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public static ContextItem narrative(String id, String content, Instant createdAt) {
        return new ContextItem(id, ContextKind.NARRATIVE, content, 0, false, createdAt);
    }

    public static ContextItem preserved(
            String id,
            ContextKind kind,
            String content,
            Instant createdAt
    ) {
        if (!kind.preserved()) {
            throw new IllegalArgumentException("kind must be a preserved context kind");
        }
        return new ContextItem(id, kind, content, Integer.MAX_VALUE, true, createdAt);
    }

    public boolean compressible() {
        return !pinned && !kind.preserved();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
