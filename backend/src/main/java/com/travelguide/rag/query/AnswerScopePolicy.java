package com.travelguide.rag.query;

import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.QueryPlan;
import com.travelguide.rag.RagModels.RankedChunk;
import com.travelguide.rag.retrieval.TextTokenizer;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 判断问题是否落在当前景点知识库的可回答范围内。
 *
 * <p>向量召回天然会返回 Top-K 结果，即使问题与知识库无关。本策略先剔除
 * 问题中的语气词和通用问法，再要求剩余实体词与至少一条证据存在字面交集。
 * 它不做事实生成，只负责阻止把无关问题硬套到景点讲解上。</p>
 */
public final class AnswerScopePolicy {

    public static final String UNSUPPORTED_ANSWER = "对不起，我暂时无法回答这个问题，问问别的吧";

    private static final TextTokenizer TOKENIZER = new TextTokenizer();
    private static final List<String> QUESTION_SCAFFOLD = List.of(
            "这个地方", "这个景点", "当前位置", "上海博物馆人民广场馆",
            "我想知道", "请告诉我", "能不能", "可不可以", "有没有",
            "是什么", "为什么", "怎么样", "有哪些", "有什么", "怎么",
            "如何", "是否", "请问", "告诉", "介绍", "讲讲", "说说",
            "建议", "值得", "适合", "重要", "特色", "好玩", "好看",
            "好吃", "好买", "好逛", "推荐",
            "这里", "这儿", "这边", "这个", "那个", "它", "我",
            "你", "一下", "吗", "呢", "啊", "呀"
    );
    private static final List<String> TRAVEL_SCAFFOLD = List.of(
            "附近还能看什么", "附近", "下一站", "还能", "看点", "值得看",
            "展品", "展厅", "怎么逛", "停留多久", "多久", "多长时间",
            "门票", "票价", "多少钱", "收费", "免费", "预约", "证件",
            "实名", "开放时间", "几点", "开门", "关门", "闭馆", "开放",
            "入场", "入馆", "入口", "天气", "下雨", "降雨", "气温",
            "台风", "吃什么", "吃饭", "用餐", "餐厅", "餐饮", "小吃",
            "咖啡", "美食", "怎么去", "怎么走", "交通", "地铁", "公交", "停车",
            "路线", "历史", "由来", "年代", "故事", "讲解", "攻略"
    );

    private AnswerScopePolicy() {
    }

    /**
     * RAG 有结果时，要求问题的剩余实体词至少命中一条 Top 证据。
     */
    public static boolean hasRelevantEvidence(
            String question,
            QueryPlan plan,
            List<RankedChunk> results
    ) {
        if (question == null || question.isBlank() || results == null || results.isEmpty()) {
            return false;
        }
        Set<String> contentTokens = contentTokens(question, plan);
        if (contentTokens.isEmpty()) {
            return true;
        }
        return results.stream()
                .limit(5)
                .map(RankedChunk::chunk)
                .map(AnswerScopePolicy::evidenceTokens)
                .anyMatch(tokens -> tokens.stream().anyMatch(contentTokens::contains));
    }

    /**
     * RAG 不可用时，仅允许保留明确的景点/旅行问法，避免再次输出整段景点讲解。
     */
    public static boolean isClearlyInScope(String question, String currentPoiName) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = normalize(question);
        if (hasText(currentPoiName) && normalized.contains(normalize(currentPoiName))) {
            return true;
        }
        if (normalized.contains("这里") || normalized.contains("这儿")
                || normalized.contains("这边") || normalized.contains("这个景点")
                || normalized.contains("这个地方")) {
            return true;
        }
        return TRAVEL_SCAFFOLD.stream().anyMatch(normalized::contains);
    }

    public static Set<String> contentTokens(String question, QueryPlan plan) {
        String content = normalize(question);
        if (hasText(plan.currentPoiName())) {
            content = content.replace(normalize(plan.currentPoiName()), " ");
        }
        if (hasText(plan.city())) {
            content = content.replace(normalize(plan.city()), " ");
        }
        for (String phrase : QUESTION_SCAFFOLD) {
            content = content.replace(normalize(phrase), " ");
        }
        for (String phrase : TRAVEL_SCAFFOLD) {
            content = content.replace(normalize(phrase), " ");
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : TOKENIZER.tokenize(content)) {
            if (token.codePointCount(0, token.length()) >= 2) {
                tokens.add(token);
            }
        }
        return Set.copyOf(tokens);
    }

    private static Set<String> evidenceTokens(KnowledgeChunk chunk) {
        return Set.copyOf(TOKENIZER.tokenize(
                chunk.metadata().title() + " " + chunk.sectionPath() + " " + chunk.rawText()
        ));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
