package com.travelguide.orchestration;

import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 包装 MemoryService 的显式偏好写入能力。
 */
@Component
public class PreferenceRememberTool
        implements Tool<PreferenceRememberTool.Input, PreferenceSignal> {

    public static final String NAME = "preference.remember";

    private final MemoryService memoryService;

    public PreferenceRememberTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "保存用户明确表达的长期旅行偏好。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "type",
                        ToolInputSchema.Property.string("偏好类型"),
                        "key",
                        ToolInputSchema.Property.string("偏好键"),
                        "value",
                        ToolInputSchema.Property.string("偏好值"),
                        "sourceRunId",
                        ToolInputSchema.Property.string("产生该偏好的编排运行标识"),
                        "now",
                        ToolInputSchema.Property.string("写入时间")
                ),
                "type",
                "key",
                "value",
                "sourceRunId",
                "now"
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
    public PreferenceSignal execute(Input input) {
        return memoryService.rememberExplicit(
                ToolExecutionContext.requireUserId(),
                input.type(),
                input.key(),
                input.value(),
                input.sourceRunId(),
                input.now()
        );
    }

    public record Input(
            String type,
            String key,
            String value,
            String sourceRunId,
            Instant now
    ) {
    }
}
