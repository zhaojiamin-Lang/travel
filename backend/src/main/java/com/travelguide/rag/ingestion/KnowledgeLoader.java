package com.travelguide.rag.ingestion;

import com.travelguide.rag.RagModels.KnowledgeDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 从 knowledge 目录加载 Markdown 知识文档。
 */
public final class KnowledgeLoader {

    private final FrontMatterParser parser;

    public KnowledgeLoader() {
        this(new FrontMatterParser());
    }

    public KnowledgeLoader(FrontMatterParser parser) {
        this.parser = parser;
    }

    public List<KnowledgeDocument> loadDirectory(Path knowledgeRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(knowledgeRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    // README 是规则说明，不是知识语料，不能写入索引。
                    .filter(path -> !"README.md".equals(path.getFileName().toString()))
                    .filter(path -> !path.normalize().toString().contains("_sources"))
                    .sorted(Comparator.comparing(Path::toString))
                    .map(path -> {
                        try {
                            return parser.parse(path);
                        } catch (IOException exception) {
                            throw new KnowledgeLoadException("读取知识文档失败: " + path, exception);
                        }
                    })
                    .toList();
        }
    }

    public static final class KnowledgeLoadException extends RuntimeException {
        public KnowledgeLoadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
