package com.travelguide.rag.retrieval;

import java.util.List;

/**
 * Embedding 适配接口。
 *
 * <p>生产环境应替换为 BGE-M3 或当前供应商模型；RAG 核心只依赖向量，
 * 不绑定 Spring AI 或具体厂商 SDK。</p>
 */
public interface EmbeddingProvider {

    float[] embed(String text);

    /**
     * 批量向量化。真实模型应覆盖此方法减少 HTTP 往返。
     */
    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    String modelId();
}
