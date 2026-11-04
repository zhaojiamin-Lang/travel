package com.travelguide.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelguide.service.AuthService;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Streamable HTTP 的 JSON 响应传输适配器。
 *
 * <p>服务端不主动推送消息，因此不开放 GET/SSE 通道；通知按协议返回 202。</p>
 */
@RestController
@RequestMapping(path = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
public final class McpController {

    private final McpServer server;
    private final AuthService authService;

    public McpController(McpServer server, AuthService authService) {
        this.server = server;
        this.authService = authService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> handle(
            @RequestBody JsonNode payload,
            @RequestHeader(
                    value = "X-Idempotency-Key",
                    required = false
            ) String idempotencyKey,
            HttpSession session
    ) {
        String userId = authService.ensureUser(session).id();
        JsonNode response = idempotencyKey == null || idempotencyKey.isBlank()
                ? ToolExecutionContext.runAs(userId, () -> server.handle(payload))
                : ToolExecutionContext.runAs(
                        userId,
                        idempotencyKey,
                        () -> server.handle(payload)
                );
        return response == null
                ? ResponseEntity.status(HttpStatus.ACCEPTED).build()
                : ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<JsonNode> malformedJson() {
        return ResponseEntity.badRequest().body(server.parseError());
    }
}
