package com.travelguide.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.travelguide.harness.resilience.ResilienceDefaults;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Streamable HTTP MCP Client。
 */
public final class HttpMcpClient implements McpClient {

    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";

    private final URI endpoint;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker;
    private final AtomicLong requestIds = new AtomicLong();

    private volatile String protocolVersion;
    private volatile String sessionId;

    public HttpMcpClient(
            URI endpoint,
            Map<String, String> headers,
            Duration timeout,
            ObjectMapper objectMapper
    ) {
        this.endpoint = endpoint;
        this.headers = Map.copyOf(headers == null ? Map.of() : headers);
        this.timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .build();
        this.circuitBreaker = ResilienceDefaults.circuitBreaker("mcp-client");
    }

    @Override
    public synchronized void initialize() {
        if (protocolVersion != null) {
            return;
        }

        ObjectNode params = objectMapper.createObjectNode();
        params.put("protocolVersion", McpServer.LATEST_PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo")
                .put("name", "travel-guide-mcp-client")
                .put("version", "0.1.0");

        JsonNode result = request("initialize", params);
        protocolVersion = requiredText(result, "protocolVersion");
        notifyServer("notifications/initialized");
    }

    @Override
    public String protocolVersion() {
        return protocolVersion;
    }

    @Override
    public List<RemoteTool> listTools() {
        initialize();
        LinkedHashMap<String, RemoteTool> tools = new LinkedHashMap<>();
        Set<String> seenCursors = new LinkedHashSet<>();
        String cursor = null;

        do {
            ObjectNode params = objectMapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params);
            result.path("tools").forEach(tool -> addTool(tools, tool));
            String next = result.path("nextCursor").asText("");
            cursor = next.isBlank() ? null : next;
            if (cursor != null && !seenCursors.add(cursor)) {
                throw new McpClientException("MCP tools/list 返回了循环 cursor");
            }
        } while (cursor != null);

        return List.copyOf(tools.values());
    }

    @Override
    public JsonNode callTool(String name, JsonNode arguments) {
        initialize();
        ObjectNode params = objectMapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? objectMapper.createObjectNode() : arguments);

        JsonNode result = request("tools/call", params);
        if (result.path("isError").asBoolean(false)) {
            String message = result.path("content").findValuesAsText("text").stream()
                    .findFirst()
                    .orElse("Remote MCP tool failed");
            throw new McpClientException(message);
        }
        return result;
    }

    private void addTool(Map<String, RemoteTool> tools, JsonNode tool) {
        String name = tool.path("name").asText("");
        if (name.isBlank()) {
            throw new McpClientException("MCP 工具缺少 name");
        }
        if (tools.putIfAbsent(name, new RemoteTool(
                name,
                tool.path("description").asText(""),
                tool.path("inputSchema").isObject()
                        ? tool.path("inputSchema").deepCopy()
                        : objectMapper.createObjectNode().put("type", "object"),
                // 未声明能力时按非只读处理，避免误放行写操作。
                tool.path("annotations").path("readOnlyHint").asBoolean(false)
        )) != null) {
            throw new McpClientException("MCP Server 返回了重复工具: " + name);
        }
    }

    private void notifyServer(String method) {
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.putObject("params");
        send(message, false);
    }

    private JsonNode request(String method, JsonNode params) {
        long id = requestIds.incrementAndGet();
        ObjectNode message = objectMapper.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params == null ? objectMapper.createObjectNode() : params);
        return parseResponse(send(message, true), id);
    }

    private HttpResponse<String> send(JsonNode message, boolean expectsResponse) {
        try {
            Callable<HttpResponse<String>> call = CircuitBreaker.decorateCallable(
                    circuitBreaker,
                    () -> sendOnce(message, expectsResponse)
            );
            return call.call();
        } catch (CallNotPermittedException exception) {
            throw new McpClientException("MCP 调用已熔断", exception, false);
        } catch (Exception exception) {
            if (exception instanceof McpClientException clientException) {
                throw clientException;
            }
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new McpClientException("MCP 请求失败", exception, true);
        }
    }

    private HttpResponse<String> sendOnce(JsonNode message, boolean expectsResponse) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", JSON)
                .header("Accept", JSON + ", " + SSE)
                .POST(HttpRequest.BodyPublishers.ofString(json(message)));
        headers.forEach(builder::header);
        if (sessionId != null) {
            builder.header("Mcp-Session-Id", sessionId);
        }
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }

        try {
            HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new McpClientException(
                        "MCP HTTP " + response.statusCode() + ": " + response.body(),
                        null,
                        response.statusCode() == 429 || response.statusCode() >= 500
                );
            }
            response.headers().firstValue("Mcp-Session-Id")
                    .or(() -> response.headers().firstValue("MCP-Session-Id"))
                    .ifPresent(value -> sessionId = value);
            if (expectsResponse && response.body().isBlank()) {
                throw new McpClientException("MCP Server 返回了空响应");
            }
            return response;
        } catch (IOException exception) {
            throw new McpClientException("MCP 请求失败: " + endpoint, exception, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new McpClientException("MCP 请求被中断", exception);
        }
    }

    private JsonNode parseResponse(HttpResponse<String> response, long id) {
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            JsonNode message = contentType.startsWith(SSE)
                    ? parseSse(response.body(), id)
                    : objectMapper.readTree(response.body());
            if (message == null) {
                throw new McpClientException("MCP SSE 响应中没有 JSON-RPC 结果");
            }
            if (message.has("error")) {
                throw new McpClientException("MCP 错误 "
                        + message.path("error").path("code").asInt()
                        + ": "
                        + message.path("error").path("message").asText());
            }
            if (!message.has("result")) {
                throw new McpClientException("MCP 响应缺少 result");
            }
            return message.path("result");
        } catch (JsonProcessingException exception) {
            throw new McpClientException("MCP 响应不是合法 JSON", exception);
        }
    }

    private JsonNode parseSse(String body, long id) throws JsonProcessingException {
        JsonNode matched = null;
        for (String event : body.split("(?:\\r?\\n){2,}")) {
            String data = event.lines()
                    .filter(line -> line.startsWith("data:"))
                    .map(line -> line.substring(5).trim())
                    .collect(Collectors.joining("\n"));
            if (data.isBlank()) {
                continue;
            }
            JsonNode candidate = objectMapper.readTree(data);
            if (candidate.path("id").asLong(Long.MIN_VALUE) == id) {
                matched = candidate;
            }
        }
        return matched;
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("");
        if (value.isBlank()) {
            throw new McpClientException("MCP 响应缺少 " + field);
        }
        return value;
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new McpClientException("MCP 请求无法序列化", exception);
        }
    }

    public static final class McpClientException extends RuntimeException {
        private final boolean retryable;

        public McpClientException(String message) {
            super(message);
            this.retryable = false;
        }

        public McpClientException(String message, Throwable cause) {
            super(message, cause);
            this.retryable = false;
        }

        public McpClientException(
                String message,
                Throwable cause,
                boolean retryable
        ) {
            super(message, cause);
            this.retryable = retryable;
        }

        public boolean retryable() {
            return retryable;
        }
    }
}
