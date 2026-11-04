package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.ExecuteRequest;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationCommand;
import com.travelguide.orchestration.OrchestrationModels.OrchestrationResponse;
import com.travelguide.orchestration.OrchestrationModels.RunStatus;
import com.travelguide.service.AuthService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 多 Agent 统一入口。
 *
 * <p>用户身份由现有会话服务补齐，客户端只需要提供当前业务上下文标识。</p>
 */
@RestController
@RequestMapping("/api/orchestration")
public class MultiAgentController {

    private final MultiAgentOrchestrator orchestrator;
    private final AuthService authService;

    public MultiAgentController(
            MultiAgentOrchestrator orchestrator,
            AuthService authService
    ) {
        this.orchestrator = orchestrator;
        this.authService = authService;
    }

    @PostMapping("/runs")
    public OrchestrationResponse execute(
            @Valid @RequestBody ExecuteRequest request,
            @RequestHeader(value = "X-Run-Id", required = false) String runId,
            HttpSession session
    ) {
        String userId = authService.ensureUser(session).id();
        return orchestrator.execute(
                new OrchestrationCommand(userId, request),
                runId
        );
    }

    @PostMapping("/runs/{runId}/resume")
    public OrchestrationResponse resume(
            @PathVariable String runId,
            @Valid @RequestBody ExecuteRequest request,
            HttpSession session
    ) {
        String userId = authService.ensureUser(session).id();
        return orchestrator.resume(
                runId,
                new OrchestrationCommand(userId, request)
        );
    }

    @GetMapping("/runs/{runId}")
    public RunStatus status(
            @PathVariable String runId,
            HttpSession session
    ) {
        String userId = authService.ensureUser(session).id();
        return orchestrator.status(runId, userId);
    }
}
