package com.langmem4j.core.store;

import com.langmem4j.core.memory.Memory;

import java.util.List;
import java.util.Optional;

/**
 * Storage-agnostic contract for persisting and retrieving long-term memories.
 * <p>
 * Every operation is scoped to a {@code namespace}, which acts as the
 * isolation boundary (per-user, per-session, per-tenant, etc.). Namespace
 * handling is entirely the caller's responsibility; implementations must not
 * merge or leak data across namespaces.
 * <p>
 * {@link #search(String, String, int)} accepts a plain-text query string.
 * Concrete implementations are responsible for converting the text into an
 * embedding vector (typically via an injected {@code EmbeddingGenerator})
 * and performing the actual nearest-neighbour lookup.
 *
 * @see com.langmem4j.core.memory.Memory
 * @see com.langmem4j.core.embedding.EmbeddingGenerator
 */
public interface MemoryStore {

    /**
     * Writes or updates a memory. If a memory with the same
     * {@code namespace + key} already exists, it is overwritten.
     */
    void upsert(String namespace, Memory memory);

    /**
     * Batch variant of {@link #upsert(String, Memory)}.
     * Default implementation iterates and calls {@link #upsert}.
     */
    default void upsertBatch(String namespace, List<Memory> memories) {
        memories.forEach(m -> upsert(namespace, m));
    }

    /**
     * Looks up a memory by exact key within the namespace.
     *
     * @return the memory, or {@link Optional#empty()} if no match exists
     */
    Optional<Memory> getByKey(String namespace, String key);

    /**
     * Lists all keys currently stored under the given namespace.
     * Ordering is implementation-defined; callers must not rely on it.
     */
    List<String> listKeys(String namespace);

    /**
     * Semantic search: returns the {@code limit} memories whose content is
     * most relevant to the given free-text query.
     * <p>
     * Implementations typically generate an embedding for {@code queryText}
     * and run a nearest-neighbour search over stored vectors. The returned
     * list must not be null; may be empty if no results match.
     * <p>
     * Delegates to {@link #search(String, String, int, MemoryFilter)} with
     * {@link MemoryFilter#NONE} by default.
     *
     * @param namespace the scope to search within
     * @param queryText free-text query string
     * @param limit     maximum number of results; must be positive
     * @see #search(String, String, int, MemoryFilter)
     */
    default List<Memory> search(String namespace, String queryText, int limit) {
        return search(namespace, queryText, limit, MemoryFilter.NONE);
    }

    /**
     * Semantic search with an optional filter applied before ranking.
     * <p>
     * <b>Default implementation</b> is <em>deliberately absent</em> for the
     * filtered variant: SPI implementations MUST override this method so
     * they can push filter conditions down to the storage engine where
     * possible. If you are writing a trivial store, the following
     * "pull-and-prune" pattern is acceptable:
     *
     * <pre>{@code
     * @Override public List<Memory> search(String ns, String q, int limit, MemoryFilter f) {
     *     // 1. call your unfiltered inner search (never the public 3-arg!)
     *     // 2. prune via f.matchesMetadata(m.metadata())
     *     // 3. apply f.minScore() if applicable
     * }
     * }</pre>
     *
     * @param namespace the scope to search within
     * @param queryText free-text query string
     * @param limit     maximum number of results; must be positive
     * @param filter    predicates to narrow the candidate set;
     *                  callers should pass {@link MemoryFilter#NONE}
     *                  (never {@code null}) for an unfiltered search.
     */
    List<Memory> search(String namespace, String queryText,
                        int limit, MemoryFilter filter);

    /**
     * Deletes the memory identified by {@code namespace + key}.
     * No-op if the key does not exist.
     */
    void deleteByKey(String namespace, String key);

    /**
     * Removes every memory stored under the given namespace.
     * No-op if the namespace is empty or does not exist.
     */
    void clearNamespace(String namespace);
}
