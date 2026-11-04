package com.travelguide.harness;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 确定性的兜底压缩器。它只会淘汰未固定的叙述内容；
 * 计划中间表示、证据、预算与时间字段、策略、工具结果和系统指令都会被保留。
 */
public final class ContextWindowCompactor implements ContextCompactor {
    private static final int SUMMARY_CHAR_LIMIT = 160;

    @Override
    public CompactionResult compact(List<ContextItem> context, int maxTokens) {
        List<ContextItem> source = List.copyOf(
                Objects.requireNonNull(context, "context")
        );
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be positive");
        }
        int before = estimateTokens(source);
        if (before <= maxTokens) {
            return new CompactionResult(source, false, false, before, before);
        }

        List<ContextItem> narratives = source.stream()
                .filter(ContextItem::compressible)
                .toList();
        if (narratives.isEmpty()) {
            return new CompactionResult(source, false, true, before, before);
        }

        for (int keep = narratives.size() - 1; keep >= 0; keep--) {
            List<ContextItem> retained = narratives.subList(
                    narratives.size() - keep,
                    narratives.size()
            );
            List<ContextItem> dropped = narratives.subList(0, narratives.size() - keep);
            List<ContextItem> candidate = rebuild(source, retained, dropped);
            int after = estimateTokens(candidate);
            if (after <= maxTokens) {
                return new CompactionResult(candidate, true, false, before, after);
            }
        }
        return new CompactionResult(source, false, true, before, before);
    }

    public int estimateTokens(List<ContextItem> context) {
        return context.stream()
                .mapToInt(item -> 4 + estimateTokens(item.content()))
                .sum();
    }

    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int codePoints = text.codePointCount(0, text.length());
        return Math.max(1, (codePoints + 1) / 2);
    }

    private List<ContextItem> rebuild(
            List<ContextItem> source,
            List<ContextItem> retainedNarratives,
            List<ContextItem> droppedNarratives
    ) {
        List<ContextItem> result = new ArrayList<>();
        if (!droppedNarratives.isEmpty()) {
            result.add(new ContextItem(
                    "compacted-summary",
                    ContextKind.SUMMARY,
                    summary(droppedNarratives),
                    Integer.MAX_VALUE,
                    false,
                    droppedNarratives.get(0).createdAt()
            ));
        }
        for (ContextItem item : source) {
            if (!item.compressible() || retainedNarratives.contains(item)) {
                result.add(item);
            }
        }
        return result;
    }

    private String summary(List<ContextItem> dropped) {
        StringBuilder result = new StringBuilder(
                "Earlier narrative compacted (" + dropped.size() + " items): "
        );
        for (ContextItem item : dropped) {
            if (result.length() >= SUMMARY_CHAR_LIMIT) {
                break;
            }
            int remaining = SUMMARY_CHAR_LIMIT - result.length();
            String content = item.content().replaceAll("\\s+", " ").trim();
            result.append(content, 0, Math.min(content.length(), remaining));
            if (result.length() < SUMMARY_CHAR_LIMIT) {
                result.append(" | ");
            }
        }
        return result.toString();
    }
}
