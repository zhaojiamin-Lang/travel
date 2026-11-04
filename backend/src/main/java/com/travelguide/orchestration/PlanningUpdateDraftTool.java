package com.travelguide.orchestration;

import com.travelguide.service.PlanningService;
import com.travelguide.service.agent.IdempotentOperationService;
import com.travelguide.web.ApiDtos.DraftResponse;
import com.travelguide.web.ApiDtos.NaturalLanguageUpdateRequest;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 包装 PlanningService 的自然语言草稿更新能力。
 */
@Component
public class PlanningUpdateDraftTool
        implements Tool<PlanningUpdateDraftTool.Input, DraftResponse> {

    public static final String NAME = "planning.update_draft";

    private final PlanningService planningService;
    private final IdempotentOperationService idempotentOperations;

    public PlanningUpdateDraftTool(
            PlanningService planningService,
            IdempotentOperationService idempotentOperations
    ) {
        this.planningService = planningService;
        this.idempotentOperations = idempotentOperations;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "根据自然语言更新现有旅行需求草稿。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "requestId",
                        ToolInputSchema.Property.string("规划请求标识"),
                        "request",
                        ToolInputSchema.Property.object("自然语言更新内容")
                ),
                "requestId",
                "request"
        );
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public DraftResponse execute(Input input) {
        return idempotentOperations.execute(
                ToolExecutionContext.requireOperationKey(),
                DraftResponse.class,
                () -> planningService.updateFromNaturalLanguage(
                        input.requestId(),
                        input.request(),
                        ToolExecutionContext.requireUserId()
                )
        );
    }

    public record Input(
            String requestId,
            NaturalLanguageUpdateRequest request
    ) {
    }
}
