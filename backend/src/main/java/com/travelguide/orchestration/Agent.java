package com.travelguide.orchestration;

import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentResult;
import com.travelguide.orchestration.OrchestrationModels.AgentType;

/**
 * 业务 Agent 的最小执行契约。
 *
 * <p>Agent 只消费不可变调用上下文并返回结构化结果，不直接控制其他 Agent，
 * 从而把跨 Agent 的执行顺序、最多执行次数和终止条件集中到编排器中。</p>
 */
public interface Agent {

    AgentType type();

    AgentResult execute(AgentInvocation invocation);
}
