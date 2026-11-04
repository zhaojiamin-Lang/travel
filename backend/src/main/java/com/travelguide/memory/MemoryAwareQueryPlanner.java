package com.travelguide.memory;

import com.travelguide.memory.MemoryModels.EntityResolution;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.query.QueryRewriteService;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 将记忆实体解析结果并入规则化 QueryPlan。
 */
public final class MemoryAwareQueryPlanner {

    private final EntityResolver entityResolver;
    private final QueryRewriteService queryRewriteService;

    public MemoryAwareQueryPlanner(
            List<KnowledgeDocument> documents,
            EntityResolver entityResolver
    ) {
        this.entityResolver = entityResolver;
        this.queryRewriteService = new QueryRewriteService(documents);
    }

    public QueryPlan plan(
            String userId,
            String sessionId,
            String question,
            QueryContext context
    ) {
        EntityResolution resolution = entityResolver.resolve(userId, sessionId, question, context);
        QueryPlan base = queryRewriteService.rewrite(resolution.standaloneQuery(), context);

        LinkedHashSet<String> entityIds = new LinkedHashSet<>(base.entityIds());
        entityIds.addAll(resolution.resolvedEntityIds());
        List<String> poiIds = entityIds.stream()
                .filter(id -> id.startsWith("poi:"))
                .map(id -> id.substring("poi:".length()))
                .toList();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>(base.unresolvedMentions());
        unresolved.addAll(resolution.unresolvedMentions());

        return new QueryPlan(
                question,
                resolution.standaloneQuery(),
                base.intent(),
                List.copyOf(entityIds),
                poiIds,
                base.factTypes(),
                base.city(),
                base.currentPoiName(),
                base.asOf(),
                List.copyOf(unresolved),
                base.rewritten() || !resolution.resolvedMentions().isEmpty(),
                resolution.resolvedMentions().isEmpty() ? base.rewriteSource() : "MEMORY_ENTITY_RULES"
        );
    }
}
