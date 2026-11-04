package com.travelguide.rag.retrieval;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向中文短问答的轻量 tokenizer。
 *
 * <p>OpenSearch 生产环境应使用固定版本 IK。本地实现用汉字二/三元组补充
 * 未登录词召回，保证开发和评测阶段可以复现 BM25 行为。</p>
 */
public final class TextTokenizer {

    private static final Pattern TOKEN_RUN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+");

    public List<String> tokenize(String text) {
        String normalized = normalize(text);
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_RUN.matcher(normalized);
        while (matcher.find()) {
            String run = matcher.group();
            if (run.codePoints().allMatch(this::isHan)) {
                addChineseNgrams(run, tokens);
            } else if (run.length() >= 2) {
                tokens.add(run);
            }
        }
        return tokens;
    }

    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }

    private void addChineseNgrams(String run, List<String> tokens) {
        if (run.length() == 1) {
            tokens.add(run);
            return;
        }
        for (int size = 2; size <= 3; size++) {
            for (int index = 0; index + size <= run.length(); index++) {
                tokens.add(run.substring(index, index + size));
            }
        }
        // 短专名额外保留完整词，提升“外滩”“豫园”等精确匹配权重。
        if (run.length() <= 8) {
            tokens.add(run);
        }
    }

    private boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }
}
