package com.travelguide.rag.retrieval;

import com.travelguide.rag.persistence.PgEmbeddingCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Embedding 两级缓存：进程内热点 + PostgreSQL 持久化。
 */
public final class CachedEmbeddingProvider implements EmbeddingProvider {

    private final EmbeddingProvider delegate;
    private final PgEmbeddingCache cache;
    private final Map<String, float[]> memory = new LinkedHashMap<>();
    private volatile boolean loaded;

    public CachedEmbeddingProvider(EmbeddingProvider delegate, PgEmbeddingCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    @Override
    public synchronized float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public synchronized List<float[]> embedAll(List<String> texts) {
        ensureLoaded();
        List<String> hashes = texts.stream().map(cache::hash).toList();
        List<Integer> missingIndexes = new ArrayList<>();
        for (int index = 0; index < hashes.size(); index++) {
            if (!memory.containsKey(hashes.get(index))) {
                missingIndexes.add(index);
            }
        }
        if (!missingIndexes.isEmpty()) {
            List<float[]> generated = delegate.embedAll(
                    missingIndexes.stream().map(texts::get).toList()
            );
            Map<String, float[]> additions = new LinkedHashMap<>();
            for (int index = 0; index < missingIndexes.size(); index++) {
                String hash = hashes.get(missingIndexes.get(index));
                float[] vector = generated.get(index);
                memory.put(hash, vector);
                additions.put(hash, vector);
            }
            cache.saveAll(modelId(), additions);
        }
        return hashes.stream().map(memory::get).toList();
    }

    @Override
    public String modelId() {
        return delegate.modelId();
    }

    private void ensureLoaded() {
        if (loaded) {
            return;
        }
        memory.putAll(cache.load(modelId()));
        loaded = true;
    }
}
