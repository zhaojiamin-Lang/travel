package com.travelguide.orchestration;

import com.travelguide.service.PlanningService;
import com.travelguide.service.agent.IdempotentOperationService;
import com.travelguide.web.ApiDtos.DraftRequest;
import com.travelguide.web.ApiDtos.DraftResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 包装 PlanningService 的需求草稿创建能力。
 */
@Component
public class PlanningCreateDraftTool
        implements Tool<PlanningCreateDraftTool.Input, DraftResponse> {

    public static final String NAME = "planning.create_draft";

    private final PlanningService planningService;
    private final IdempotentOperationService idempotentOperations;

    public PlanningCreateDraftTool(
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
        return "根据自然语言创建旅行需求草稿。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "request",
                        ToolInputSchema.Property.object("旅行需求草稿")
                ),
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
                () -> planningService.createDraft(
                        input.request(),
                        ToolExecutionContext.requireUserId()
                )
        );
    }

    public record Input(DraftRequest request) {
    }
}
