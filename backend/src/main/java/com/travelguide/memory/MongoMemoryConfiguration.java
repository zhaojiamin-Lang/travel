package com.travelguide.memory;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * MongoDB 记忆存储条件配置。
 *
 * <p>只有显式配置非空 Mongo URI 时才连接数据库，默认离线启动不受影响。</p>
 */
@Configuration
@Conditional(MongoMemoryConfiguration.MongoUriPresentCondition.class)
public class MongoMemoryConfiguration {

    @Bean(destroyMethod = "close")
    public MongoClient memoryMongoClient(
            @Value("${travel.memory.mongodb.uri}") String uri
    ) {
        return MongoClients.create(uri);
    }

    @Bean
    public MemoryStore memoryStore(
            MongoClient memoryMongoClient,
            @Value("${travel.memory.mongodb.database:travel_memory}") String database
    ) {
        return new MongoMemoryStore(memoryMongoClient, database);
    }

    static final class MongoUriPresentCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String uri = context.getEnvironment().getProperty("travel.memory.mongodb.uri");
            return uri != null && !uri.isBlank();
        }
    }
}
