package com.travelguide.rag.retrieval;

import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;

import java.util.List;

/**
 * Cross-Encoder Rerank 适配接口。
 *
 * <p>真实模型可以通过 HTTP 或本地推理实现此接口，混合召回层无需感知供应商。</p>
 */
public interface Reranker {

    List<RankedChunk> rerank(QueryPlan query, List<RankedChunk> candidates, int topK);
}
