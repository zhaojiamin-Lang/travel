package com.travelguide.orchestration;

import com.travelguide.service.GuideService;
import com.travelguide.web.ApiDtos.AskResponse;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 包装 GuideService 的现场问答能力。
 */
@Component
public class GuideAskTool implements Tool<GuideAskTool.Input, AskResponse> {

    public static final String NAME = "guide.ask";

    private final GuideService guideService;

    public GuideAskTool(GuideService guideService) {
        this.guideService = guideService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "结合当前导游会话、景点和用户偏好回答问题。";
    }

    @Override
    public ToolInputSchema inputSchema() {
        return ToolInputSchema.object(
                Map.of(
                        "sessionId",
                        ToolInputSchema.Property.string("当前导游会话标识"),
                        "question",
                        ToolInputSchema.Property.string("用户问题")
                ),
                "sessionId",
                "question"
        );
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public AskResponse execute(Input input) {
        return guideService.ask(
                input.sessionId(),
                input.question(),
                ToolExecutionContext.requireUserId()
        );
    }

    public record Input(
            String sessionId,
            String question
    ) {
    }
}
