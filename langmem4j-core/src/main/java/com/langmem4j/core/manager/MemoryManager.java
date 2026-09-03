package com.langmem4j.core.manager;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * High-level facade over {@link MemoryStore} + {@link EmbeddingGenerator} —
 * the intended entry point for most langMem4j users.
 * <p>
 * <strong>Why this exists:</strong> raw {@link MemoryStore} accepts
 * {@link Memory} records and delegates to a storage backend. It deliberately
 * knows nothing about embedding generation, default namespaces, or conflict
 * policy — those concerns belong here. Use {@code MemoryManager} if you want
 * a single API surface that handles the common case cleanly.
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withEmbeddingGenerator(myGenerator)   // optional; enables cosine search
 *         .withDefaultNamespace("user_alice")    // optional; saves a param per call
 *         .build();
 *
 * manager.add("favorite_food", "Alice loves hot pot");
 * List<Memory> hits = manager.search("what does Alice eat?", 5);
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * Depends entirely on the wrapped {@link MemoryStore} — the manager itself
 * is stateless and thread-safe as long as the store is. The default
 * {@link InMemoryMemoryStore} is thread-safe.
 */
public class MemoryManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryManager.class);

    private final MemoryStore store;
    private final EmbeddingGenerator embeddingGenerator;
    private final String defaultNamespace;
    private final MemoryDecayPolicy decayPolicy;
    private final MemoryMergePolicy mergePolicy;
    private final MemoryCompactionPolicy compactionPolicy;

    private MemoryManager(Builder builder) {
        this.store = builder.store;
        this.embeddingGenerator = builder.embeddingGenerator;
        this.defaultNamespace = builder.defaultNamespace;
        this.decayPolicy = builder.decayPolicy;
        this.mergePolicy = builder.mergePolicy;
        this.compactionPolicy = builder.compactionPolicy;
    }

    // ================================================================
    // Static factory / Builder
    // ================================================================

    /**
     * Starts a builder backed by an {@link InMemoryMemoryStore}.
     */
    public static Builder inMemory() {
        return new Builder().store(new InMemoryMemoryStore());
    }

    /**
     * Starts a builder with a caller-provided store.
     */
    public static Builder withStore(MemoryStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        return new Builder().store(store);
    }

    /**
     * Fluent builder for {@link MemoryManager}.
     */
    public static final class Builder {
        private MemoryStore store;
        private EmbeddingGenerator embeddingGenerator;
        private String defaultNamespace;
        private MemoryDecayPolicy decayPolicy = MemoryDecayPolicy.NONE;
        private MemoryMergePolicy mergePolicy = MemoryMergePolicy.NONE;
        private MemoryCompactionPolicy compactionPolicy = MemoryCompactionPolicy.NONE;

        Builder store(MemoryStore store) { this.store = store; return this; }

        /**
         * Sets the generator used to produce embedding vectors for
         * {@link MemoryManager#add} and semantic search. Optional; if
         * omitted, {@link MemoryManager#search} falls back to whatever
         * the store's default search strategy is (e.g. substring match
         * for InMemoryMemoryStore).
         */
        public Builder withEmbeddingGenerator(EmbeddingGenerator generator) {
            this.embeddingGenerator = generator;
            return this;
        }

        /**
         * Sets the fallback namespace used by single-arg variants of
         * {@code add}, {@code search}, etc. If not set, those variants
         * throw to force the caller to be explicit.
         */
        public Builder withDefaultNamespace(String namespace) {
            this.defaultNamespace = namespace;
            return this;
        }

        /**
         * Sets the decay policy used to filter and re-rank stale memories
         * during {@code search()}. Also enables {@code lastAccessedAt}
         * refresh on {@code get()}. Default is
         * {@link MemoryDecayPolicy#NONE} (no decay).
         */
        public Builder withDecayPolicy(MemoryDecayPolicy policy) {
            this.decayPolicy = policy == null ? MemoryDecayPolicy.NONE : policy;
            return this;
        }

        /**
         * Sets the merge policy invoked when {@code add()} or
         * {@code addAll()} encounters an existing memory at the same key.
         * Default is {@link MemoryMergePolicy#NONE}
         * (classic overwrite upsert).
         */
        public Builder withMergePolicy(MemoryMergePolicy policy) {
            this.mergePolicy = policy == null ? MemoryMergePolicy.NONE : policy;
            return this;
        }

        /**
         * Sets the compaction policy used by {@link MemoryManager#compact} to
         * summarize fragmented memories into fewer, denser records. Default is
         * {@link MemoryCompactionPolicy#NONE} (no compaction).
         */
        public Builder withCompactionPolicy(MemoryCompactionPolicy policy) {
            this.compactionPolicy = policy == null ? MemoryCompactionPolicy.NONE : policy;
            return this;
        }

        public MemoryManager build() {
            if (store == null) {
                throw new IllegalStateException("store must be set — use MemoryManager.inMemory() or withStore()");
            }
            return new MemoryManager(this);
        }
    }

    // ================================================================
    // Write API — add / remove / clear
    // ================================================================

    /**
     * Stores (or overwrites) a memory under the default namespace.
     *
     * @throws IllegalStateException if no default namespace was configured
     */
    public void add(String key, String value) {
        add(defaultNs(), key, value, null);
    }

    /**
     * Stores (or overwrites) a memory with optional metadata under the
     * default namespace.
     */
    public void add(String key, String value, Map<String, Object> metadata) {
        add(defaultNs(), key, value, metadata);
    }

    /**
     * Stores (or overwrites) a memory under an explicit namespace.
     * <p>
     * If a merge policy is configured and a memory already exists at this
     * key, the two are merged via {@link MemoryMergePolicy#merge}.
     * Otherwise the incoming memory overwrites the existing one.
     * <p>
     * If the record has no pre-computed embedding and an
     * {@link EmbeddingGenerator} is configured, the vector is generated
     * transparently before writing.
     */
    public void add(String namespace, String key, String value, Map<String, Object> metadata) {
        Memory memory = Memory.of(namespace, key, value, metadata);

        if (embeddingGenerator != null) {
            float[] vector = embeddingGenerator.embed(value);
            memory = memory.withEmbedding(vector);
            log.debug("auto-embedded ns={} key={} dim={}", namespace, key, vector.length);
        }

        memory = applyMerge(namespace, memory);
        store.upsert(namespace, memory);
    }

    /**
     * Stores a pre-built {@link Memory} instance directly. Useful when
     * you've already constructed the record (e.g. with a pre-computed
     * embedding or complex metadata).
     */
    public void add(Memory memory) {
        Memory toUpsert = memory;
        if (toUpsert.embeddingVector() == null && embeddingGenerator != null) {
            toUpsert = toUpsert.withEmbedding(embeddingGenerator.embed(memory.value()));
        }
        toUpsert = applyMerge(memory.namespace(), toUpsert);
        store.upsert(memory.namespace(), toUpsert);
    }

    /**
     * Batch variant of {@link #add(String, String, String, Map)}.
     * <p>
     * Each memory goes through the same embedding enrichment and merge
     * policy as {@link #add(Memory)}. Merge is applied per-record (each
     * memory is checked against the store individually) to ensure
     * semantic consistency with {@code add()}.
     */
    public void addAll(String namespace, List<Memory> memories) {
        List<Memory> enriched = memories.stream()
                .map(m -> {
                    if (m.embeddingVector() == null && embeddingGenerator != null) {
                        return m.withEmbedding(embeddingGenerator.embed(m.value()));
                    }
                    return m;
                })
                .map(m -> applyMerge(namespace, m))
                .collect(Collectors.toList());
        store.upsertBatch(namespace, enriched);
    }

    /** Removes a memory by key from the default namespace. */
    public void remove(String key) {
        store.deleteByKey(defaultNs(), key);
    }

    /** Removes a memory by key from the given namespace. */
    public void remove(String namespace, String key) {
        store.deleteByKey(namespace, key);
    }

    /** Wipes every memory under the default namespace. */
    public void clear() {
        store.clearNamespace(defaultNs());
    }

    /** Wipes every memory under the given namespace. */
    public void clear(String namespace) {
        store.clearNamespace(namespace);
    }

    // ================================================================
    // Read API — get / list / search
    // ================================================================

    /**
     * Looks up a single memory by key in the default namespace.
     * <p>
     * If a decay policy is active, the returned memory's
     * {@code lastAccessedAt} is refreshed to now and written back to the
     * store, pushing back the decay clock.
     */
    public Optional<Memory> get(String key) {
        return refreshAccessedAt(store.getByKey(defaultNs(), key));
    }

    /**
     * Looks up a single memory by key in the given namespace.
     * <p>
     * If a decay policy is active, the returned memory's
     * {@code lastAccessedAt} is refreshed to now and written back to the
     * store.
     */
    public Optional<Memory> get(String namespace, String key) {
        return refreshAccessedAt(store.getByKey(namespace, key));
    }

    /** Lists all memory keys in the default namespace. */
    public List<String> keys() {
        return store.listKeys(defaultNs());
    }

    /** Lists all memory keys in the given namespace. */
    public List<String> keys(String namespace) {
        return store.listKeys(namespace);
    }

    /**
     * Semantic search in the default namespace with a default limit of 5.
     */
    public List<Memory> search(String query) {
        return search(defaultNs(), query, 5);
    }

    /**
     * Semantic search in the default namespace with an explicit limit.
     */
    public List<Memory> search(String query, int limit) {
        return search(defaultNs(), query, limit);
    }

    /**
     * Semantic search in the given namespace.
     * <p>
     * The store is responsible for turning {@code query} into a vector
     * via the {@link EmbeddingGenerator} that was injected at
     * construction time. If no generator is configured, the store's
     * default fallback strategy is used (e.g. substring match in
     * InMemoryMemoryStore).
     * <p>
     * If a decay policy is active, results are filtered (memories below
     * {@link MemoryDecayPolicy#pruneThreshold()} are dropped) and
     * re-ranked by decay factor (higher = more recent = first).
     */
    public List<Memory> search(String namespace, String query, int limit) {
        return applyDecay(store.search(namespace, query, limit));
    }

    /**
     * Semantic search with a metadata filter — the store pushes the
     * constraints down to the storage engine when possible (see
     * {@link MemoryStore#search(String, String, int, MemoryFilter)}).
     *
     * <pre>{@code
     * List<Memory> userPrefs = manager.search("user_alice", "food", 10,
     *         MemoryFilter.builder()
     *                 .metadata("category", "preference")
     *                 .metadata("source",   "user_input")
     *                 .build());
     * }</pre>
     */
    public List<Memory> search(String namespace, String query, int limit, MemoryFilter filter) {
        return applyDecay(store.search(namespace, query, limit,
                filter == null ? MemoryFilter.NONE : filter));
    }

    /** Default-namespace + explicit-limit variant of the filtered search. */
    public List<Memory> search(String query, int limit, MemoryFilter filter) {
        return search(defaultNs(), query, limit, filter);
    }

    /** Default-namespace + default-limit variant of the filtered search. */
    public List<Memory> search(String query, MemoryFilter filter) {
        return search(defaultNs(), query, 5, filter);
    }

    /** Returns the underlying store. Useful for tests and edge cases. */
    public MemoryStore store() {
        return store;
    }

    /** Returns the configured generator (may be null). */
    public Optional<EmbeddingGenerator> embeddingGenerator() {
        return Optional.ofNullable(embeddingGenerator);
    }

    /** Returns the default namespace (may be null if never set). */
    public Optional<String> defaultNamespace() {
        return Optional.ofNullable(defaultNamespace);
    }

    /** Returns the configured decay policy (never null). */
    public MemoryDecayPolicy decayPolicy() {
        return decayPolicy;
    }

    /** Returns the configured merge policy (never null). */
    public MemoryMergePolicy mergePolicy() {
        return mergePolicy;
    }

    /** Returns the configured compaction policy (never null). */
    public MemoryCompactionPolicy compactionPolicy() {
        return compactionPolicy;
    }

    // ================================================================
    // Compaction API
    // ================================================================

    /**
     * Compacts (summarizes) all memories in the given namespace.
     * <p>
     * Fetches all memories via {@code listKeys + getByKey}, runs them through
     * the configured {@link MemoryCompactionPolicy}, then deletes all old
     * memories and stores the compacted replacements. Embeddings are
     * re-generated for compacted memories if an {@link EmbeddingGenerator} is
     * configured.
     * <p>
     * If the compaction policy is {@link MemoryCompactionPolicy#NONE}, this
     * method is a no-op.
     * <p>
     * <b>Requires {@link MemoryStore#listKeys(String)} support.</b>
     * The {@code LangGraph4jStoreAdapter} does not implement {@code listKeys}
     * (the langgraph4j Store SPI has no equivalent method) and will throw
     * {@link UnsupportedOperationException}. Use {@code compact()} only with
     * {@code InMemoryMemoryStore} or {@code QdrantMemoryStore} backends.
     *
     * @param namespace the namespace to compact
     */
    public void compact(String namespace) {
        if (compactionPolicy == MemoryCompactionPolicy.NONE) return;

        List<String> keys = store.listKeys(namespace);
        if (keys.isEmpty()) return;

        List<Memory> all = new ArrayList<>();
        for (String key : keys) {
            store.getByKey(namespace, key).ifPresent(all::add);
        }
        if (all.isEmpty()) return;

        List<Memory> compacted = compactionPolicy.compact(namespace, all);
        log.info("compact ns={} before={} after={}", namespace, all.size(), compacted.size());

        // Delete all old memories
        for (Memory m : all) {
            store.deleteByKey(namespace, m.key());
        }

        // Store compacted replacements (with embedding if generator available)
        for (Memory m : compacted) {
            Memory toStore = m;
            if (m.embeddingVector() == null && embeddingGenerator != null) {
                toStore = m.withEmbedding(embeddingGenerator.embed(m.value()));
            }
            store.upsert(namespace, toStore);
        }
    }

    /**
     * Compacts memories in the default namespace.
     */
    public void compact() {
        compact(defaultNs());
    }

    // ================================================================
    // Internal — merge, decay, refresh
    // ================================================================

    /**
     * If a merge policy is configured, checks for an existing memory at the
     * same key and merges it with the incoming one.
     */
    private Memory applyMerge(String namespace, Memory incoming) {
        if (mergePolicy == MemoryMergePolicy.NONE) return incoming;
        Optional<Memory> existing = store.getByKey(namespace, incoming.key());
        if (existing.isPresent()) {
            Memory merged = mergePolicy.merge(existing.get(), incoming);
            log.debug("merged ns={} key={} existing.len={} incoming.len={} → merged.len={}",
                    namespace, incoming.key(),
                    existing.get().value().length(), incoming.value().length(),
                    merged.value().length());
            return merged;
        }
        return incoming;
    }

    /**
     * If a decay policy is configured:
     * <ol>
     *   <li><b>Filter</b> — drop memories whose decay factor has fallen below
     *       {@link MemoryDecayPolicy#pruneThreshold()}.</li>
     *   <li><b>Re-rank</b> — sort surviving memories by decay factor descending
     *       (more recent first). The sort is stable, so ties preserve the
     *       store's original cosine-similarity order.</li>
     * </ol>
     * Note: search results are NOT written back (no lastAccessedAt refresh)
     * to avoid write amplification. Use {@link #get} for explicit refresh.
     */
    private List<Memory> applyDecay(List<Memory> results) {
        if (decayPolicy == MemoryDecayPolicy.NONE || results.isEmpty()) {
            return results;
        }
        long now = System.currentTimeMillis();
        float threshold = decayPolicy.pruneThreshold();
        return results.stream()
                .filter(m -> decayPolicy.decayFactor(
                        m.createdAt(), m.lastAccessedAt(), now) >= threshold)
                .sorted(Comparator.comparingDouble(
                        (Memory m) -> decayPolicy.decayFactor(
                                m.createdAt(), m.lastAccessedAt(), now)
                ).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Refreshes {@code lastAccessedAt} on the returned memory and writes it
     * back to the store. Only active when a decay policy is configured.
     * This is the mechanism that "refreshes the decay clock" — without it,
     * memories would decay based on creation time alone.
     */
    private Optional<Memory> refreshAccessedAt(Optional<Memory> found) {
        if (found.isEmpty() || decayPolicy == MemoryDecayPolicy.NONE) {
            return found;
        }
        Memory m = found.get();
        Memory refreshed = m.withLastAccessedAt(System.currentTimeMillis());
        store.upsert(refreshed.namespace(), refreshed);
        log.debug("refreshed lastAccessedAt ns={} key={}", m.namespace(), m.key());
        return Optional.of(refreshed);
    }

    private String defaultNs() {
        if (defaultNamespace == null) {
            throw new IllegalStateException(
                    "No default namespace configured. Pass an explicit namespace or "
                    + "call Builder.withDefaultNamespace(\"...\").build()");
        }
        return defaultNamespace;
    }
}
