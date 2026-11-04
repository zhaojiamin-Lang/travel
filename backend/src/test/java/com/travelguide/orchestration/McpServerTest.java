package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.service.AuthService;
import com.travelguide.web.ApiDtos.UserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpServerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpServer server;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new FailingTool());
        server = new McpServer(registry, objectMapper);
        AuthService authService = mock(AuthService.class);
        when(authService.ensureUser(any())).thenReturn(new UserView(
                "user_test",
                null,
                "测试用户",
                true
        ));
        mockMvc = MockMvcBuilders.standaloneSetup(
                new McpController(server, authService)
        ).build();
    }

    @Test
    void negotiatesProtocolAndAdvertisesTools() throws Exception {
        JsonNode initialized = server.handle(json("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "test-client", "version": "1.0"}
                  }
                }
                """));

        assertThat(initialized.path("result").path("protocolVersion").asText())
                .isEqualTo("2024-11-05");
        assertThat(initialized.path("result").path("capabilities")
                .path("tools").path("listChanged").asBoolean()).isFalse();

        JsonNode listed = server.handle(json("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """));
        JsonNode tools = listed.path("result").path("tools");

        assertThat(tools.size()).isEqualTo(2);
        assertThat(tools.get(0).path("name").asText()).isEqualTo("test.echo");
        assertThat(tools.get(0).path("inputSchema").path("type").asText())
                .isEqualTo("object");
        assertThat(tools.get(0).path("annotations").path("readOnlyHint").asBoolean())
                .isTrue();
    }

    @Test
    void executesTypedToolAndReturnsMcpContent() throws Exception {
        JsonNode response = server.handle(json("""
                {
                  "jsonrpc": "2.0",
                  "id": "call-1",
                  "method": "tools/call",
                  "params": {
                    "name": "test.echo",
                    "arguments": {"message": "hello"}
                  }
                }
                """));

        assertThat(response.path("id").asText()).isEqualTo("call-1");
        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("{\"value\":\"HELLO\"}");
    }

    @Test
    void reportsToolFailuresInResultAndProtocolFailuresInError() throws Exception {
        JsonNode toolFailure = server.handle(json("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "tools/call",
                  "params": {"name": "test.fail", "arguments": {}}
                }
                """));
        JsonNode unknownMethod = server.handle(json("""
                {"jsonrpc":"2.0","id":2,"method":"unknown","params":{}}
                """));

        assertThat(toolFailure.path("result").path("isError").asBoolean()).isTrue();
        assertThat(toolFailure.path("result").path("content").get(0).path("text").asText())
                .isEqualTo("deliberate failure");
        assertThat(unknownMethod.path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(unknownMethod.path("id").asInt()).isEqualTo(2);
    }

    @Test
    void suppressesNotificationResponsesAndSupportsBatches() throws Exception {
        JsonNode notification = json("""
                {"jsonrpc":"2.0","method":"notifications/initialized","params":{}}
                """);
        JsonNode batch = json("""
                [
                  {"jsonrpc":"2.0","method":"notifications/initialized","params":{}},
                  {"jsonrpc":"2.0","id":3,"method":"ping","params":{}}
                ]
                """);

        assertThat(server.handle(notification)).isNull();
        JsonNode response = server.handle(batch);
        assertThat(response.isArray()).isTrue();
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.get(0).path("id").asInt()).isEqualTo(3);
        assertThat(response.get(0).path("result").isObject()).isTrue();
        assertThat(response.get(0).path("result").size()).isZero();
    }

    @Test
    void exposesTheProtocolOverHttp() throws Exception {
        mockMvc.perform(post("/mcp")
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "jsonrpc": "2.0",
                                  "id": 1,
                                  "method": "initialize",
                                  "params": {
                                    "protocolVersion": "2025-06-18",
                                    "capabilities": {},
                                    "clientInfo": {"name": "http-test", "version": "1.0"}
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));

        mockMvc.perform(post("/mcp")
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jsonrpc":"2.0","method":"notifications/initialized"}
                                """))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/mcp")
                        .session(new MockHttpSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private record EchoInput(String message) {
    }

    private static final class EchoTool implements Tool<EchoInput, Map<String, String>> {

        @Override
        public String name() {
            return "test.echo";
        }

        @Override
        public String description() {
            return "Echo input";
        }

        @Override
        public ToolInputSchema inputSchema() {
            return ToolInputSchema.object(
                    Map.of("message", ToolInputSchema.Property.string("Message")),
                    "message"
            );
        }

        @Override
        public Class<EchoInput> inputType() {
            return EchoInput.class;
        }

        @Override
        public Map<String, String> execute(EchoInput input) {
            return Map.of("value", input.message().toUpperCase());
        }
    }

    private static final class FailingTool implements Tool<Map<String, Object>, Object> {

        @Override
        public String name() {
            return "test.fail";
        }

        @Override
        public String description() {
            return "Fail deliberately";
        }

        @Override
        public ToolInputSchema inputSchema() {
            return ToolInputSchema.object(Map.of());
        }

        @Override
        public Class<Map<String, Object>> inputType() {
            @SuppressWarnings("unchecked")
            Class<Map<String, Object>> type = (Class<Map<String, Object>>) (Class<?>) Map.class;
            return type;
        }

        @Override
        public boolean readOnly() {
            return false;
        }

        @Override
        public Object execute(Map<String, Object> input) {
            throw new IllegalStateException("deliberate failure");
        }
    }
}
