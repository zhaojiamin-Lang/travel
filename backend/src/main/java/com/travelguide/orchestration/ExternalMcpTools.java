package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;

/**
 * 外部 MCP 工具的发现、刷新与 Registry 同步。
 */
@Component
@ConfigurationProperties(prefix = "travel.mcp.client")
public class ExternalMcpTools implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExternalMcpTools.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();

    private boolean enabled = true;
    private Map<String, Server> servers = new LinkedHashMap<>();

    public ExternalMcpTools(ToolRegistry toolRegistry, ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        refresh();
    }

    @Scheduled(
            initialDelayString = "${travel.mcp.client.refresh-interval-ms:60000}",
            fixedDelayString = "${travel.mcp.client.refresh-interval-ms:60000}"
    )
    public synchronized void refresh() {
        if (!enabled) {
            clients.keySet().forEach(this::removeServer);
            clients.clear();
            return;
        }

        Set<String> knownServers = new HashSet<>(clients.keySet());
        knownServers.addAll(servers.keySet());
        knownServers.stream()
                .filter(name -> !servers.containsKey(name))
                .forEach(this::removeServer);
        servers.forEach(this::refreshServer);
    }

    private void refreshServer(String serverName, Server server) {
        try {
            validate(serverName, server);
            McpClient client = new HttpMcpClient(
                    server.getUrl(),
                    server.getHeaders(),
                    Duration.ofSeconds(server.getTimeoutSeconds()),
                    objectMapper
            );
            // 先完成握手，再按命名空间原子替换远端工具快照。
            client.initialize();
            List<McpClient.RemoteTool> discovered = client.listTools();
            String prefix = prefix(serverName);
            List<Tool<?, ?>> proxies = discovered.stream()
                    .<Tool<?, ?>>map(tool -> proxy(
                            prefix,
                            serverName,
                            tool,
                            client
                    ))
                    .toList();
            toolRegistry.replacePrefix(prefix, proxies);
            clients.put(serverName, client);
            LOGGER.info("MCP Client {} 发现 {} 个工具，协议 {}",
                    serverName, proxies.size(), client.protocolVersion());
        } catch (RuntimeException exception) {
            // 刷新失败保留上一版工具快照，避免瞬时网络故障清空 Agent 能力。
            LOGGER.warn("MCP Client {} 刷新失败: {}", serverName, exception.getMessage());
        }
    }

    private void removeServer(String serverName) {
        toolRegistry.replacePrefix(prefix(serverName), List.of());
        clients.remove(serverName);
    }

    private void validate(String serverName, Server server) {
        if (!serverName.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("MCP server 名称只能包含字母、数字、_ 和 -");
        }
        if (server.getUrl() == null
                || !Set.of("http", "https").contains(server.getUrl().getScheme())) {
            throw new IllegalArgumentException("MCP server URL 必须是 http/https");
        }
    }

    private String prefix(String serverName) {
        return "mcp." + serverName + ".";
    }

    private Tool<?, ?> proxy(
            String prefix,
            String serverName,
            McpClient.RemoteTool tool,
            McpClient client
    ) {
        return new RemoteTool(
                prefix + tool.name(),
                serverName,
                tool,
                client,
                objectMapper
        );
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, Server> getServers() {
        return servers;
    }

    public void setServers(Map<String, Server> servers) {
        this.servers = servers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(servers);
    }

    public static class Server {
        private URI url;
        private Map<String, String> headers = new LinkedHashMap<>();
        private long timeoutSeconds = 10;

        public URI getUrl() {
            return url;
        }

        public void setUrl(URI url) {
            this.url = url;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        }

        public long getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(long timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private static final class RemoteTool implements Tool<Map<String, Object>, JsonNode> {

        private static final Class<Map<String, Object>> ARGUMENTS = argumentsType();

        private final String exposedName;
        private final McpClient.RemoteTool remote;
        private final McpClient client;
        private final ObjectMapper objectMapper;
        private final Retry retry;

        private RemoteTool(
                String exposedName,
                String serverName,
                McpClient.RemoteTool remote,
                McpClient client,
                ObjectMapper objectMapper
        ) {
            this.exposedName = exposedName;
            this.remote = new McpClient.RemoteTool(
                    remote.name(),
                    remote.description().isBlank()
                            ? "来自 MCP Server " + serverName
                            : remote.description(),
                    remote.inputSchema(),
                    remote.readOnly()
            );
            this.client = client;
            this.objectMapper = objectMapper;
            this.retry = Retry.of(
                    exposedName,
                    RetryConfig.custom()
                            .maxAttempts(3)
                            .intervalFunction(
                                    IntervalFunction.ofExponentialBackoff(
                                            Duration.ofMillis(100),
                                            2.0
                                    )
                            )
                            .retryOnException(error ->
                                    error instanceof HttpMcpClient.McpClientException clientError
                                            && clientError.retryable()
                            )
                            .build()
            );
        }

        @Override
        public String name() {
            return exposedName;
        }

        @Override
        public String description() {
            return remote.description();
        }

        @Override
        public Object inputSchema() {
            return remote.inputSchema();
        }

        @Override
        public Class<Map<String, Object>> inputType() {
            return ARGUMENTS;
        }

        @Override
        public boolean readOnly() {
            return remote.readOnly();
        }

        @Override
        public JsonNode execute(Map<String, Object> input) {
            // 保留 JSON 原貌，避免代理层把远端 Schema 和参数重新建模。
            Callable<JsonNode> call = () -> client.callTool(
                    remote.name(),
                    objectMapper.valueToTree(input)
            );
            try {
                return remote.readOnly()
                        ? Retry.decorateCallable(retry, call).call()
                        : call.call();
            } catch (Exception exception) {
                if (exception instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new HttpMcpClient.McpClientException(
                        "MCP 工具调用失败",
                        exception,
                        true
                );
            }
        }

        @SuppressWarnings("unchecked")
        private static Class<Map<String, Object>> argumentsType() {
            return (Class<Map<String, Object>>) (Class<?>) Map.class;
        }
    }
}
