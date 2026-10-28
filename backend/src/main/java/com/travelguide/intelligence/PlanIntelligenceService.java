package com.travelguide.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.ai.AiGateway;
import com.travelguide.intelligence.PlanDiffModels.ExplanationView;
import com.travelguide.intelligence.PlanDiffModels.IntentView;
import com.travelguide.intelligence.PlanDiffModels.PlanDiffView;
import com.travelguide.service.PlanningService;
import com.travelguide.web.ApiDtos.PlanItem;
import com.travelguide.web.ApiDtos.PlanView;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定义千问模型与确定性排程逻辑之间的职责边界。
 *
 * <p>千问只负责理解用户意图和组织解释文案；项目 ID 校验、时间重算、
 * 语义 Diff 和新版本创建全部由 Java 完成。</p>
 */
@Service
public class PlanIntelligenceService {

    private static final Set<String> SUPPORTED_INTENTS = Set.of(
            "SET_BUDGET",
            "SET_PACE",
            "REPORT_DELAY",
            "REPLACE_POI",
            "REMOVE_POI",
            "EXPLAIN",
            "UNKNOWN"
    );

    private final AiGateway aiGateway;
    private final PlanningService planningService;
    private final PlanDiffService diffService;
    private final ObjectMapper objectMapper;

    public PlanIntelligenceService(
            AiGateway aiGateway,
            PlanningService planningService,
            PlanDiffService diffService,
            ObjectMapper objectMapper
    ) {
        this.aiGateway = aiGateway;
        this.planningService = planningService;
        this.diffService = diffService;
        this.objectMapper = objectMapper;
    }

    /**
     * 只提取用户意图，不直接修改行程、预算或时间。
     */
    public IntentView interpret(String planId, String message) {
        PlanView plan = planningService.getPlan(planId);
        if (aiGateway.isEnabled()) {
            try {
                String response = aiGateway.complete(
                        """
                                你是旅行计划指令解析器。只输出 JSON，不要计算路线或时间。
                                intent 只能取 SET_BUDGET、SET_PACE、REPORT_DELAY、
                                REPLACE_POI、REMOVE_POI、EXPLAIN、UNKNOWN。
                                fields 中只能提取用户明确给出的信息，例如 budget、pace、delayMinutes、itemId。
                                不得自行修改行程，不得编造景点 ID。
                                """,
                        "当前计划：" + summary(plan) + "\n用户输入：" + message
                );
                JsonNode json = objectMapper.readTree(stripCodeFence(response));
                String intent = json.path("intent").asText("UNKNOWN");
                if (!SUPPORTED_INTENTS.contains(intent)) {
                    intent = "UNKNOWN";
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> fields = json.path("fields").isObject()
                        ? objectMapper.convertValue(json.path("fields"), Map.class)
                        : Map.of();
                fields = mergeExplicitHints(message, fields);
                validateFields(plan, fields);
                return new IntentView(
                        intent,
                        Math.max(0.0, Math.min(1.0, json.path("confidence").asDouble(0.8))),
                        !"UNKNOWN".equals(intent) && !"EXPLAIN".equals(intent),
                        fields,
                        "由千问完成意图理解，时间与路线由 Java 规则校验"
                );
            } catch (RuntimeException | java.io.IOException ignored) {
                // 千问调用失败时，继续使用确定性解析器。
            }
        }
        return fallbackIntent(plan, message);
    }

    /**
     * 千问只能解释 Java 已经计算好的差异；
     * 如果回答出现差异中不存在的数字，就使用确定性说明兜底。
     */
    public ExplanationView explainDiff(String targetPlanId, String againstPlanId) {
        PlanView target = planningService.getPlan(targetPlanId);
        PlanView against = planningService.getPlan(againstPlanId);
        PlanDiffView diff = diffService.diff(against, target);
        String fallback = deterministicExplanation(diff);
        if (!aiGateway.isEnabled()) {
            return new ExplanationView(fallback, "DETERMINISTIC_FALLBACK", diff);
        }
        try {
            String json;
            try {
                json = objectMapper.writeValueAsString(diff);
            } catch (java.io.IOException exception) {
                return new ExplanationView(fallback, "DETERMINISTIC_FALLBACK", diff);
            }
            String explanation = aiGateway.complete(
                    """
                            你是行程变更说明助手。只能根据给定 Diff 解释变化原因和影响。
                            不得自行计算路线、预算或时间，不得增加 Diff 中不存在的数字。
                            用中文回答两到三句，说明用户需要确认什么。
                            """,
                    json
            ).trim();
            if (!numericFactsAreSupported(explanation, json, fallback)) {
                return new ExplanationView(fallback, "DETERMINISTIC_FALLBACK", diff);
            }
            return new ExplanationView(explanation, "QWEN_SPRING_AI", diff);
        } catch (RuntimeException exception) {
            return new ExplanationView(fallback, "DETERMINISTIC_FALLBACK", diff);
        }
    }

    private IntentView fallbackIntent(PlanView plan, String message) {
        Map<String, Object> fields = new LinkedHashMap<>();
        String intent = "UNKNOWN";
        double confidence = 0.72;

        Matcher budget = Pattern.compile("(?:预算|改成|调整到)\\s*(\\d{3,6})\\s*(?:元|块)?").matcher(message);
        Matcher delay = Pattern.compile("(?:晚了|迟到|延误)\\s*(\\d{1,3})\\s*分钟").matcher(message);
        if (budget.find()) {
            intent = "SET_BUDGET";
            fields.put("budget", Integer.parseInt(budget.group(1)));
        } else if (delay.find()) {
            intent = "REPORT_DELAY";
            fields.put("delayMinutes", Integer.parseInt(delay.group(1)));
        } else if (message.matches(".*(轻松|慢一点|休闲).*")) {
            intent = "SET_PACE";
            fields.put("pace", "RELAXED");
        } else if (message.matches(".*(紧凑|多去几个|特种兵).*")) {
            intent = "SET_PACE";
            fields.put("pace", "BALANCED");
        } else if (message.matches(".*(为什么|原因|解释).*")) {
            intent = "EXPLAIN";
        } else {
            for (PlanItem item : items(plan)) {
                if (message.contains(item.name())) {
                    intent = message.matches(".*(不去|删除|移除).*") ? "REMOVE_POI" : "REPLACE_POI";
                    fields.put("itemId", item.id());
                    fields.put("poiName", item.name());
                    break;
                }
            }
        }
        return new IntentView(intent, confidence, !"UNKNOWN".equals(intent) && !"EXPLAIN".equals(intent), fields,
                "千问未启用或解析失败，已使用确定性意图规则");
    }

    private Map<String, Object> mergeExplicitHints(String message, Map<String, Object> modelFields) {
        Map<String, Object> fields = new LinkedHashMap<>(modelFields);
        if (message.matches(".*(轻松|慢一点|休闲).*")) {
            fields.putIfAbsent("pace", "RELAXED");
        } else if (message.matches(".*(紧凑|多去几个|特种兵).*")) {
            fields.putIfAbsent("pace", "BALANCED");
        }
        Matcher day = Pattern.compile("第([一二两三四五1-5])天").matcher(message);
        if (day.find()) {
            fields.putIfAbsent("dayNumber", switch (day.group(1)) {
                case "一" -> 1;
                case "两", "二" -> 2;
                case "三" -> 3;
                case "四" -> 4;
                case "五" -> 5;
                default -> Integer.parseInt(day.group(1));
            });
        }
        return fields;
    }

    private void validateFields(PlanView plan, Map<String, Object> fields) {
        if (fields.containsKey("itemId")) {
            String itemId = String.valueOf(fields.get("itemId"));
            boolean exists = items(plan).stream().anyMatch(item -> item.id().equals(itemId));
            if (!exists) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模型返回了不属于当前行程的项目");
            }
        }
        if (fields.containsKey("budget")) {
            double budget = Double.parseDouble(String.valueOf(fields.get("budget")));
            if (budget <= 0 || budget > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预算参数不合法");
            }
        }
    }

    private String deterministicExplanation(PlanDiffView diff) {
        if (diff.identical()) {
            return "两个版本的行程内容一致，不需要生成新的回滚版本。";
        }
        var summary = diff.summary();
        return "版本 v" + diff.fromVersion() + " 到 v" + diff.toVersion()
                + " 共调整 " + diff.changes().size() + " 项：新增 " + summary.additions()
                + "，移除 " + summary.removals() + "，替换 " + summary.replacements()
                + "，时间变化 " + summary.timeChanges() + "。预算变化 "
                + summary.costDelta().stripTrailingZeros().toPlainString() + " 元。请确认后再应用。";
    }

    private boolean numericFactsAreSupported(String explanation, String diffJson, String fallback) {
        Set<String> allowed = new java.util.HashSet<>();
        Matcher matcher = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(diffJson + " " + fallback);
        while (matcher.find()) {
            allowed.add(matcher.group());
        }
        Matcher outputNumbers = Pattern.compile("\\d+(?:\\.\\d+)?").matcher(explanation);
        while (outputNumbers.find()) {
            if (!allowed.contains(outputNumbers.group())) {
                return false;
            }
        }
        return true;
    }

    private List<PlanItem> items(PlanView plan) {
        List<PlanItem> items = new ArrayList<>();
        plan.dayPlans().forEach(day -> items.addAll(day.items().stream()
                .filter(item -> "POI".equals(item.type()))
                .toList()));
        return items;
    }

    private String summary(PlanView plan) {
        return "当前为 v" + plan.version() + "，预算 " + plan.budget()
                + " 元，节奏 " + plan.pace() + "，日期 " + plan.startDate()
                + "，景点：" + items(plan).stream().map(PlanItem::name).toList();
    }

    private String stripCodeFence(String value) {
        return value.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
    }
}
