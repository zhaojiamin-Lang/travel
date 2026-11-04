package com.travelguide.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleEmbeddingProviderTest {

    @Test
    void sendsBatchRequestAndRestoresVectorOrder() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/compatible-mode/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8
            ));
            byte[] response = """
                    {
                      "data": [
                        {"index": 1, "embedding": [0.0, 1.0, 0.0]},
                        {"index": 0, "embedding": [1.0, 0.0, 0.0]}
                      ]
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
            var provider = new OpenAiCompatibleEmbeddingProvider(
                    "http://127.0.0.1:" + port + "/compatible-mode",
                    "test-key",
                    "text-embedding-v3",
                    new ObjectMapper()
            );

            var vectors = provider.embedAll(List.of("外滩", "豫园"));

            assertThat(authorization.get()).isEqualTo("Bearer test-key");
            assertThat(requestBody.get()).contains("\"model\":\"text-embedding-v3\"");
            assertThat(requestBody.get()).contains("\"外滩\"").contains("\"豫园\"");
            assertThat(vectors).hasSize(2);
            assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f, 0.0f);
            assertThat(vectors.get(1)).containsExactly(0.0f, 1.0f, 0.0f);
        } finally {
            server.stop(0);
        }
    }
}
