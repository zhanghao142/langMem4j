package com.langmem4j.core.manager;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private MemoryManager(Builder builder) {
        this.store = builder.store;
        this.embeddingGenerator = builder.embeddingGenerator;
        this.defaultNamespace = builder.defaultNamespace;
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
        store.upsert(memory.namespace(), toUpsert);
    }

    /** Batch variant of {@link #add(String, String, String, Map)}. */
    public void addAll(String namespace, List<Memory> memories) {
        List<Memory> enriched = memories.stream()
                .map(m -> {
                    if (m.embeddingVector() == null && embeddingGenerator != null) {
                        return m.withEmbedding(embeddingGenerator.embed(m.value()));
                    }
                    return m;
                })
                .toList();
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

    /** Looks up a single memory by key in the default namespace. */
    public Optional<Memory> get(String key) {
        return store.getByKey(defaultNs(), key);
    }

    /** Looks up a single memory by key in the given namespace. */
    public Optional<Memory> get(String namespace, String key) {
        return store.getByKey(namespace, key);
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
     */
    public List<Memory> search(String namespace, String query, int limit) {
        return store.search(namespace, query, limit);
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
        return store.search(namespace, query, limit,
                filter == null ? MemoryFilter.NONE : filter);
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

    private String defaultNs() {
        if (defaultNamespace == null) {
            throw new IllegalStateException(
                    "No default namespace configured. Pass an explicit namespace or "
                    + "call Builder.withDefaultNamespace(\"...\").build()");
        }
        return defaultNamespace;
    }
}
