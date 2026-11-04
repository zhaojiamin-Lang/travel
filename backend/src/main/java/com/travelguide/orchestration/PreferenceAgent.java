package com.travelguide.orchestration;

import com.travelguide.memory.MemoryModels.PreferenceSignal;
import com.travelguide.memory.MemoryModels.RecalledMemory;
import com.travelguide.memory.MemoryService;
import com.travelguide.orchestration.OrchestrationModels.AgentInvocation;
import com.travelguide.orchestration.OrchestrationModels.AgentType;
import com.travelguide.orchestration.OrchestrationModels.PreferenceResult;
import com.travelguide.orchestration.OrchestrationModels.PreferenceView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户偏好 Agent。
 *
 * <p>只把用户明确表达的稳定偏好写入长期记忆，不根据一次行程推断敏感属性。
 * 显式修正会覆盖旧偏好；查询类表达只召回、不写入。</p>
 */
@Service
public class PreferenceAgent implements Agent {

    private static final String TYPE_PACE = "pace";
    private static final String TYPE_DIET = "diet";
    private static final String TYPE_BUDGET = "budget_style";
    private static final String TYPE_INTEREST = "interest";
    private static final String TYPE_ACCESSIBILITY = "accessibility";
    private static final int MAX_RECALLED_PREFERENCES = 12;

    private static final Pattern INTEREST_PATTERN = Pattern.compile(
            "(?:喜欢|偏好|更喜欢|偏爱|感兴趣(?:于)?)([^。！？!?；;\\n]{1,24})"
    );
    private static final Map<String, String> INTEREST_ALIASES = Map.ofEntries(
            Map.entry("建筑", "建筑"),
            Map.entry("历史", "历史"),
            Map.entry("博物馆", "博物馆"),
            Map.entry("艺术", "艺术"),
            Map.entry("展览", "艺术"),
            Map.entry("自然", "自然"),
            Map.entry("公园", "公园"),
            Map.entry("园林", "园林"),
            Map.entry("美食", "美食"),
            Map.entry("小吃", "美食"),
            Map.entry("摄影", "摄影"),
            Map.entry("拍照", "摄影"),
            Map.entry("亲子", "亲子"),
            Map.entry("购物", "购物"),
            Map.entry("夜景", "夜景")
    );

    private final Tool<PreferenceRecallTool.Input, List<RecalledMemory>> recallTool;
    private final Tool<PreferenceRememberTool.Input, PreferenceSignal> rememberTool;

    @Autowired
    public PreferenceAgent(ToolRegistry toolRegistry) {
        this.recallTool = toolRegistry.get(PreferenceRecallTool.NAME);
        this.rememberTool = toolRegistry.get(PreferenceRememberTool.NAME);
    }

    /**
     * 兼容直接构造 Agent 的既有测试；依赖仍先包装为工具后进入统一边界。
     */
    public PreferenceAgent(MemoryService memoryService) {
        this(legacyRegistry(memoryService));
    }

    @Override
    public AgentType type() {
        return AgentType.PREFERENCE;
    }

    @Override
    public PreferenceResult execute(AgentInvocation invocation) {
        if (invocation.userId() == null || invocation.userId().isBlank()) {
            return new PreferenceResult(
                    List.of(),
                    List.of(),
                    "登录后我才能保存和召回你的长期偏好。",
                    true
            );
        }
        return ToolExecutionContext.runAs(
                invocation.userId(),
                () -> executeInternal(invocation)
        );
    }

    private PreferenceResult executeInternal(AgentInvocation invocation) {
        Instant now = Instant.now();
        List<PreferenceView> updated = rememberExplicitPreferences(invocation, now);
        List<PreferenceView> available = recall(invocation.userId(), now);
        String reply;
        if (!updated.isEmpty()) {
            reply = "已记住：" + joinLabels(updated) + "。";
        } else if (!available.isEmpty()) {
            reply = "已加载你的旅行偏好：" + joinLabels(available) + "。";
        } else {
            reply = "目前还没有保存长期旅行偏好。";
        }
        return new PreferenceResult(available, updated, reply, false);
    }

    /**
     * 将活跃偏好压缩成适合放入规划上下文的短句。
     *
     * <p>偏好只作为软约束，用户当前消息中的明确要求始终优先。</p>
     */
    public String enrichPlanningMessage(String message, List<PreferenceView> preferences) {
        if (preferences == null || preferences.isEmpty()) {
            return message;
        }
        String summary = preferences.stream()
                .map(PreferenceView::label)
                .distinct()
                .limit(8)
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
        return "长期偏好（仅作参考，当前明确要求优先）：" + summary
                + "\n当前请求：" + message;
    }

    private List<PreferenceView> rememberExplicitPreferences(
            AgentInvocation invocation,
            Instant now
    ) {
        List<Rule> rules = extractRules(invocation.message());
        List<PreferenceView> updated = new ArrayList<>();
        for (Rule rule : rules) {
            List<PreferenceSignal> existingSignals = recallTool
                    .execute(new PreferenceRecallTool.Input(
                            rule.type(),
                            now,
                            50
                    ))
                    .stream()
                    .map(RecalledMemory::signal)
                    .toList();
            Rule effectiveRule = mergeInterestRule(rule, existingSignals);
            boolean alreadyStored = existingSignals.stream()
                    .anyMatch(signal -> signal.key().equals(rule.key())
                            && signal.value().equals(effectiveRule.value()));
            if (alreadyStored) {
                continue;
            }
            // 偏好写入统一经过工具边界：修正旧值、保留审计，不让 Agent 直接改存储。
            PreferenceSignal saved = rememberTool.execute(
                    new PreferenceRememberTool.Input(
                            effectiveRule.type(),
                            effectiveRule.key(),
                            effectiveRule.value(),
                            invocation.runId(),
                            now
                    )
            );
            updated.add(toView(saved));
        }
        return List.copyOf(updated);
    }

    /**
     * 只抽取“我明确喜欢/不吃/想要”的稳定表达，宁缺毋滥。
     */
    private List<Rule> extractRules(String message) {
        if (message == null || message.isBlank() || isPreferenceQuestion(message)) {
            return List.of();
        }
        LinkedHashMap<String, Rule> rules = new LinkedHashMap<>();
        if (message.matches(".*(轻松一点|慢一点|不要太赶|不想走太累|休闲节奏).*")) {
            add(rules, new Rule(TYPE_PACE, "travel_pace", "RELAXED"));
        } else if (message.matches(".*(紧凑一点|多去几个|特种兵|行程排满).*")) {
            add(rules, new Rule(TYPE_PACE, "travel_pace", "COMPACT"));
        }

        if (message.matches(".*(不吃辣|不能吃辣|不要辣).*")) {
            add(rules, new Rule(TYPE_DIET, "spicy", "NO_SPICY"));
        }
        if (message.matches(".*(吃素|素食).*")) {
            add(rules, new Rule(TYPE_DIET, "vegetarian", "YES"));
        }

        if (message.matches(".*(穷游|预算不高|追求性价比|经济实惠).*")) {
            add(rules, new Rule(TYPE_BUDGET, "comfort", "ECONOMY"));
        } else if (message.matches(".*(住得舒服|预算充足|舒适优先|不想穷游).*")) {
            add(rules, new Rule(TYPE_BUDGET, "comfort", "COMFORT"));
        }

        if (message.matches(".*(行动不便|轮椅|无障碍|少走楼梯|避免台阶).*")) {
            add(rules, new Rule(
                    TYPE_ACCESSIBILITY,
                    "step_free",
                    "PREFERRED"
            ));
        }

        Matcher matcher = INTEREST_PATTERN.matcher(message);
        if (matcher.find()) {
            Set<String> interests = new java.util.LinkedHashSet<>();
            String phrase = matcher.group(1);
            INTEREST_ALIASES.forEach((alias, canonical) -> {
                if (phrase.contains(alias)) {
                    interests.add(canonical);
                }
            });
            if (!interests.isEmpty()) {
                String value = String.join(",", interests);
                add(rules, new Rule(
                        TYPE_INTEREST,
                        "travel_interest",
                        value
                ));
            }
        }
        return List.copyOf(rules.values());
    }

    /**
     * 兴趣是累加关系，不能因为新说了一个兴趣就静默丢掉此前明确保存的兴趣。
     */
    private Rule mergeInterestRule(Rule rule, List<PreferenceSignal> existingSignals) {
        if (!TYPE_INTEREST.equals(rule.type())) {
            return rule;
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>();
        existingSignals.stream()
                .filter(signal -> TYPE_INTEREST.equals(signal.type()))
                .forEach(signal -> merged.addAll(List.of(signal.value().split(","))));
        merged.addAll(List.of(rule.value().split(",")));
        return new Rule(rule.type(), rule.key(), String.join(",", merged));
    }

    private boolean isPreferenceQuestion(String message) {
        return message.matches(".*(我(?:都)?喜欢什么|我的偏好|我有哪些偏好|记得我.*(?:喜欢|偏好)).*");
    }

    private void add(Map<String, Rule> rules, Rule rule) {
        rules.putIfAbsent(rule.type() + ":" + rule.key(), rule);
    }

    private List<PreferenceView> recall(String userId, Instant now) {
        return recallTool.execute(new PreferenceRecallTool.Input(
                        null,
                        now,
                        MAX_RECALLED_PREFERENCES
                ))
                .stream()
                .map(RecalledMemory::signal)
                .map(this::toView)
                .toList();
    }

    private PreferenceView toView(PreferenceSignal signal) {
        return new PreferenceView(
                signal.signalId(),
                signal.type(),
                signal.key(),
                signal.value(),
                labelOf(signal.key(), signal.value()),
                signal.confidence()
        );
    }

    private String labelOf(String key, String value) {
        return switch (key + ":" + value) {
            case "travel_pace:RELAXED" -> "旅行节奏偏轻松";
            case "travel_pace:COMPACT" -> "旅行节奏偏紧凑";
            case "spicy:NO_SPICY" -> "不吃辣";
            case "vegetarian:YES" -> "偏好素食";
            case "comfort:ECONOMY" -> "偏好经济型方案";
            case "comfort:COMFORT" -> "偏好舒适型方案";
            case "step_free:PREFERRED" -> "优先无障碍路线";
            default -> "travel_interest".equals(key)
                    ? "偏好" + value.replace(",", "、")
                    : value;
        };
    }

    private String joinLabels(List<PreferenceView> preferences) {
        return preferences.stream()
                .map(PreferenceView::label)
                .distinct()
                .reduce((left, right) -> left + "；" + right)
                .orElse("");
    }

    private static ToolRegistry legacyRegistry(MemoryService memoryService) {
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new PreferenceRecallTool(memoryService));
        toolRegistry.register(new PreferenceRememberTool(memoryService));
        return toolRegistry;
    }

    private record Rule(String type, String key, String value) {
    }
}
