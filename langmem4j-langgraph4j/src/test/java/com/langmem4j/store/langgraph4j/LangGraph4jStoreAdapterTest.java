package com.langmem4j.store.langgraph4j;

import com.langmem4j.core.memory.Memory;
import org.bsc.langgraph4j.store.InMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LangGraph4jStoreAdapterTest {

    private static final String NS = "test-ns";

    private LangGraph4jStoreAdapter adapter;
    private long beforeFirstWrite;

    @BeforeEach
    void setUp() {
        adapter = new LangGraph4jStoreAdapter(new InMemoryStore());
        // Record a timestamp slightly before any writes; used to bracket createdAt
        // so we can assert it was set within a reasonable window without sleeping.
        beforeFirstWrite = System.currentTimeMillis() - 50L;
    }

    // ------------------------------------------------------------------
    // 1. upsert + get round-trip
    // ------------------------------------------------------------------

    @Test
    void upsert_and_getByKey_roundtrip() {
        long before = System.currentTimeMillis();
        Map<String, Object> meta = Map.of("src", "unit", "n", 42);
        adapter.upsert(NS, Memory.of(NS, "k1", "hello world", meta));
        long after = System.currentTimeMillis();

        Optional<Memory> result = adapter.getByKey(NS, "k1");
        assertThat(result).isPresent();

        Memory m = result.get();
        // Namespace was properly threaded through toMemory (not empty).
        assertThat(m.namespace()).isEqualTo(NS);
        assertThat(m.key()).isEqualTo("k1");
        assertThat(m.value()).isEqualTo("hello world");
        // Metadata: round-trip of all keys.
        assertThat(m.metadata())
                .containsEntry("src", "unit")
                .containsEntry("n", 42);
        // Embedding: not supplied → null (adapter does not invent vectors).
        assertThat(m.embeddingVector()).isNull();
        // Timestamps: createdAt ∈ [before, after] && lastAccessedAt (updatedAt)
        // also set by langgraph4j put at roughly same time.
        assertThat(m.createdAt()).isBetween(before, after);
        assertThat(m.lastAccessedAt()).isGreaterThanOrEqualTo(m.createdAt());
    }

    // ------------------------------------------------------------------
    // 2. getByKey miss
    // ------------------------------------------------------------------

    @Test
    void getByKey_nonexistent_returns_empty() {
        adapter.upsert(NS, Memory.of(NS, "exists", "value"));

        assertThat(adapter.getByKey(NS, "nope")).isEmpty();
        // Cross-namespace miss should also be empty.
        assertThat(adapter.getByKey("other-ns", "exists")).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. deleteByKey removes
    // ------------------------------------------------------------------

    @Test
    void deleteByKey_removes_memory() {
        adapter.upsert(NS, Memory.of(NS, "a", "1"));
        adapter.upsert(NS, Memory.of(NS, "b", "2"));

        // Sanity: both present.
        assertThat(adapter.getByKey(NS, "a")).isPresent();
        assertThat(adapter.getByKey(NS, "b")).isPresent();

        adapter.deleteByKey(NS, "a");

        assertThat(adapter.getByKey(NS, "a")).isEmpty();
        // Other key untouched.
        assertThat(adapter.getByKey(NS, "b")).isPresent();

        // Deleting already-absent key must not throw.
        adapter.deleteByKey(NS, "does-not-exist");
    }

    // ------------------------------------------------------------------
    // 4. search returns keyword-matching items
    // ------------------------------------------------------------------

    @Test
    void search_returns_matching_items() {
        adapter.upsert(NS, Memory.of(NS, "apple",   "apple is a red fruit",      Map.of("t", "food")));
        adapter.upsert(NS, Memory.of(NS, "banana",  "banana is a yellow fruit",   Map.of("t", "food")));
        adapter.upsert(NS, Memory.of(NS, "car",     "my car is blue",             Map.of("t", "vehicle")));

        // Note: langgraph4j InMemoryStore.search uses substring-like semantics for
        // plain-text queries (implementation detail confirmed via jar scan). The
        // word "fruit" should match the first two memories but not the car.
        List<Memory> fruits = adapter.search(NS, "fruit", 10);

        assertThat(fruits).hasSize(2);
        assertThat(fruits).extracting(Memory::key).containsExactlyInAnyOrder("apple", "banana");

        // And sanity: query specific to the car matches only car.
        List<Memory> cars = adapter.search(NS, "blue", 10);
        assertThat(cars).hasSize(1);
        assertThat(cars.get(0).key()).isEqualTo("car");
    }

    // ------------------------------------------------------------------
    // 5. search limit caps results
    // ------------------------------------------------------------------

    @Test
    void search_respects_limit() {
        // Each memory contains "needle" so InMemoryStore.search will return
        // all of them if unconstrained; with limit=3 we must see ≤3.
        for (int i = 0; i < 5; i++) {
            adapter.upsert(NS, Memory.of(NS, "m" + i, "needle " + i));
        }

        List<Memory> r = adapter.search(NS, "needle", 3);

        assertThat(r).hasSize(3);
    }

    // ------------------------------------------------------------------
    // 6. listKeys → UnsupportedOperationException
    // ------------------------------------------------------------------

    @Test
    void listKeys_throws_unsupported() {
        assertThatThrownBy(() -> adapter.listKeys(NS))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("listKeys");
    }

    // ------------------------------------------------------------------
    // 7. clearNamespace → UnsupportedOperationException
    // ------------------------------------------------------------------

    @Test
    void clearNamespace_throws_unsupported() {
        assertThatThrownBy(() -> adapter.clearNamespace(NS))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("clearNamespace");
    }
}
