package com.travelguide.rag.generation;

import java.util.List;

/**
 * 受证据约束的回答结构。
 */
public final class GroundingModels {

    private GroundingModels() {
    }

    public record EvidenceCitation(
            String evidenceId,
            String docId,
            int docVersion,
            String title,
            String section,
            String sourceName,
            String sourceUrl,
            String sourceTier,
            String dataConfidence,
            String validFrom,
            String validTo,
            String quote,
            String rawSnapshot
    ) {
    }

    public record AnswerClaim(
            String claimId,
            String text,
            List<String> citationIds,
            String support
    ) {
        public AnswerClaim {
            citationIds = List.copyOf(citationIds);
        }
    }

    public record RetrievalTrace(
            String originalQuery,
            String standaloneQuery,
            String intent,
            List<String> channels,
            int indexedDocuments,
            int candidateCount,
            int evidenceCount,
            long latencyMs
    ) {
        public RetrievalTrace {
            channels = List.copyOf(channels);
        }
    }

    public record GroundedAnswer(
            String answer,
            List<AnswerClaim> claims,
            List<EvidenceCitation> citations,
            List<String> uncertainties,
            List<String> conflicts,
            RetrievalTrace retrieval
    ) {
        public GroundedAnswer {
            claims = List.copyOf(claims);
            citations = List.copyOf(citations);
            uncertainties = List.copyOf(uncertainties);
            conflicts = List.copyOf(conflicts);
        }
    }
}
