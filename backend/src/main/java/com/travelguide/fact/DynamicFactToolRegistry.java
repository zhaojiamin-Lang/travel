package com.travelguide.fact;

import com.travelguide.fact.FactModels.FactCollection;
import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactRecord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 动态事实工具注册表。
 *
 * <p>一个事实可以同时调用多个数据源，后续仲裁器负责比较来源和有效期。
 * 单个工具失败不会阻断其他来源。</p>
 */
public final class DynamicFactToolRegistry {

    private final List<DynamicFactTool> tools;

    public DynamicFactToolRegistry(List<DynamicFactTool> tools) {
        this.tools = List.copyOf(tools);
    }

    public FactCollection collect(FactQuery query) {
        List<FactRecord> facts = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (DynamicFactTool tool : tools) {
            if (!tool.supports(query)) {
                continue;
            }
            try {
                facts.addAll(tool.fetch(query));
            } catch (RuntimeException exception) {
                failures.put(tool.name(), exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage());
            }
        }
        return new FactCollection(facts, failures);
    }
}
