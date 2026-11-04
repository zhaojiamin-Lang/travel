package com.travelguide.harness;

import java.util.List;

public interface ContextCompactor {
    CompactionResult compact(List<ContextItem> context, int maxTokens);

    record CompactionResult(
            List<ContextItem> context,
            boolean compacted,
            boolean overBudget,
            int estimatedTokensBefore,
            int estimatedTokensAfter
    ) {
        public CompactionResult {
            context = List.copyOf(context);
        }
    }
}
