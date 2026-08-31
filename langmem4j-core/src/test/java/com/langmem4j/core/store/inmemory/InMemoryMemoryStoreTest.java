package com.langmem4j.core.store.inmemory;

import com.langmem4j.core.memory.Memory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMemoryStoreTest {

    private InMemoryMemoryStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
    }

    // --- upsert + getByKey ---

    @Test
    void upsert_and_get_round_trip() {
        Memory memory = Memory.of("user1", "favorite_food", "hot pot");
        store.upsert("user1", memory);

        Optional<Memory> result = store.getByKey("user1", "favorite_food");
        assertThat(result).contains(memory);
    }

    @Test
    void upsert_overwrites_existing_key() {
        store.upsert("ns", Memory.of("ns", "k", "old_value"));
        store.upsert("ns", Memory.of("ns", "k", "new_value"));

        assertThat(store.getByKey("ns", "k"))
                .map(Memory::value)
                .contains("new_value");
        assertThat(store.sizeOf("ns")).isEqualTo(1);
    }

    @Test
    void getByKey_returns_empty_when_namespace_missing() {
        assertThat(store.getByKey("nonexistent", "anything")).isEmpty();
    }

    @Test
    void getByKey_returns_empty_when_key_missing() {
        store.upsert("ns", Memory.of("ns", "a", "v"));
        assertThat(store.getByKey("ns", "b")).isEmpty();
    }

    // --- listKeys ---

    @Test
    void listKeys_returns_all_keys_in_namespace() {
        store.upsert("ns", Memory.of("ns", "a", "v"));
        store.upsert("ns", Memory.of("ns", "b", "v"));
        store.upsert("other", Memory.of("other", "c", "v"));

        assertThat(store.listKeys("ns")).containsExactlyInAnyOrder("a", "b");
        assertThat(store.listKeys("other")).containsExactly("c");
    }

    @Test
    void listKeys_returns_empty_when_namespace_missing() {
        assertThat(store.listKeys("ghost")).isEmpty();
    }

    // --- deleteByKey ---

    @Test
    void delete_removes_key() {
        store.upsert("ns", Memory.of("ns", "k", "v"));
        store.deleteByKey("ns", "k");

        assertThat(store.getByKey("ns", "k")).isEmpty();
        assertThat(store.listKeys("ns")).isEmpty();
    }

    @Test
    void delete_missing_key_is_noop() {
        store.upsert("ns", Memory.of("ns", "exists", "v"));
        store.deleteByKey("ns", "does_not_exist");

        assertThat(store.getByKey("ns", "exists")).isPresent();
    }

    // --- clearNamespace ---

    @Test
    void clear_namespace_removes_all_entries() {
        store.upsert("ns", Memory.of("ns", "a", "v"));
        store.upsert("ns", Memory.of("ns", "b", "v"));
        store.upsert("other", Memory.of("other", "c", "v"));

        store.clearNamespace("ns");

        assertThat(store.listKeys("ns")).isEmpty();
        assertThat(store.listKeys("other")).containsExactly("c");
        assertThat(store.namespaceCount()).isEqualTo(1);
    }

    @Test
    void clear_missing_namespace_is_noop() {
        store.clearNamespace("never_existed"); // should not throw
    }

    // --- batch upsert ---

    @Test
    void upsertBatch_inserts_all() {
        List<Memory> batch = List.of(
                Memory.of("ns", "a", "v1"),
                Memory.of("ns", "b", "v2"),
                Memory.of("ns", "c", "v3")
        );
        store.upsertBatch("ns", batch);

        assertThat(store.listKeys("ns")).hasSize(3);
    }

    // --- substring search (default mode) ---

    @Test
    void search_by_substring_returns_matches() {
        store.upsert("ns", Memory.of("ns", "food", "I like hot pot"));
        store.upsert("ns", Memory.of("ns", "drink", "I drink milk tea"));
        store.upsert("ns", Memory.of("ns", "hobby", "I play guitar"));

        List<Memory> results = store.search("ns", "like", 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).value()).contains("like");
    }

    @Test
    void search_is_case_insensitive() {
        store.upsert("ns", Memory.of("ns", "k", "Python is great"));

        List<Memory> results = store.search("ns", "PYTHON", 5);
        assertThat(results).hasSize(1);
    }

    @Test
    void search_respects_limit() {
        for (int i = 0; i < 10; i++) {
            store.upsert("ns", Memory.of("ns", "k" + i, "memory with keyword"));
        }

        List<Memory> results = store.search("ns", "keyword", 3);
        assertThat(results).hasSize(3);
    }

    @Test
    void search_returns_empty_when_no_match() {
        store.upsert("ns", Memory.of("ns", "k", "completely different"));

        List<Memory> results = store.search("ns", "xyz_not_present", 5);
        assertThat(results).isEmpty();
    }

    @Test
    void search_returns_empty_when_namespace_missing() {
        assertThat(store.search("ghost", "query", 5)).isEmpty();
    }

    @Test
    void search_rejects_non_positive_limit() {
        assertThatThrownBy(() -> store.search("ns", "q", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.search("ns", "q", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- multiple namespaces isolation ---

    @Test
    void namespaces_are_isolated() {
        store.upsert("userA", Memory.of("userA", "secret", "alice's secret"));
        store.upsert("userB", Memory.of("userB", "secret", "bob's secret"));

        assertThat(store.getByKey("userA", "secret"))
                .map(Memory::value)
                .hasValueSatisfying(v -> assertThat(v).contains("alice"));
        assertThat(store.getByKey("userB", "secret"))
                .map(Memory::value)
                .hasValueSatisfying(v -> assertThat(v).contains("bob"));
        assertThat(store.namespaceCount()).isEqualTo(2);
    }
}
