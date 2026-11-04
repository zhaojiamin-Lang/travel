package com.travelguide.fact;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态事实工具与仲裁器装配。没有工具 Bean 时仍提供空 Registry，查询返回 UNAVAILABLE。
 */
@Configuration
public class DynamicFactConfiguration {

    @Bean
    public TemporalFactArbiter temporalFactArbiter() {
        return new TemporalFactArbiter();
    }

    @Bean
    public DynamicFactService dynamicFactService(
            ObjectProvider<DynamicFactTool> tools,
            TemporalFactArbiter arbiter
    ) {
        return new DynamicFactService(
                new DynamicFactToolRegistry(tools.orderedStream().toList()),
                arbiter
        );
    }
}
