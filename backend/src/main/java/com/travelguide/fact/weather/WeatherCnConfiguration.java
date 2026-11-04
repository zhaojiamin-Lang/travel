package com.travelguide.fact.weather;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * WeatherCN 条件配置。
 *
 * <p>未配置 API Key 时不创建天气工具 Bean，不影响现有启动和离线降级。</p>
 */
@Configuration
@Conditional(WeatherCnConfiguration.ApiKeyPresentCondition.class)
public class WeatherCnConfiguration {

    @Bean
    public WeatherCnDynamicFactTool weatherCnDynamicFactTool(
            ObjectMapper objectMapper,
            @Value("${travel.weathercn.base-url:https://openapi.weathercn.com}") String baseUrl,
            @Value("${travel.weathercn.api-key}") String apiKey
    ) {
        // Key 由 YAML 的环境变量占位符传入，不写入源码和仓库配置。
        return new WeatherCnDynamicFactTool(
                new WeatherCnHttpClient(baseUrl, apiKey, objectMapper)
        );
    }

    /**
     * 属性存在但值为空时仍视为未配置，避免测试和离线启动失败。
     */
    static final class ApiKeyPresentCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String apiKey = context.getEnvironment().getProperty("travel.weathercn.api-key");
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
