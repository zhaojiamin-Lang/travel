package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 外部 MCP Server 的最小客户端契约。
 */
public interface McpClient {

    void initialize();

    String protocolVersion();

    List<RemoteTool> listTools();

    JsonNode callTool(String name, JsonNode arguments);

    record RemoteTool(
            String name,
            String description,
            JsonNode inputSchema,
            boolean readOnly
    ) {
    }
}
