package com.langmem4j.core.store.inmemory;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Thread-safe in-memory {@link MemoryStore} backed by nested
 * {@link ConcurrentHashMap} instances.
 * <p>
 * Two search strategies are available:
 * <ul>
 *   <li><b>Default (no generator):</b> substring match on {@code value}.
 *       Suitable for development and unit tests where a real embedding
 *       backend is unnecessary.</li>
 *   <li><b>With {@link EmbeddingGenerator}:</b> cosine similarity between
 *       the query embedding and each stored memory's
 *       {@link Memory#embeddingVector()}. Only memories that have a
 *       pre-computed embedding participate in the ranking.</li>
 * </ul>
 * Not persistent; data is lost on JVM exit.
 */
public class InMemoryMemoryStore implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryMemoryStore.class);

    /** namespace -> (key -> memory) */
    private final ConcurrentMap<String, ConcurrentMap<String, Memory>> store = new ConcurrentHashMap<>();

    /** Optional: when set, search uses cosine similarity instead of substring match. */
    private final EmbeddingGenerator embeddingGenerator;

    /**
     * Creates an instance with the default substring-match search.
     */
    public InMemoryMemoryStore() {
        this(null);
    }

    /**
     * Creates an instance using {@code embeddingGenerator} for semantic
     * cosine-similarity search.
     *
     * @param embeddingGenerator used to embed the search query; may be null,
     *                           in which case substring-match search is used
     */
    public InMemoryMemoryStore(EmbeddingGenerator embeddingGenerator) {
        this.embeddingGenerator = embeddingGenerator;
    }

    @Override
    public void upsert(String namespace, Memory memory) {
        ConcurrentMap<String, Memory> bucket = store.computeIfAbsent(namespace, k -> new ConcurrentHashMap<>());
        bucket.put(memory.key(), memory);
        log.debug("upsert ns={} key={}", namespace, memory.key());
    }

    @Override
    public Optional<Memory> getByKey(String namespace, String key) {
        ConcurrentMap<String, Memory> bucket = store.get(namespace);
        if (bucket == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bucket.get(key));
    }

    @Override
    public List<String> listKeys(String namespace) {
        ConcurrentMap<String, Memory> bucket = store.get(namespace);
        if (bucket == null) {
            return List.of();
        }
        return new ArrayList<>(bucket.keySet());
    }

    @Override
    public List<Memory> search(String namespace, String queryText, int limit) {
        return search(namespace, queryText, limit, MemoryFilter.NONE);
    }

    @Override
    public List<Memory> search(String namespace, String queryText,
                               int limit, MemoryFilter filter) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        MemoryFilter f = filter == null ? MemoryFilter.NONE : filter;
        ConcurrentMap<String, Memory> bucket = store.get(namespace);
        if (bucket == null || bucket.isEmpty()) {
            return List.of();
        }

        // Apply metadata filter BEFORE ranking so substring / cosine strategies
        // only walk the already-qualified candidates (cheap + deterministic).
        List<Memory> candidates = bucket.values().stream()
                .filter(m -> f.matchesMetadata(m.metadata()))
                .toList();
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<Memory> ranked = (embeddingGenerator != null)
                ? searchByCosine(candidates, queryText, limit, f.hasMinScore() ? f.minScore() : Float.NaN)
                : searchBySubstring(candidates, queryText, limit);
        return ranked;
    }

    /**
     * Substring-match fallback: ranks memories whose {@code value} contains
     * {@code queryText} (case-insensitive). Non-matches are excluded.
     */
    private List<Memory> searchBySubstring(List<Memory> candidates, String queryText, int limit) {
        String needle = queryText.toLowerCase();
        return candidates.stream()
                .filter(m -> m.value().toLowerCase().contains(needle))
                .limit(limit)
                .toList();
    }

    /**
     * Cosine-similarity ranking. Requires that stored memories have an
     * embedding vector; those without one are skipped. If {@code minScore}
     * is a valid number (not NaN), candidates below that score are dropped.
     */
    private List<Memory> searchByCosine(List<Memory> candidates, String queryText,
                                        int limit, float minScore) {
        float[] queryVector = embeddingGenerator.embed(queryText);

        record ScoredMemory(Memory memory, float score) {}

        return candidates.stream()
                .filter(m -> m.embeddingVector() != null
                        && m.embeddingVector().length == queryVector.length)
                .map(m -> new ScoredMemory(m, cosine(queryVector, m.embeddingVector())))
                .filter(s -> !Float.isNaN(s.score()))
                .filter(s -> Float.isNaN(minScore) || s.score() >= minScore)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(limit)
                .map(ScoredMemory::memory)
                .toList();
    }

    /**
     * Computes cosine similarity between two equal-length dense vectors.
     * Returns {@link Float#NaN} if either vector has zero norm.
     */
    static float cosine(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vectors must have equal length: "
                    + a.length + " vs " + b.length);
        }
        float dot = 0f;
        float normA = 0f;
        float normB = 0f;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        float denom = (float) (Math.sqrt(normA) * Math.sqrt(normB));
        return dot / denom; // NaN when denom == 0
    }

    @Override
    public void deleteByKey(String namespace, String key) {
        ConcurrentMap<String, Memory> bucket = store.get(namespace);
        if (bucket != null) {
            bucket.remove(key);
            log.debug("delete ns={} key={}", namespace, key);
        }
    }

    @Override
    public void clearNamespace(String namespace) {
        store.remove(namespace);
        log.debug("clear ns={}", namespace);
    }

    /**
     * Returns the total number of namespaces currently held.
     * Primarily useful for testing / diagnostics.
     */
    public int namespaceCount() {
        return store.size();
    }

    /**
     * Returns the number of memories stored under the given namespace.
     */
    public int sizeOf(String namespace) {
        ConcurrentMap<String, Memory> bucket = store.get(namespace);
        return bucket == null ? 0 : bucket.size();
    }
}
