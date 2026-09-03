package com.langmem4j.store.langgraph4j;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import org.bsc.langgraph4j.store.Store;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bridges langMem4j's {@link MemoryStore} SPI to a langgraph4j {@link Store}
 * implementation (e.g. {@link org.bsc.langgraph4j.store.InMemoryStore}).
 * <p>
 * Translation rules:
 * <ul>
 *   <li>{@code Memory.value → Store.Item.value} (as Object);
 *       round-trips to String because we always put a String.</li>
 *   <li>{@code Memory.metadata → Store.Item.metadata} (both Map&lt;String,Object&gt;).</li>
 *   <li>{@code Memory.embeddingVector}: not directly expressible on langgraph4j Item;
 *       round-trip is always {@code null}. Users who need embeddings should rely on
 *       the MemoryManager layer (it regenerates vectors on add anyway).</li>
 *   <li>{@code Memory.createdAt → Store.Item.createdAt};
 *       {@code Memory.lastAccessedAt → Store.Item.updatedAt}
 *       (the closest semantic match; decay policy uses lastAccessedAt / updatedAt).</li>
 *   <li>{@code listKeys} and {@code clearNamespace} are not exposed by the langgraph4j
 *       Store interface; these throw {@link UnsupportedOperationException}.</li>
 *   <li>{@code MemoryFilter} metadata matching / minScore are intentionally handled at
 *       the {@code MemoryStore} default-implementation layer (3-arg search → 4-arg
 *       search with NONE fallback + candidate expansion); this adapter only concerns
 *       itself with call delegation.</li>
 * </ul>
 */
public class LangGraph4jStoreAdapter implements MemoryStore {

    private final Store delegate;

    /**
     * Wraps any langgraph4j Store (InMemoryStore, RedisStore, etc.) as a
     * langMem4j-compatible MemoryStore.
     *
     * @param delegate the langgraph4j Store to wrap; must not be null
     */
    public LangGraph4jStoreAdapter(Store delegate) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate Store must not be null");
        }
        this.delegate = delegate;
    }

    // ------------------------------------------------------------------
    // Writes
    // ------------------------------------------------------------------

    @Override
    public void upsert(String namespace, Memory memory) {
        delegate.put(namespace, memory.key(), memory.value(), memory.metadata());
    }

    @Override
    public void upsertBatch(String namespace, List<Memory> memories) {
        for (Memory m : memories) {
            upsert(namespace, m);
        }
    }

    @Override
    public void deleteByKey(String namespace, String key) {
        delegate.delete(namespace, key);
    }

    @Override
    public void clearNamespace(String namespace) {
        throw new UnsupportedOperationException(
                "langgraph4j Store SPI does not expose a clearNamespace operation. "
                + "Iterate listKeys() + deleteByKey() as a workaround if needed.");
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Override
    public Optional<Memory> getByKey(String namespace, String key) {
        return delegate.get(namespace, key).map(it -> toMemory(it, namespace));
    }

    @Override
    public List<String> listKeys(String namespace) {
        throw new UnsupportedOperationException(
                "langgraph4j Store SPI does not expose a listKeys operation. "
                + "This adapter cannot synthesise keys without a broader API.");
    }

    @Override
    public List<Memory> search(String namespace, String queryText, int limit) {
        Store.SearchResult result = delegate.search(namespace, queryText, limit);
        return result.items().stream()
                .map(item -> toMemory(item, namespace))
                .collect(Collectors.toList());
    }

    @Override
    public List<Memory> search(String namespace, String queryText, int limit, MemoryFilter filter) {
        final MemoryFilter f = (filter == null) ? MemoryFilter.NONE : filter;
        if (f == MemoryFilter.NONE) {
            // Fast path: identical to the 3-arg variant.
            return search(namespace, queryText, limit);
        }
        // langgraph4j Store.search has no native filter / minScore API,
        // so we fall back to the "probe-limit expansion" strategy used by
        // InMemoryMemoryStore and documented in the MemoryStore SPI.
        // We ask langgraph4j for 10× as many candidates, then filter them
        // client-side via MemoryFilter.matchesMetadata(). Note: minScore
        // is intentionally ignored for this adapter because langgraph4j's
        // search result ranking is opaque (no exposed cosine score).
        int probeLimit = Math.min(limit * 10, 500);
        Store.SearchResult raw = delegate.search(namespace, queryText, probeLimit);
        return raw.items().stream()
                .map(item -> toMemory(item, namespace))
                .filter(m -> f.matchesMetadata(m.metadata()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Converts a langgraph4j {@link Store.Item} record into a langMem4j
     * {@link Memory} record.
     * <p>
     * The caller must provide the {@code namespace} explicitly because
     * langgraph4j's {@code Item} does not carry it; the value is exactly
     * the namespace that was passed to the originating search / get call.
     * This keeps Memory's non-blank namespace invariant happy.
     * <p>
     * Remaining mapping:
     * <ul>
     *   <li>{@code Store.Item.key() → Memory.key}</li>
     *   <li>{@code Store.Item.value() → Memory.value} (via String.valueOf)</li>
     *   <li>{@code Store.Item.metadata() → Memory.metadata}</li>
     *   <li>{@code Store.Item.createdAt() → Memory.createdAt}</li>
     *   <li>{@code Store.Item.updatedAt() → Memory.lastAccessedAt}</li>
     *   <li>{@code embeddingVector → null} (langgraph4j Item has no vector)</li>
     * </ul>
     */
    static Memory toMemory(Store.Item item, String namespace) {
        String value = (item.value() == null) ? "" : String.valueOf(item.value());
        Map<String, Object> metadata = item.metadata();
        // Memory compact constructor enforces key/value non-blank; do our best
        // and let Memory's validation throw if the store produced something
        // invalid (it shouldn't for our puts, but be defensive).
        return new Memory(
                namespace,
                item.key(),
                value,
                metadata,
                null,                   // langgraph4j Item has no embedding
                item.createdAt(),
                item.updatedAt()
        );
    }
}
