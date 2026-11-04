package com.travelguide.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.travelguide.rag.RagModels.DocumentMetadata;
import com.travelguide.rag.RagModels.DocType;
import com.travelguide.rag.RagModels.Intent;
import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;
import com.travelguide.rag.RagModels.RetrievalChannel;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DashScopeRerankerTest {

    @Test
    void usesRemoteRelevanceScoresAndPreservesCitationChunks() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/services/rerank/text-rerank/text-rerank", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {
                      "output": {
                        "results": [
                          {"index": 1, "relevance_score": 0.91},
                          {"index": 0, "relevance_score": 0.12}
                        ]
                      }
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var reranker = new DashScopeReranker(
                    "http://127.0.0.1:" + port + "/api/v1",
                    "test-key",
                    "gte-rerank-v2",
                    new ObjectMapper(),
                    new HeuristicReranker()
            );

            var result = reranker.rerank(queryPlan(), candidates(), 2);

            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(result).extracting(item -> item.chunk().chunkId())
                    .containsExactly("c2", "c1");
            assertThat(result.get(0).score()).isEqualTo(0.91);
        } finally {
            server.stop(0);
        }
    }

    private QueryPlan queryPlan() {
        return new QueryPlan(
                "上海博物馆门票多少钱",
                "上海博物馆人民广场馆门票多少钱",
                Intent.TICKET,
                List.of("poi:shanghai-museum"),
                List.of("shanghai-museum"),
                List.of("TICKET"),
                "上海",
                "上海博物馆人民广场馆",
                Instant.now(),
                List.of(),
                true,
                "TEST"
        );
    }

    private List<RankedChunk> candidates() {
        DocumentMetadata metadata = new DocumentMetadata(
                "kb.poi.shanghai-museum.visit",
                DocType.VISIT,
                2,
                "上海博物馆人民广场馆游览说明",
                "上海",
                "黄浦",
                List.of("poi:shanghai-museum"),
                List.of("shanghai-museum"),
                List.of("TICKET"),
                List.of("上海博物馆"),
                "OFFICIAL_STATIC",
                "上海博物馆",
                "https://www.shanghaimuseum.net/",
                "CITED",
                "DRAFT",
                null,
                null,
                null,
                null,
                null
        );
        return List.of(
                new RankedChunk(
                        new KnowledgeChunk("c1", "p1", metadata, "门票与票种",
                                List.of("TICKET"), "成人票 30 元", "成人票 30 元"),
                        0.03,
                        Map.of(RetrievalChannel.BM25, 1)
                ),
                new RankedChunk(
                        new KnowledgeChunk("c2", "p1", metadata, "门票与票种",
                                List.of("TICKET"), "特展期间需预约购票", "特展期间需预约购票"),
                        0.02,
                        Map.of(RetrievalChannel.BM25, 2)
                )
        );
    }
}
