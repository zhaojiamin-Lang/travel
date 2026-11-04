package com.travelguide.rag.query;

import com.travelguide.rag.RagModels.Intent;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.QueryContext;
import com.travelguide.rag.RagModels.QueryPlan;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 规则优先的 Query Rewrite。
 *
 * <p>先使用当前景点、行程和实体别名消除指代；只有在上下文不足时记录
 * unresolvedMentions，交给后续 LLM 或 Mongo 记忆消歧。这样常规问答不会
 * 为了一次简单改写额外调用大模型。</p>
 */
public final class QueryRewriteService {

    private static final Pattern HERE_PRONOUN = Pattern.compile("这里|这儿|这边|这个地方|这个景点|它");
    private static final Pattern CONTEXT_DEPENDENT_FACT = Pattern.compile(
            "几点|开门|关门|闭馆|开放|门票|票价|多少钱|收费|免费|预约|证件|看什么|看点|怎么逛|多久"
                    + "|天气|下雨|降雨|气温|台风|入场|入馆|入口|从哪里进"
                    + "|讲解|介绍|讲一讲|说一说|故事"
    );
    private static final Pattern MEMORY_REFERENCE = Pattern.compile("上次|那家|之前那|刚才那");

    private final Map<String, Set<String>> aliasesToEntities;
    private final List<String> aliasesByLength;

    public QueryRewriteService(List<KnowledgeDocument> documents) {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        for (KnowledgeDocument document : documents) {
            for (String alias : document.metadata().aliases()) {
                if (alias == null || alias.isBlank()) {
                    continue;
                }
                index.computeIfAbsent(alias.strip(), ignored -> new LinkedHashSet<>())
                        .addAll(document.metadata().entityIds());
            }
        }
        this.aliasesToEntities = Map.copyOf(index);
        this.aliasesByLength = index.keySet().stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    public QueryPlan rewrite(String question, QueryContext context) {
        String original = question == null ? "" : Normalizer.normalize(question, Normalizer.Form.NFKC).strip();
        if (original.isBlank()) {
            throw new IllegalArgumentException("问题不能为空");
        }

        String standalone = original;
        boolean rewritten = false;
        List<String> unresolved = new ArrayList<>();
        LinkedHashSet<String> entityIds = new LinkedHashSet<>();
        Intent intent = detectIntent(original);

        // 指代词优先使用当前导游会话里的稳定 POI，而不是向量相似度。
        if (HERE_PRONOUN.matcher(standalone).find()) {
            if (hasText(context.currentPoiId()) && hasText(context.currentPoiName())) {
                standalone = HERE_PRONOUN.matcher(standalone)
                        .replaceAll(context.currentPoiName());
                entityIds.add(context.currentPoiId());
                rewritten = true;
            } else {
                unresolved.add("当前景点指代");
            }
        }

        if (standalone.contains("下一站")) {
            if (hasText(context.nextPoiId()) && hasText(context.nextPoiName())) {
                standalone = standalone.replace("下一站", context.nextPoiName());
                entityIds.add(context.nextPoiId());
                rewritten = true;
            } else {
                unresolved.add("下一站");
            }
        }

        for (Map.Entry<String, String> memory : context.rememberedEntities().entrySet()) {
            if (standalone.contains(memory.getKey())) {
                standalone = standalone.replace(memory.getKey(), memory.getValue());
                rewritten = true;
            }
        }

        if (MEMORY_REFERENCE.matcher(original).find() && !rewritten) {
            unresolved.add("长期记忆指代");
        }

        // 无实体的问题若明显依赖当前景点，补上当前 POI 名称作为检索锚点。
        if (entityIds.isEmpty()
                && CONTEXT_DEPENDENT_FACT.matcher(original).find()
                && hasText(context.currentPoiId())
                && hasText(context.currentPoiName())
                && !standalone.contains(context.currentPoiName())) {
            standalone = context.currentPoiName() + " " + standalone;
            entityIds.add(context.currentPoiId());
            rewritten = true;
        }

        // 最长别名优先，并跳过已经被更长实体名覆盖的重叠命中。
        List<Span> matchedSpans = new ArrayList<>();
        for (String alias : aliasesByLength) {
            int cursor = 0;
            while ((cursor = standalone.indexOf(alias, cursor)) >= 0) {
                int end = cursor + alias.length();
                if (!overlaps(matchedSpans, cursor, end)) {
                    entityIds.addAll(aliasesToEntities.getOrDefault(alias, Set.of()));
                    matchedSpans.add(new Span(cursor, end));
                }
                cursor = end;
            }
        }

        List<String> poiIds = entityIds.stream()
                .filter(id -> id != null && id.startsWith("poi:"))
                .map(id -> id.substring("poi:".length()))
                .toList();
        List<String> factTypes = factTypes(intent);
        String city = hasText(context.city()) ? context.city() : detectCity(standalone);
        Instant asOf = context.asOf() == null ? Instant.now() : context.asOf();

        return new QueryPlan(
                original,
                standalone,
                intent,
                List.copyOf(entityIds),
                poiIds,
                factTypes,
                city,
                context.currentPoiName(),
                asOf,
                unresolved,
                rewritten,
                rewritten ? "RULE_CONTEXT" : "ORIGINAL"
        );
    }

    private Intent detectIntent(String query) {
        String text = query.toLowerCase(Locale.ROOT);
        if (text.matches(".*(现在|此刻).*(开放|闭馆|能进|能去).*")) {
            return Intent.OPEN_STATUS;
        }
        if (text.matches(".*(门票|票价|多少钱|收费|免费).*")) {
            return Intent.TICKET;
        }
        if (text.matches(".*(几点|开门|关门|开放时间|营业|闭馆|最晚.*(入场|入馆)|停止(入场|入馆)).*")) {
            return Intent.OPEN_HOURS;
        }
        if (text.matches(".*(预约|证件|实名).*")) {
            return Intent.BOOKING;
        }
        if (text.matches(".*(天气|下雨|降雨|气温|台风|冷不冷|热不热).*")) {
            return Intent.WEATHER;
        }
        if (text.matches(".*(吃什么|吃饭|用餐|餐厅|餐饮|小吃|咖啡).*")) {
            return Intent.DINING;
        }
        if (isTravelDuration(text)) {
            return Intent.TRAVEL_DURATION;
        }
        if (text.matches(".*(多久|停留|玩多长时间|需要几个小时).*")) {
            return Intent.DURATION;
        }
        if (text.matches(".*(怎么去|怎么走|交通|地铁|公交|停车|路线|入口|从哪里(进|入馆)|哪个门).*")) {
            return Intent.ROUTE;
        }
        if (text.matches(".*(看点|值得看|展品|展厅|怎么逛).*")) {
            return Intent.HIGHLIGHT;
        }
        if (text.matches(".*(历史|由来|年代|故事).*")) {
            return Intent.HISTORY;
        }
        return Intent.GENERAL;
    }

    private List<String> factTypes(Intent intent) {
        return switch (intent) {
            case OPEN_HOURS, OPEN_STATUS -> List.of("OPEN_HOURS");
            case TICKET -> List.of("TICKET");
            case BOOKING -> List.of("BOOKING");
            case ROUTE, TRANSIT -> List.of("TRANSIT", "ROUTE", "ENTRANCE");
            case HIGHLIGHT -> List.of("HIGHLIGHT");
            case HISTORY -> List.of("HISTORY");
            case DINING -> List.of("DINING");
            case DURATION -> List.of("DURATION");
            // 交通耗时属于路线事实，不能套用景点“停留时长”的严格过滤条件。
            case TRAVEL_DURATION -> List.of("TRANSIT", "ROUTE", "ENTRANCE");
            case WEATHER -> List.of("WEATHER_CURRENT", "WEATHER_FORECAST");
            default -> List.of();
        };
    }

    private boolean isTravelDuration(String text) {
        boolean asksDuration = text.matches(".*(多久|多长时间|几个小时|几小时|车程).*");
        boolean mentionsTransport = text.matches(
                ".*(地铁|公交|磁悬浮|机场|火车站|到市区|去市区|换乘|坐车|开车|打车|步行|骑行"
                        + "|从.+到.+|到.+要.+).*"
        );
        return asksDuration && mentionsTransport;
    }

    private String detectCity(String query) {
        return query.contains("上海") ? "上海" : "";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean overlaps(List<Span> spans, int start, int end) {
        return spans.stream().anyMatch(span -> start < span.end() && end > span.start());
    }

    private record Span(int start, int end) {
    }
}
