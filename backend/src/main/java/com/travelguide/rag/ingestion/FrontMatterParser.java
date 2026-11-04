package com.travelguide.rag.ingestion;

import com.travelguide.rag.RagModels.DocType;
import com.travelguide.rag.RagModels.DocumentMetadata;
import com.travelguide.rag.RagModels.KnowledgeDocument;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析知识 Markdown 的 YAML front matter。
 *
 * <p>这里只解析 RAG 入库需要的顶层标量和数组字段，避免为简单元数据引入
 * 额外 YAML 依赖。evidence 等内容保留在原文中，供后续引用校验使用。</p>
 */
public final class FrontMatterParser {

    public KnowledgeDocument parse(Path path) throws IOException {
        String source = Files.readString(path, StandardCharsets.UTF_8)
                .replace("\uFEFF", "");
        List<String> lines = List.of(source.split("\\R", -1));
        if (lines.isEmpty() || !"---".equals(lines.get(0).trim())) {
            throw new IllegalArgumentException(path + " 缺少 front matter");
        }

        Map<String, String> values = new LinkedHashMap<>();
        int bodyStart = -1;
        for (int index = 1; index < lines.size(); index++) {
            String line = lines.get(index);
            if ("---".equals(line.trim())) {
                bodyStart = index + 1;
                break;
            }
            // 只收集顶层字段，缩进的 evidence 明细不在这里解析。
            if (line.length() > 0 && !Character.isWhitespace(line.charAt(0))) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    values.put(line.substring(0, colon).trim(), unquote(line.substring(colon + 1).trim()));
                }
            }
        }
        if (bodyStart < 0) {
            throw new IllegalArgumentException(path + " 的 front matter 没有结束标记");
        }

        String body = String.join("\n", lines.subList(bodyStart, lines.size())).strip();
        DocumentMetadata metadata = new DocumentMetadata(
                required(values, "docId", path),
                DocType.parse(values.get("docType")),
                parseInt(values.get("version"), 1),
                value(values, "title", ""),
                value(values, "city", ""),
                value(values, "area", ""),
                list(values.get("entityIds")),
                list(values.get("poiIds")),
                list(values.get("factTypes")),
                list(values.get("aliases")),
                value(values, "sourceTier", ""),
                value(values, "sourceName", ""),
                nullable(values.get("sourceUrl")),
                value(values, "dataConfidence", "DEMO"),
                value(values, "status", "DRAFT"),
                nullable(values.get("reviewer")),
                date(values.get("validFrom")),
                date(values.get("validTo")),
                nullable(values.get("rawSnapshot")),
                nullable(values.get("contentSha256"))
        );
        return new KnowledgeDocument(metadata, body);
    }

    private String required(Map<String, String> values, String key, Path path) {
        String value = nullable(values.get(key));
        if (value == null) {
            throw new IllegalArgumentException(path + " 缺少必填字段 " + key);
        }
        return value;
    }

    private String value(Map<String, String> values, String key, String fallback) {
        String value = nullable(values.get(key));
        return value == null ? fallback : value;
    }

    private String nullable(String value) {
        String cleaned = unquote(value);
        return cleaned == null || cleaned.isBlank() || "null".equalsIgnoreCase(cleaned) ? null : cleaned;
    }

    private int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private LocalDate date(String value) {
        String cleaned = nullable(value);
        if (cleaned == null) {
            return null;
        }
        try {
            return LocalDate.parse(cleaned.length() > 10 ? cleaned.substring(0, 10) : cleaned);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<String> list(String value) {
        String cleaned = nullable(value);
        if (cleaned == null) {
            return List.of();
        }
        if (cleaned.startsWith("[") && cleaned.endsWith("]")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String item : cleaned.split(",")) {
            String parsed = unquote(item.trim());
            if (parsed != null && !parsed.isBlank()) {
                result.add(parsed);
            }
        }
        return List.copyOf(result);
    }

    private String unquote(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            return cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned;
    }
}
