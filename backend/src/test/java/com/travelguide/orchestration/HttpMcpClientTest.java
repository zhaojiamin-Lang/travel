package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class HttpMcpClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicBoolean exposeTool = new AtomicBoolean(true);
    private HttpServer server;
    private URI endpoint;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void discoversCallsAndUnregistersExternalTools() throws Exception {
        HttpMcpClient client = new HttpMcpClient(
                endpoint,
                Map.of(),
                Duration.ofSeconds(2),
                objectMapper
        );

        client.initialize();
        assertThat(client.protocolVersion()).isEqualTo(McpServer.LATEST_PROTOCOL_VERSION);
        assertThat(client.listTools()).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("remote.echo");
            assertThat(tool.inputSchema().path("properties").path("city").path("type").asText())
                    .isEqualTo("string");
            assertThat(tool.readOnly()).isFalse();
        });
        assertThat(client.callTool(
                "remote.echo",
                objectMapper.readTree("{\"city\":\"上海\"}")
        ).path("content").get(0).path("text").asText()).contains("上海");

        ToolRegistry registry = new ToolRegistry();
        ExternalMcpTools discovery = new ExternalMcpTools(registry, objectMapper);
        ExternalMcpTools.Server config = new ExternalMcpTools.Server();
        config.setUrl(endpoint);
        config.setTimeoutSeconds(2);
        discovery.setServers(Map.of("remote", config));

        discovery.refresh();
        assertThat(registry.find("mcp.remote.remote.echo")).isPresent();

        exposeTool.set(false);
        discovery.refresh();
        assertThat(registry.find("mcp.remote.remote.echo")).isEmpty();
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonNode request = objectMapper.readTree(exchange.getRequestBody());
        if (!request.has("id")) {
            respond(exchange, 202, "");
            return;
        }

        long id = request.path("id").asLong();
        String method = request.path("method").asText();
        JsonNode result = switch (method) {
            case "initialize" -> objectMapper.createObjectNode().put(
                    "protocolVersion",
                    McpServer.LATEST_PROTOCOL_VERSION
            );
            case "tools/list" -> toolsResult();
            case "tools/call" -> callResult(request.path("params").path("arguments"));
            default -> throw new IllegalStateException("Unexpected method " + method);
        };
        var response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.set("result", result);
        respond(exchange, 200, objectMapper.writeValueAsString(response));
    }

    private JsonNode toolsResult() {
        var result = objectMapper.createObjectNode();
        var tools = result.putArray("tools");
        if (exposeTool.get()) {
            var tool = tools.addObject();
            tool.put("name", "remote.echo");
            tool.put("description", "Remote echo");
            var schema = tool.putObject("inputSchema");
            schema.put("type", "object");
            schema.putObject("properties").putObject("city").put("type", "string");
            tool.putObject("annotations").put("readOnlyHint", false);
        }
        return result;
    }

    private JsonNode callResult(JsonNode arguments) {
        var result = objectMapper.createObjectNode();
        result.putArray("content")
                .addObject()
                .put("type", "text")
                .put("text", arguments.toString());
        result.put("isError", false);
        return result;
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
