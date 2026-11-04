package com.travelguide.harness;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextWindowCompactorTest {
    private final ContextWindowCompactor compactor = new ContextWindowCompactor();
    private final Instant now = Instant.parse("2026-11-01T00:00:00Z");

    @Test
    void compressesOldNarrativeButPreservesStructuredContext() {
        List<ContextItem> source = List.of(
                ContextItem.preserved(
                        "plan",
                        ContextKind.PLAN_IR,
                        "PLAN-IR must remain byte-for-byte stable",
                        now
                ),
                ContextItem.narrative("n1", "a".repeat(160), now),
                ContextItem.narrative("n2", "b".repeat(160), now),
                ContextItem.narrative("n3", "c".repeat(160), now),
                ContextItem.preserved(
                        "evidence",
                        ContextKind.EVIDENCE,
                        "citation evidence that must not be rewritten",
                        now
                )
        );
        int before = compactor.estimateTokens(source);

        ContextCompactor.CompactionResult result = compactor.compact(source, 180);

        assertThat(result.compacted()).isTrue();
        assertThat(result.overBudget()).isFalse();
        assertThat(result.estimatedTokensAfter()).isLessThan(before);
        assertThat(result.context())
                .extracting(ContextItem::id)
                .contains("plan", "evidence", "compacted-summary");
        assertThat(result.context())
                .filteredOn(item -> item.kind() == ContextKind.PLAN_IR
                        || item.kind() == ContextKind.EVIDENCE)
                .hasSize(2);
        assertThat(result.context())
                .filteredOn(item -> item.kind() == ContextKind.NARRATIVE)
                .hasSizeLessThan(3);

        ContextCompactor.CompactionResult repeated = compactor.compact(
                result.context(),
                180
        );
        assertThat(repeated.context())
                .filteredOn(item -> item.kind() == ContextKind.SUMMARY)
                .hasSizeLessThanOrEqualTo(1);
    }

    @Test
    void reportsOverBudgetWhenOnlyPreservedContextRemains() {
        List<ContextItem> source = List.of(
                ContextItem.preserved(
                        "plan",
                        ContextKind.PLAN_IR,
                        "x".repeat(400),
                        now
                ),
                ContextItem.preserved(
                        "budget",
                        ContextKind.BUDGET_TIME,
                        "y".repeat(400),
                        now
                )
        );

        ContextCompactor.CompactionResult result = compactor.compact(source, 100);

        assertThat(result.compacted()).isFalse();
        assertThat(result.overBudget()).isTrue();
        assertThat(result.context()).isEqualTo(source);
    }
}
