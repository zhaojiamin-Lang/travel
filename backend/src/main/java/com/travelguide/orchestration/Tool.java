package com.travelguide.orchestration;

/**
 * Agent 可调用的最小工具契约。
 *
 * <p>工具只暴露稳定的名称、说明、输入结构与执行入口。Agent 通过
 * {@link ToolRegistry} 获取工具，不直接绑定业务 Service，从而为后续接入
 * 外部 MCP 工具保留统一边界。</p>
 *
 * @param <I> 工具输入
 * @param <O> 工具输出
 */
public interface Tool<I, O> {

    String name();

    String description();

    Object inputSchema();

    Class<I> inputType();

    default boolean readOnly() {
        return true;
    }

    O execute(I input);
}
