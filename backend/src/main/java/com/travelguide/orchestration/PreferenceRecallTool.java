package com.travelguide.orchestration;

import com.travelguide.memory.MemoryModels.RecalledMemory;
import com.travelguide.memory.MemoryService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 包装 MemoryService 的只读偏好召回能力。
 */
@Component
public class PreferenceRecallTool
        implements Tool<PreferenceRecallTool.Input, List<RecalledMemory>> {

    public static final String NAME = "preference.recall";

    private final MemoryService memoryService;

    public PreferenceRecallTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "召回用户当前有效的长期旅行偏好。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "type",
                        ToolInputSchema.Property.string("可选偏好类型"),
                        "now",
                        ToolInputSchema.Property.string("召回基准时间"),
                        "limit",
                        ToolInputSchema.Property.integer("最大返回数量")
                ),
                "now",
                "limit"
        );
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public List<RecalledMemory> execute(Input input) {
        return memoryService.recall(
                ToolExecutionContext.requireUserId(),
                input.type(),
                input.now(),
                input.limit()
        );
    }

    public record Input(
            String type,
            Instant now,
            int limit
    ) {
    }
}
