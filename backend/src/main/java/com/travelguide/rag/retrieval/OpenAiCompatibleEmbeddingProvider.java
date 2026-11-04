package com.travelguide.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OpenAI Compatible Embeddings 适配器。
 *
 * <p>兼容当前千问 DashScope Compatible Mode，也可通过配置切换到其他
 * OpenAI 兼容 Embedding 服务。API Key 仅放在 Authorization Header。</p>
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final int maxBatchSize;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleEmbeddingProvider(
            String baseUrl,
            String apiKey,
            String model,
            ObjectMapper objectMapper
    ) {
        this(
                baseUrl,
                apiKey,
                model,
                objectMapper,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                10
        );
    }

    public OpenAiCompatibleEmbeddingProvider(
            String baseUrl,
            String apiKey,
            String model,
            ObjectMapper objectMapper,
            HttpClient httpClient,
            int maxBatchSize
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Embedding API Key 未配置");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Embedding 模型未配置");
        }
        if (maxBatchSize <= 0) {
            throw new IllegalArgumentException("Embedding 批大小必须大于 0");
        }
        this.endpoint = URI.create(embeddingEndpoint(baseUrl));
        this.apiKey = apiKey;
        this.model = model;
        this.maxBatchSize = maxBatchSize;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<float[]> result = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += maxBatchSize) {
            int end = Math.min(texts.size(), start + maxBatchSize);
            result.addAll(requestBatch(texts.subList(start, end)));
        }
        return List.copyOf(result);
    }

    @Override
    public String modelId() {
        return model;
    }

    private List<float[]> requestBatch(List<String> texts) {
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        ArrayNode input = requestBody.putArray("input");
        texts.forEach(input::add);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        requestBody.toString(),
                        StandardCharsets.UTF_8
                ))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 不输出响应正文，防止供应商错误信息携带敏感上下文。
                throw new IllegalStateException("Embedding 请求失败，HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != texts.size()) {
                throw new IllegalStateException("Embedding 响应数量异常");
            }
            List<float[]> vectors = new ArrayList<>(Collections.nCopies(texts.size(), null));
            int fallbackIndex = 0;
            for (JsonNode item : data) {
                JsonNode values = item.path("embedding");
                float[] vector = new float[values.size()];
                for (int index = 0; index < values.size(); index++) {
                    vector[index] = (float) values.get(index).asDouble();
                }
                if (vector.length == 0) {
                    throw new IllegalStateException("Embedding 返回空向量");
                }
                int index = item.path("index").asInt(fallbackIndex++);
                if (index < 0 || index >= vectors.size() || vectors.get(index) != null) {
                    throw new IllegalStateException("Embedding 响应 index 异常");
                }
                vectors.set(index, vector);
            }
            if (vectors.stream().anyMatch(value -> value == null)) {
                throw new IllegalStateException("Embedding 响应缺少向量");
            }
            return vectors;
        } catch (IOException exception) {
            throw new IllegalStateException("Embedding 网络请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding 请求被中断", exception);
        }
    }

    private String embeddingEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://api.openai.com"
                : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith("/v1") ? base + "/embeddings" : base + "/v1/embeddings";
    }
}
