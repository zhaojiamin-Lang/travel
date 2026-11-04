package com.travelguide.orchestration;

import com.travelguide.memory.InMemoryMemoryStore;
import com.travelguide.memory.MemoryService;
import com.travelguide.memory.MemoryStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 多 Agent 模块的本地装配。
 *
 * <p>已配置 Mongo 时复用持久化 MemoryStore；否则使用进程内实现，保证未配置外部依赖时模块仍可运行。</p>
 */
@Configuration
public class OrchestrationConfiguration {

    @Bean
    @ConditionalOnMissingBean(MemoryService.class)
    public MemoryService orchestrationMemoryService(
            ObjectProvider<MemoryStore> memoryStoreProvider
    ) {
        return new MemoryService(
                memoryStoreProvider.getIfAvailable(InMemoryMemoryStore::new)
        );
    }
}
