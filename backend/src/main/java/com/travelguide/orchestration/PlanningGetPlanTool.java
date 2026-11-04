package com.travelguide.orchestration;

import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 包装 PlanningService 的行程查询能力。
 */
@Component
public class PlanningGetPlanTool
        implements Tool<PlanningGetPlanTool.Input, PlanView> {

    public static final String NAME = "planning.get_plan";

    private final PlanningService planningService;

    public PlanningGetPlanTool(PlanningService planningService) {
        this.planningService = planningService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "按行程标识读取现有行程详情。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "planId",
                        ToolInputSchema.Property.string("行程标识")
                ),
                "planId"
        );
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public PlanView execute(Input input) {
        return planningService.getPlan(
                input.planId(),
                ToolExecutionContext.requireUserId()
        );
    }

    public record Input(String planId) {
    }
}
