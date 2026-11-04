package com.travelguide.rag.retrieval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;
import com.travelguide.harness.resilience.ResilienceDefaults;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * DashScope GTE Rerank 适配器。
 *
 * <p>远程重排失败或超时时自动回退本地启发式重排，保证导游问答可用。</p>
 */
public final class DashScopeReranker implements Reranker {

    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Reranker fallback;
    private final CircuitBreaker circuitBreaker;

    public DashScopeReranker(
            String baseUrl,
            String apiKey,
            String model,
            ObjectMapper objectMapper,
            Reranker fallback
    ) {
        this(
                baseUrl,
                apiKey,
                model,
                objectMapper,
                fallback,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
        );
    }

    public DashScopeReranker(
            String baseUrl,
            String apiKey,
            String model,
            ObjectMapper objectMapper,
            Reranker fallback,
            HttpClient httpClient
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Rerank API Key 未配置");
        }
        this.endpoint = URI.create(rerankEndpoint(baseUrl));
        this.apiKey = apiKey;
        this.model = model == null || model.isBlank() ? "gte-rerank-v2" : model;
        this.objectMapper = objectMapper;
        this.fallback = fallback;
        this.httpClient = httpClient;
        this.circuitBreaker = ResilienceDefaults.circuitBreaker("dashscope-rerank");
    }

    @Override
    public List<RankedChunk> rerank(QueryPlan query, List<RankedChunk> candidates, int topK) {
        if (candidates.isEmpty() || topK <= 0) {
            return List.of();
        }
        try {
            Callable<List<RankedChunk>> call = CircuitBreaker.decorateCallable(
                    circuitBreaker,
                    () -> remoteRerank(query, candidates, topK)
            );
            return call.call();
        } catch (CallNotPermittedException ignored) {
            return fallback.rerank(query, candidates, topK);
        } catch (RuntimeException ignored) {
            return fallback.rerank(query, candidates, topK);
        } catch (Exception ignored) {
            return fallback.rerank(query, candidates, topK);
        }
    }

    private List<RankedChunk> remoteRerank(
            QueryPlan query,
            List<RankedChunk> candidates,
            int topK
    ) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        ObjectNode input = body.putObject("input");
        input.put("query", query.standaloneQuery());
        ArrayNode documents = input.putArray("documents");
        candidates.forEach(candidate -> documents.add(candidate.chunk().embeddingText()));
        ObjectNode parameters = body.putObject("parameters");
        parameters.put("return_documents", false);
        parameters.put("top_n", Math.min(topK, candidates.size()));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(endpoint)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Rerank 请求失败，HTTP " + response.statusCode());
            }
            JsonNode results = objectMapper.readTree(response.body())
                    .path("output")
                    .path("results");
            List<RankedChunk> reranked = new ArrayList<>();
            for (JsonNode item : results) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) {
                    continue;
                }
                RankedChunk source = candidates.get(index);
                reranked.add(new RankedChunk(
                        source.chunk(),
                        item.path("relevance_score").asDouble(),
                        source.channelRanks()
                ));
            }
            return reranked.stream()
                    .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                            .thenComparing(item -> item.chunk().chunkId()))
                    .limit(topK)
                    .toList();
        } catch (IOException exception) {
            throw new IllegalStateException("Rerank 网络请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Rerank 请求被中断", exception);
        }
    }

    private String rerankEndpoint(String baseUrl) {
        String base = baseUrl == null || baseUrl.isBlank()
                ? "https://dashscope.aliyuncs.com/api/v1"
                : baseUrl.strip();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/services/rerank/text-rerank/text-rerank";
    }
}
