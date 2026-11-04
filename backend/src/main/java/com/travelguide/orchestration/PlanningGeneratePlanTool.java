package com.travelguide.orchestration;

import com.travelguide.service.PlanningService;
import com.travelguide.service.agent.IdempotentOperationService;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 包装 PlanningService 的确定性行程生成能力。
 */
@Component
public class PlanningGeneratePlanTool
        implements Tool<PlanningGeneratePlanTool.Input, PlanView> {

    public static final String NAME = "planning.generate_plan";

    private final PlanningService planningService;
    private final IdempotentOperationService idempotentOperations;

    public PlanningGeneratePlanTool(
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
        return "为已补齐必填槽位的需求生成并校验行程。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "requestId",
                        ToolInputSchema.Property.string("规划请求标识")
                ),
                "requestId"
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
    public PlanView execute(Input input) {
        return idempotentOperations.execute(
                ToolExecutionContext.requireOperationKey(),
                PlanView.class,
                () -> planningService.generatePlan(
                        input.requestId(),
                        ToolExecutionContext.requireUserId()
                )
        );
    }

    public record Input(String requestId) {
    }
}
