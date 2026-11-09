package com.travelguide.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.ai.AiGateway;
import com.travelguide.orchestration.OrchestrationModels.Intent;
import com.travelguide.orchestration.OrchestrationModels.RoutingContext;
import com.travelguide.orchestration.OrchestrationModels.RoutingDecision;
import com.travelguide.orchestration.OrchestrationModels.Surface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Supervisor 的 LLM 意图路由。
 *
 * <p>路由模型只输出结构化意图，不生成业务内容。模型不可用、输出不可解析或置信度不足时，
 * 使用确定性规则与一次澄清完成降级，避免把模糊请求误分发给错误 Agent。</p>
 */
@Component
public class LlmIntentRouter implements IntentRouter {

    private static final Logger LOGGER = LoggerFactory.getLogger(LlmIntentRouter.class);
    private static final Set<String> PLAN_TERMS = Set.of(
            "规划", "安排", "行程", "路线", "几天", "一日", "两日", "三日",
            "预算", "改一下", "换一个景点", "重新安排", "怎么玩", "去哪儿玩"
    );
    private static final Set<String> GUIDE_TERMS = Set.of(
            "到了吗", "附近", "这里", "当前景点", "讲解", "导览", "下一站",
            "还能看什么", "还能吃什么", "多少钱", "开放吗", "闭馆",
            "导游", "讲解员", "边走边讲"
    );
    private static final Set<String> PREFERENCE_TERMS = Set.of(
            "喜欢", "偏好", "感兴趣", "不吃", "吃素", "清淡", "轻松",
            "慢一点", "不要太赶", "紧凑", "穷游", "预算不高", "无障碍", "少走楼梯"
    );
    private static final String ROUTER_INSTRUCTIONS = """
            你是旅行助手的 Supervisor，只负责判断用户下一步应该交给哪个 Agent。

            可选主意图：
            - PLANNING：创建、修改、生成或检查旅行计划。
            - GUIDE：当前景点讲解、定位确认、附近推荐和现场追问。
            - PREFERENCE：明确表达、查询或纠正长期旅行偏好。
            - CLARIFY：信息不足，无法可靠判断。

            规则：
            1. 一句话同时包含行动和偏好时，primary 选择行动，secondary 加入 PREFERENCE。
            2. “我喜欢安静，帮我安排两天”应返回 PLANNING + PREFERENCE。
            3. 不替用户补充事实，不输出 Markdown。
            4. 只输出 JSON：
            {"primary":"PLANNING","secondary":["PREFERENCE"],"confidence":0.93,"reason":"简短中文原因"}
            """;

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper;
    private final double confidenceThreshold;

    public LlmIntentRouter(
            AiGateway aiGateway,
            ObjectMapper objectMapper,
            @Value("${travel.orchestration.router.confidence-threshold:0.60}")
            double confidenceThreshold
    ) {
        this.aiGateway = aiGateway;
        this.objectMapper = objectMapper;
        this.confidenceThreshold = confidenceThreshold;
    }

    @Override
    public RoutingDecision route(RoutingContext context) {
        RoutingDecision fastPath = unambiguousRuleDecision(context);
        if (fastPath != null) {
            return fastPath;
        }
        if (aiGateway.isEnabled()) {
            try {
                return routeWithModel(context);
            } catch (RuntimeException exception) {
                LOGGER.warn("LLM 意图路由失败，已降级到确定性规则: {}", exception.getMessage());
                return ruleBased(context, "模型路由失败");
            }
        }
        return ruleBased(context, "模型未配置");
    }

    /**
     * 明确单意图请求走零模型调用的快速路径。
     *
     * <p>复合“规划 + 偏好”仍交给 LLM，避免关键词快捷路径吞掉用户隐含的长期偏好。</p>
     */
    private RoutingDecision unambiguousRuleDecision(RoutingContext context) {
        String message = context.message() == null ? "" : context.message();
        boolean planning = containsAny(message, PLAN_TERMS);
        boolean guide = containsAny(message, GUIDE_TERMS);
        boolean preference = containsAny(message, PREFERENCE_TERMS);

        Intent primary;
        if (!planning && !guide && preference) {
            primary = Intent.PREFERENCE;
        } else if (!planning && guide) {
            primary = Intent.GUIDE;
        } else if (planning && !guide && !preference) {
            primary = Intent.PLANNING;
        } else if (context.surface() == Surface.GUIDE && !planning) {
            primary = Intent.GUIDE;
        } else {
            return null;
        }

        Set<Intent> secondary = preference && primary != Intent.PREFERENCE
                ? Set.of(Intent.PREFERENCE)
                : Set.of();
        return new RoutingDecision(
                primary,
                secondary,
                0.94,
                "明确单意图请求命中本地快速路由",
                "RULE_FAST_PATH"
        );
    }

    private RoutingDecision routeWithModel(RoutingContext context) {
        String routingContext = """
                当前页面：%s
                是否有活动规划：%s
                是否有导游会话：%s
                用户输入：%s
                """.formatted(
                context.surface(),
                hasText(context.planId()),
                hasText(context.guideSessionId()),
                context.message()
        );
        String raw = aiGateway.complete(ROUTER_INSTRUCTIONS, routingContext);
        String json = extractJsonObject(raw);
        ModelDecision decision;
        try {
            decision = objectMapper.readValue(json, ModelDecision.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("路由 JSON 无法解析", exception);
        }

        Intent primary = parseIntent(decision.primary());
        if (primary == null) {
            throw new IllegalArgumentException("路由结果缺少合法主意图");
        }

        Set<Intent> secondary = new LinkedHashSet<>();
        if (decision.secondary() != null) {
            decision.secondary().stream()
                    .map(this::parseIntent)
                    .filter(item -> item != null && item != Intent.CLARIFY && item != primary)
                    .forEach(secondary::add);
        }
        double confidence = decision.confidence() == null ? 0 : decision.confidence();

        // 置信度不足时宁可追问一次，也不能把用户请求交给错误业务 Agent。
        if (confidence < confidenceThreshold && primary != Intent.CLARIFY) {
            return new RoutingDecision(
                    Intent.CLARIFY,
                    Set.of(),
                    confidence,
                    "意图不够明确，需要用户补充目标",
                    "LLM"
            );
        }
        String reason = hasText(decision.reason()) ? decision.reason().trim() : "模型完成意图判断";
        return new RoutingDecision(primary, secondary, confidence, reason, "LLM");
    }

    private RoutingDecision ruleBased(RoutingContext context, String fallbackReason) {
        String message = context.message() == null ? "" : context.message();
        boolean planning = containsAny(message, PLAN_TERMS)
                || context.surface() == Surface.PLAN;
        boolean guide = containsAny(message, GUIDE_TERMS)
                || context.surface() == Surface.GUIDE
                || hasText(context.guideSessionId());
        boolean preference = containsAny(message, PREFERENCE_TERMS);

        Intent primary;
        if (planning) {
            primary = Intent.PLANNING;
        } else if (guide) {
            primary = Intent.GUIDE;
        } else if (preference) {
            primary = Intent.PREFERENCE;
        } else {
            return new RoutingDecision(
                    Intent.CLARIFY,
                    Set.of(),
                    0.35,
                    fallbackReason + "，且用户目标不明确",
                    "RULE_FALLBACK"
            );
        }

        Set<Intent> secondary = new LinkedHashSet<>();
        if (preference && primary != Intent.PREFERENCE) {
            secondary.add(Intent.PREFERENCE);
        }
        return new RoutingDecision(
                primary,
                secondary,
                planning || guide ? 0.82 : 0.74,
                fallbackReason + "，已使用关键词与页面上下文完成路由",
                "RULE_FALLBACK"
        );
    }

    private Intent parseIntent(String value) {
        if (!hasText(value)) {
            return null;
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "PLANNING", "PLAN", "ITINERARY" -> Intent.PLANNING;
            case "GUIDE", "GUIDANCE" -> Intent.GUIDE;
            case "PREFERENCE", "MEMORY" -> Intent.PREFERENCE;
            case "CLARIFY", "CLARIFICATION" -> Intent.CLARIFY;
            default -> null;
        };
    }

    private String extractJsonObject(String value) {
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型未返回 JSON 对象");
        }
        return value.substring(start, end + 1);
    }

    private boolean containsAny(String text, Set<String> terms) {
        return terms.stream().anyMatch(text::contains);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ModelDecision(
            String primary,
            List<String> secondary,
            Double confidence,
            String reason
    ) {
    }
}
