package com.travelguide.rag.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地确定性 Embedding，用于单元测试和无模型环境冒烟。
 *
 * <p>它是词法特征哈希，不具备真实语义模型的中文同义理解能力；
 * 生产评测必须替换为真实 EmbeddingProvider。</p>
 */
public final class HashingEmbeddingProvider implements EmbeddingProvider {

    private final int dimensions;
    private final TextTokenizer tokenizer;

    public HashingEmbeddingProvider(int dimensions) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("向量维度必须大于 0");
        }
        this.dimensions = dimensions;
        this.tokenizer = new TextTokenizer();
    }

    @Override
    public float[] embed(String text) {
        float[] vector = new float[dimensions];
        Map<String, Integer> frequencies = new LinkedHashMap<>();
        tokenizer.tokenize(text).forEach(token ->
                frequencies.merge(token, 1, Integer::sum)
        );
        for (Map.Entry<String, Integer> entry : frequencies.entrySet()) {
            int index = Math.floorMod(entry.getKey().hashCode(), dimensions);
            vector[index] += (float) (1.0 + Math.log(entry.getValue()));
        }
        normalize(vector);
        return vector;
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    @Override
    public String modelId() {
        return "local-hashing-" + dimensions;
    }

    private void normalize(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            return;
        }
        float factor = (float) (1.0 / Math.sqrt(norm));
        for (int index = 0; index < vector.length; index++) {
            vector[index] *= factor;
        }
    }
}
