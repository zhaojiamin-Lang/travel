package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentType;
import com.travelguide.orchestration.OrchestrationModels.GuideResult;
import com.travelguide.web.ApiDtos.AskResponse;
import org.springframework.stereotype.Service;

/**
 * 现场导游 Agent。
 *
 * <p>只处理当前景点上下文中的讲解和现场追问，复用现有定位匹配与有证据约束的回答链路。
 * 导游 Agent 不创建或静默修改整份行程。</p>
 */
@Service
public class GuideAgent implements Agent {

    private final Tool<GuideAskTool.Input, AskResponse> askTool;

    public GuideAgent(ToolRegistry toolRegistry) {
        this.askTool = toolRegistry.get(GuideAskTool.NAME);
    }

    @Override
    public AgentType type() {
        return AgentType.GUIDE;
    }

    @Override
    public GuideResult execute(AgentInvocation invocation) {
        if (!hasText(invocation.guideSessionId())) {
            return new GuideResult(
                    null,
                    "请先打开现场导游并选择当前景点，我再为你讲解或推荐附近内容。",
                    true
            );
        }

        // 导游回答必须绑定当前会话景点；用户偏好由同一 MemoryStore 在回答链路中召回。
        AskResponse answer = askTool.execute(
                new GuideAskTool.Input(
                        invocation.guideSessionId(),
                        invocation.message()
                )
        );
        return new GuideResult(answer, answer.answer(), false);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
