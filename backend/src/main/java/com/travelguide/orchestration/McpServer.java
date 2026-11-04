package com.travelguide.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * MCP 的传输无关协议核心。
 *
 * <p>该层负责 JSON-RPC 2.0 信封、MCP 生命周期和工具调用；HTTP、stdio
 * 等传输只需要转发 JSON 文档，不复制协议语义。</p>
 */
@Component
public final class McpServer {

    public static final String LATEST_PROTOCOL_VERSION = "2025-06-18";

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServer.class);
    private static final Set<String> SUPPORTED_PROTOCOL_VERSIONS = Set.of(
            "2024-11-05",
            "2025-03-26",
            LATEST_PROTOCOL_VERSION
    );

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;

    public McpServer(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    /**
     * 处理单个 JSON-RPC 消息或批量消息。
     *
     * @return 响应文档；全部为通知或 batch 中没有请求时返回 {@code null}
     */
    public JsonNode handle(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return error(null, -32600, "Invalid Request");
        }
        if (payload.isArray()) {
            return handleBatch((ArrayNode) payload);
        }
        return handleMessage(payload);
    }

    public JsonNode parseError() {
        return error(null, -32700, "Parse error");
    }

    private JsonNode handleBatch(ArrayNode batch) {
        if (batch.isEmpty()) {
            return error(null, -32600, "Invalid Request");
        }
        ArrayNode responses = objectMapper.createArrayNode();
        batch.forEach(message -> addIfResponse(responses, handleMessage(message)));
        return responses.isEmpty() ? null : responses;
    }

    private JsonNode handleMessage(JsonNode message) {
        if (!message.isObject()) {
            return error(null, -32600, "Invalid Request");
        }

        boolean notification = !message.has("id");
        JsonNode id = message.get("id");
        if (!validId(id)) {
            return error(null, -32600, "Invalid Request");
        }
        if (!"2.0".equals(message.path("jsonrpc").asText())) {
            return error(id, -32600, "Invalid Request");
        }

        String method = message.path("method").asText("");
        if (method.isBlank()) {
            return error(id, -32600, "Invalid Request");
        }

        JsonNode params = message.get("params");
        try {
            return switch (method) {
                case "initialize" -> response(
                        notification,
                        id,
                        () -> success(initialize(params))
                );
                case "notifications/initialized", "notifications/cancelled" -> null;
                case "ping" -> response(
                        notification,
                        id,
                        () -> success(emptyResult())
                );
                case "tools/list" -> response(
                        notification,
                        id,
                        () -> success(listTools(params))
                );
                case "tools/call" -> response(
                        notification,
                        id,
                        () -> success(callTool(params))
                );
                default -> response(
                        notification,
                        id,
                        () -> protocolError(-32601, "Method not found: " + method)
                );
            };
        } catch (InvalidParamsException exception) {
            return response(
                    notification,
                    id,
                    () -> protocolError(-32602, exception.getMessage())
            );
        } catch (ToolExecutionException exception) {
            LOGGER.warn(
                    "MCP 工具 {} 执行失败: {}",
                    exception.toolName(),
                    exception.getCause().getMessage()
            );
            return response(
                    notification,
                    id,
                    () -> success(toolError(exception.getCause()))
            );
        } catch (RuntimeException exception) {
            LOGGER.error("MCP 请求处理失败", exception);
            return response(
                    notification,
                    id,
                    () -> protocolError(-32603, "Internal error")
            );
        }
    }

    private ObjectNode initialize(JsonNode params) {
        JsonNode fields = objectParams(params);
        String requestedVersion = requiredText(fields, "protocolVersion");
        String negotiatedVersion = SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)
                ? requestedVersion
                : LATEST_PROTOCOL_VERSION;

        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", negotiatedVersion);
        result.putObject("capabilities")
                .putObject("tools")
                .put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "travel-guide-mcp");
        serverInfo.put("version", "0.1.0");
        result.put(
                "instructions",
                "旅行规划、现场导游和长期偏好工具。用户当前明确要求优先于长期偏好。"
        );
        return result;
    }

    private ObjectNode listTools(JsonNode params) {
        objectParams(params);
        ArrayNode tools = objectMapper.createArrayNode();
        availableTools().stream()
                .sorted(Comparator.comparing(Tool::name))
                .map(this::toolDescriptor)
                .forEach(tools::add);

        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private ObjectNode toolDescriptor(Tool<?, ?> tool) {
        ObjectNode descriptor = objectMapper.createObjectNode();
        descriptor.put("name", tool.name());
        descriptor.put("description", tool.description());
        descriptor.set("inputSchema", objectMapper.valueToTree(tool.inputSchema()));
        descriptor.putObject("annotations")
                .put("readOnlyHint", tool.readOnly());
        return descriptor;
    }

    private ObjectNode callTool(JsonNode params) {
        JsonNode fields = objectParams(params);
        String name = requiredText(fields, "name");
        JsonNode arguments = fields.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = objectMapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            throw new InvalidParamsException("Tool arguments must be an object");
        }

        Tool<?, ?> tool = toolRegistry.find(name)
                .orElseThrow(() -> new InvalidParamsException("Unknown tool: " + name));
        Object result;
        try {
            result = execute(tool, arguments);
            LOGGER.info(
                    "工具调用成功 user={} tool={} readOnly={}",
                    userForAudit(),
                    tool.name(),
                    tool.readOnly()
            );
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new InvalidParamsException(
                    "Invalid arguments for tool " + name + ": " + exception.getMessage()
            );
        } catch (ToolExecutionException exception) {
            LOGGER.warn(
                    "工具调用失败 user={} tool={} readOnly={}",
                    userForAudit(),
                    tool.name(),
                    tool.readOnly()
            );
            throw exception;
        } catch (RuntimeException exception) {
            throw new ToolExecutionException(name, exception);
        }
        return toolSuccess(result);
    }

    private <I, O> O execute(Tool<I, O> tool, JsonNode arguments)
            throws JsonProcessingException {
        I input = objectMapper.treeToValue(arguments, tool.inputType());
        try {
            return tool.execute(input);
        } catch (RuntimeException exception) {
            throw new ToolExecutionException(tool.name(), exception);
        }
    }

    private ObjectNode toolSuccess(Object value) {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("content", textContent(serialize(value)));
        result.put("isError", false);
        return result;
    }

    private ObjectNode toolError(Throwable exception) {
        String message = exception.getMessage();
        ObjectNode result = objectMapper.createObjectNode();
        result.set(
                "content",
                textContent(message == null || message.isBlank() ? "Tool execution failed" : message)
        );
        result.put("isError", true);
        return result;
    }

    private ArrayNode textContent(String text) {
        ArrayNode content = objectMapper.createArrayNode();
        content.addObject()
                .put("type", "text")
                .put("text", text);
        return content;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("工具结果无法序列化", exception);
        }
    }

    private String userForAudit() {
        String userId = ToolExecutionContext.currentUserId();
        return userId == null ? "internal" : userId;
    }

    private List<Tool<?, ?>> availableTools() {
        return toolRegistry.isEnabled() ? toolRegistry.listAll() : List.of();
    }

    private ObjectNode success(JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("result", result);
        return response;
    }

    private ObjectNode emptyResult() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode protocolError(int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("error", error);
        return response;
    }

    private JsonNode response(
            boolean notification,
            JsonNode id,
            ProtocolResult supplier
    ) {
        if (notification) {
            return null;
        }
        JsonNode body = supplier.get();
        ((ObjectNode) body).set("id", id == null ? objectMapper.nullNode() : id.deepCopy());
        return body;
    }

    private JsonNode error(JsonNode id, int code, String message) {
        ObjectNode response = protocolError(code, message);
        response.set("id", id == null ? objectMapper.nullNode() : id.deepCopy());
        return response;
    }

    private void addIfResponse(ArrayNode responses, JsonNode response) {
        if (response != null) {
            responses.add(response);
        }
    }

    private JsonNode objectParams(JsonNode params) {
        if (params == null || params.isNull() || params.isMissingNode()) {
            return objectMapper.createObjectNode();
        }
        if (!params.isObject()) {
            throw new InvalidParamsException("params must be an object");
        }
        return params;
    }

    private String requiredText(JsonNode object, String field) {
        String value = object.path(field).asText("");
        if (value.isBlank()) {
            throw new InvalidParamsException("Missing required parameter: " + field);
        }
        return value;
    }

    private boolean validId(JsonNode id) {
        return id == null || id.isNull() || id.isTextual() || id.isNumber();
    }

    @FunctionalInterface
    private interface ProtocolResult {
        JsonNode get();
    }

    private static final class InvalidParamsException extends RuntimeException {
        private InvalidParamsException(String message) {
            super(message);
        }
    }

    private static final class ToolExecutionException extends RuntimeException {
        private final String toolName;

        private ToolExecutionException(String toolName, Throwable cause) {
            super(cause);
            this.toolName = toolName;
        }

        private String toolName() {
            return toolName;
        }
    }
}
