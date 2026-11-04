package com.travelguide.rag.ingestion;

import com.travelguide.rag.RagModels.ChunkingOptions;
import com.travelguide.rag.RagModels.ChunkedDocument;
import com.travelguide.rag.RagModels.DocType;
import com.travelguide.rag.RagModels.KnowledgeChunk;
import com.travelguide.rag.RagModels.KnowledgeDocument;
import com.travelguide.rag.RagModels.ParentChunk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 结构感知的父子分片器。
 *
 * <p>固定长度切分会割裂旅行事实中的日期、票种和入口条件。本实现先按 H2
 * 划定语义边界，再对叙事段落和原子事实分别切分。父子块共享同一份元数据，
 * 父块用于回答时补充上下文，子块用于召回和引用。</p>
 */
public final class KnowledgeChunker {

    private static final Pattern H2_HEADING = Pattern.compile("^##\\s+(.+?)\\s*$");
    private static final Pattern LIST_LINE = Pattern.compile("^(?:[-*+]\\s+|\\d+[.)、]\\s*).+");
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？!?；;])|\\R");

    private final ChunkingOptions options;

    public KnowledgeChunker() {
        this(ChunkingOptions.defaults());
    }

    public KnowledgeChunker(ChunkingOptions options) {
        this.options = options;
    }

    public ChunkedDocument chunk(KnowledgeDocument document) {
        List<ParentChunk> parents = new ArrayList<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        int parentIndex = 0;
        int chunkIndex = 0;

        for (Section section : splitSections(document.body())) {
            // 资料说明属于 provenance 元数据，不进入用户事实召回。
            if ("资料说明".equals(section.heading()) || section.text().isBlank()) {
                continue;
            }

            parentIndex++;
            String parentId = document.metadata().docId() + "#p" + parentIndex;
            parents.add(new ParentChunk(parentId, document.metadata().docId(), section.heading(), section.text()));

            List<String> pieces = shouldUseAtomicChunks(document.metadata().docType())
                    ? splitAtomic(section.text())
                    : splitNarrative(section.text());
            for (String piece : pieces) {
                String rawText = piece.strip();
                if (rawText.isBlank()) {
                    continue;
                }
                chunkIndex++;
                chunks.add(new KnowledgeChunk(
                        document.metadata().docId() + "#c" + chunkIndex,
                        parentId,
                        document.metadata(),
                        section.heading(),
                        sectionFactTypes(document, section.heading(), rawText),
                        rawText,
                        buildEmbeddingText(document, section.heading(), rawText)
                ));
            }
        }
        return new ChunkedDocument(document, parents, chunks);
    }

    public List<ChunkedDocument> chunkAll(List<KnowledgeDocument> documents) {
        return documents.stream().map(this::chunk).toList();
    }

    private boolean shouldUseAtomicChunks(DocType type) {
        // 开放、票务、规则和交通需要单条事实可独立回答，避免不同条件被拼在一起。
        return switch (type) {
            case VISIT, NOTICE, TRANSIT -> true;
            default -> false;
        };
    }

    private List<Section> splitSections(String body) {
        List<Section> sections = new ArrayList<>();
        String heading = "正文";
        StringBuilder buffer = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : body.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                inCodeFence = !inCodeFence;
            }
            var matcher = H2_HEADING.matcher(line);
            if (!inCodeFence && matcher.matches()) {
                addSection(sections, heading, buffer);
                heading = matcher.group(1).strip();
                buffer.setLength(0);
            } else {
                buffer.append(line).append('\n');
            }
        }
        addSection(sections, heading, buffer);
        return sections;
    }

    private void addSection(List<Section> sections, String heading, StringBuilder buffer) {
        String text = buffer.toString().strip();
        if (!text.isBlank()) {
            sections.add(new Section(heading, text));
        }
    }

    private List<String> splitAtomic(String text) {
        List<String> units = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();

        for (String line : text.split("\\R", -1)) {
            String stripped = line.strip();
            if (stripped.isBlank()) {
                flush(units, paragraph);
                continue;
            }
            if (LIST_LINE.matcher(stripped).matches()) {
                flush(units, paragraph);
                // 每个列表项就是一条可独立核验的事实或建议。
                units.addAll(splitOversized(stripped, options.atomicMax()));
            } else {
                if (!paragraph.isEmpty()) {
                    paragraph.append('\n');
                }
                paragraph.append(stripped);
            }
        }
        flush(units, paragraph);
        return units;
    }

    private void flush(List<String> units, StringBuilder paragraph) {
        if (!paragraph.isEmpty()) {
            units.addAll(splitOversized(paragraph.toString().strip(), options.atomicMax()));
            paragraph.setLength(0);
        }
    }

    private List<String> splitOversized(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return List.of(text);
        }
        List<String> result = new ArrayList<>();
        int cursor = 0;
        while (cursor < text.length()) {
            int end = Math.min(text.length(), cursor + maxChars);
            if (end < text.length()) {
                int boundary = lastBoundary(text, cursor + maxChars / 2, end);
                if (boundary > cursor) {
                    end = boundary;
                }
            }
            String piece = text.substring(cursor, end).strip();
            if (!piece.isBlank()) {
                result.add(piece);
            }
            cursor = end;
        }
        return result;
    }

    private List<String> splitNarrative(String text) {
        if (text.length() <= options.narrativeMax()) {
            return List.of(text);
        }
        if (isListDominated(text)) {
            return splitListGroups(text);
        }
        return splitSentencesWithOverlap(text);
    }

    private boolean isListDominated(String text) {
        int total = 0;
        int listChars = 0;
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            total += stripped.length();
            if (LIST_LINE.matcher(stripped).matches()) {
                listChars += stripped.length();
            }
        }
        return total > 0 && listChars * 2 > total;
    }

    private List<String> splitListGroups(String text) {
        List<String> groups = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (stripped.isBlank()) {
                continue;
            }
            List<String> pieces = splitOversized(stripped, options.narrativeMax());
            for (String piece : pieces) {
                int nextLength = current.isEmpty() ? piece.length() : current.length() + 1 + piece.length();
                if (!current.isEmpty() && nextLength > options.narrativeMax()) {
                    groups.add(current.toString());
                    current.setLength(0);
                }
                if (!current.isEmpty()) {
                    current.append('\n');
                }
                current.append(piece);
            }
        }
        if (!current.isEmpty()) {
            groups.add(current.toString());
        }
        return groups;
    }

    private List<String> splitSentencesWithOverlap(String text) {
        List<String> sentences = new ArrayList<>();
        for (String value : SENTENCE_BOUNDARY.split(text)) {
            String sentence = value.strip();
            if (!sentence.isBlank()) {
                sentences.addAll(splitOversized(sentence, options.narrativeMax()));
            }
        }

        List<String> result = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLength = 0;
        for (String sentence : sentences) {
            int nextLength = currentLength + (current.isEmpty() ? 0 : 1) + sentence.length();
            if (!current.isEmpty() && nextLength > options.narrativeMax()) {
                result.add(String.join("\n", current));
                current = carryOverlap(current);
                currentLength = String.join("\n", current).length();
                // 重叠只是上下文增强，不能突破硬上限；必要时放弃重叠。
                if (!current.isEmpty() && currentLength + 1 + sentence.length() > options.narrativeMax()) {
                    current = new ArrayList<>();
                    currentLength = 0;
                }
            }
            current.add(sentence);
            currentLength += (current.size() == 1 ? 0 : 1) + sentence.length();
        }
        if (!current.isEmpty()) {
            result.add(String.join("\n", current));
        }
        return result;
    }

    private List<String> carryOverlap(List<String> current) {
        if (options.narrativeOverlap() == 0) {
            return new ArrayList<>();
        }
        List<String> overlap = new ArrayList<>();
        int length = 0;
        for (int index = current.size() - 1; index >= 0; index--) {
            String sentence = current.get(index);
            if (!overlap.isEmpty() && length + sentence.length() > options.narrativeOverlap()) {
                break;
            }
            overlap.add(0, sentence);
            length += sentence.length();
        }
        return overlap;
    }

    private int lastBoundary(String text, int minimum, int maximum) {
        for (int index = maximum - 1; index >= minimum; index--) {
            char value = text.charAt(index);
            if ("\n。！？!?；;".indexOf(value) >= 0) {
                return index + 1;
            }
        }
        return maximum;
    }

    private String buildEmbeddingText(KnowledgeDocument document, String heading, String rawText) {
        String aliases = String.join("、", document.metadata().aliases());
        return document.metadata().title() + "｜" + aliases + "｜" + heading + "\n" + rawText;
    }

    private List<String> sectionFactTypes(
            KnowledgeDocument document,
            String heading,
            String rawText
    ) {
        String normalizedHeading = heading.replace(" ", "");
        Set<String> mapped = new LinkedHashSet<>();
        if (normalizedHeading.contains("开放时间")) {
            mapped.add("OPEN_HOURS");
        }
        if (normalizedHeading.contains("门票") || normalizedHeading.contains("票种")) {
            mapped.add("TICKET");
        }
        if (normalizedHeading.contains("预约") || normalizedHeading.contains("证件")) {
            mapped.add("BOOKING");
        }
        if (normalizedHeading.contains("入口") || normalizedHeading.contains("到达")) {
            mapped.add("ENTRANCE");
            mapped.add("ROUTE");
        }
        if (normalizedHeading.contains("游览顺序")
                || normalizedHeading.contains("参观顺序")
                || normalizedHeading.contains("交通")) {
            mapped.add("ROUTE");
        }
        if (normalizedHeading.contains("停留时长")) {
            mapped.add("DURATION");
        }
        if (normalizedHeading.contains("历史")) {
            mapped.add("HISTORY");
        }
        if (normalizedHeading.contains("看点")
                || normalizedHeading.contains("楼层层级")
                || normalizedHeading.contains("讲解要点")) {
            mapped.add("HIGHLIGHT");
        }
        if (normalizedHeading.contains("适合")) {
            mapped.add("SUITABILITY");
        }
        if (mapped.isEmpty()) {
            mapped.addAll(document.metadata().factTypes());
        }
        String normalizedText = rawText.toLowerCase(Locale.ROOT);
        if (normalizedText.contains("门票") || normalizedText.contains("票价")
                || normalizedText.contains("免费") || normalizedText.contains("收费")) {
            mapped.add("TICKET");
        }
        if (normalizedText.contains("开放时间") || normalizedText.contains("闭馆")
                || normalizedText.contains("停止入场")) {
            mapped.add("OPEN_HOURS");
        }
        if (normalizedText.contains("餐饮") || normalizedText.contains("餐厅")
                || normalizedText.contains("吃饭") || normalizedText.contains("用餐")) {
            mapped.add("DINING");
        }
        return List.copyOf(mapped);
    }

    private record Section(String heading, String text) {
    }
}
