package com.travelguide.orchestration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 工具注册表。
 *
 * <p>所有已声明的 {@link Tool} Bean 在启动时注册，Agent 按稳定名称查找工具。
 * 与 {@code AiGateway} 一样，是否启用由配置控制并暴露 {@link #isEnabled()}，
 * 未配置时默认启用。</p>
 */
@Component
public class ToolRegistry {

    public static final String ENABLED_PROPERTY =
            "travel.orchestration.tools.enabled";

    private final AtomicReference<Map<String, Tool<?, ?>>> tools =
            new AtomicReference<>(Map.of());
    private final boolean enabled;

    public ToolRegistry() {
        this(List.of(), true);
    }

    @Autowired
    public ToolRegistry(
            List<Tool<?, ?>> discoveredTools,
            @Value("${travel.orchestration.tools.enabled:true}")
            boolean enabled
    ) {
        this.enabled = enabled;
        discoveredTools.forEach(this::register);
    }

    /**
     * 注册工具。重复名称会在装配阶段直接失败，避免运行时静默覆盖。
     */
    public synchronized void register(Tool<?, ?> tool) {
        validate(tool);
        Map<String, Tool<?, ?>> next = new LinkedHashMap<>(tools.get());
        Tool<?, ?> previous = next.putIfAbsent(tool.name(), tool);
        if (previous != null && previous != tool) {
            throw new IllegalStateException("工具名称重复: " + tool.name());
        }
        tools.set(next);
    }

    /**
     * 原子替换一个命名空间下的工具，供远端动态发现使用。
     */
    public synchronized void replacePrefix(String prefix, List<Tool<?, ?>> replacements) {
        Map<String, Tool<?, ?>> next = new LinkedHashMap<>(tools.get());
        next.keySet().removeIf(name -> name.startsWith(prefix));
        for (Tool<?, ?> tool : replacements) {
            validate(tool);
            if (!tool.name().startsWith(prefix)) {
                throw new IllegalArgumentException("工具不属于命名空间 " + prefix);
            }
            if (next.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalStateException("工具名称重复: " + tool.name());
            }
        }
        // 调用方只会看到替换前或替换后的完整快照。
        tools.set(next);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 按名称查找工具。禁用工具层时返回空，由调用方决定降级策略。
     */
    public Optional<Tool<?, ?>> find(String name) {
        if (!enabled) {
            return Optional.empty();
        }
        return Optional.ofNullable(tools.get().get(name));
    }

    /**
     * 按名称获取工具，并在调用方声明期望类型。
     */
    @SuppressWarnings("unchecked")
    public <I, O> Tool<I, O> get(String name) {
        return (Tool<I, O>) find(name)
                .orElseThrow(() -> new IllegalArgumentException(
                        enabled
                                ? "未注册工具: " + name
                                : "工具层已禁用，无法获取工具: " + name
                ));
    }

    /**
     * 列出启动时注册的全部工具，不受运行开关影响。
     */
    public List<Tool<?, ?>> listAll() {
        return List.copyOf(tools.get().values());
    }

    private void validate(Tool<?, ?> tool) {
        if (tool == null) {
            throw new IllegalArgumentException("工具不能为空");
        }
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
    }
}
