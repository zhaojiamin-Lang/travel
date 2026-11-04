package com.travelguide.fact;

import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactResolution;

/**
 * 动态事实统一门面：调用支持该事实的工具，再由仲裁器生成可消费结果。
 */
public final class DynamicFactService {

    private final DynamicFactToolRegistry registry;
    private final TemporalFactArbiter arbiter;

    public DynamicFactService(
            DynamicFactToolRegistry registry,
            TemporalFactArbiter arbiter
    ) {
        this.registry = registry;
        this.arbiter = arbiter;
    }

    public FactResolution resolve(FactQuery query) {
        return arbiter.resolve(query, registry.collect(query));
    }
}
