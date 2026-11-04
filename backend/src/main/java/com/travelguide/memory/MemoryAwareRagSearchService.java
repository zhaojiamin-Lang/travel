package com.travelguide.memory;

import com.travelguide.rag.RagEngine;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.RagSearchResult;
import com.travelguide.rag.RagModels.RetrievalOptions;
import com.travelguide.rag.RagModels.RetrievalProfile;

/**
 * 带长期记忆实体解析的 RAG 查询门面。
 */
public final class MemoryAwareRagSearchService {

    private final RagEngine ragEngine;
    private final MemoryAwareQueryPlanner queryPlanner;

    public MemoryAwareRagSearchService(
            RagEngine ragEngine,
            MemoryAwareQueryPlanner queryPlanner
    ) {
        this.ragEngine = ragEngine;
        this.queryPlanner = queryPlanner;
    }

    public RagSearchResult search(
            String userId,
            String sessionId,
            String question,
            QueryContext context,
            RetrievalOptions options,
            RetrievalProfile profile
    ) {
        var plan = queryPlanner.plan(userId, sessionId, question, context);
        return ragEngine.search(plan, options, profile);
    }
}
