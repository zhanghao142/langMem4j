package com.langmem4j.tools.core;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchMemoryServiceTest {

    @Test
    void constructor_rejects_bad_inputs() {
        var store = new InMemoryMemoryStore();
        assertThatThrownBy(() -> new SearchMemoryService(null, "ns"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SearchMemoryService(store, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getMemory_found_and_not_found_messages() {
        var store = new InMemoryMemoryStore();
        store.upsert("ns", Memory.of("ns", "k", "v", Map.of("a", 1)));
        var svc = new SearchMemoryService(store, "ns");

        assertThat(svc.getMemory("k")).contains("k", "v", "a=1");
        assertThat(svc.getMemory("missing")).contains("No memory found");
    }

    @Test
    void searchMemory_substring_ranks_hits_and_formats_them() {
        var store = new InMemoryMemoryStore();
        store.upsert("ns", Memory.of("ns", "a", "hot pot"));
        store.upsert("ns", Memory.of("ns", "b", "no match"));
        store.upsert("ns", Memory.of("ns", "c", "hot chocolate"));

        var svc = new SearchMemoryService(store, "ns");
        String out = svc.searchMemory("hot", 10);
        assertThat(out).contains("- a: hot pot", "- c: hot chocolate");
    }

    @Test
    void searchMemory_empty_returns_dedicated_message() {
        var svc = new SearchMemoryService(new InMemoryMemoryStore(), "ns");
        assertThat(svc.searchMemory("nope", 5)).contains("No memories found");
    }

    @Test
    void searchMemory_limit_null_becomes_5_and_over_20_clamped_to_20() {
        var store = new InMemoryMemoryStore();
        for (int i = 0; i < 30; i++) {
            store.upsert("ns", Memory.of("ns", "k" + i, "contains needle " + i));
        }
        var svc = new SearchMemoryService(store, "ns");
        // limit null → 5
        assertThat(countLines(svc.searchMemory("needle", null))).isEqualTo(5);
        // limit 0 → 5
        assertThat(countLines(svc.searchMemory("needle", 0))).isEqualTo(5);
        // limit 100 → 20 (hard-capped by the service)
        assertThat(countLines(svc.searchMemory("needle", 100))).isEqualTo(20);
        // limit 3 → 3
        assertThat(countLines(svc.searchMemory("needle", 3))).isEqualTo(3);
    }

    @Test
    void searchMemory_with_metadata_filter() {
        var store = new InMemoryMemoryStore();
        store.upsert("ns", Memory.of("ns", "a", "eat food",  Map.of("kind", "food")));
        store.upsert("ns", Memory.of("ns", "b", "food art",  Map.of("kind", "art")));

        var svc = new SearchMemoryService(store, "ns");
        String out = svc.searchMemory("food", 10, "kind=food");
        // Only 'a' matches the filter despite 'b' also containing "food"
        assertThat(out).contains("- a: eat food").doesNotContain("- b:");
    }

    @Test
    void listMemories_empty_and_non_empty() {
        var store = new InMemoryMemoryStore();
        var svc = new SearchMemoryService(store, "ns");
        assertThat(svc.listMemories()).contains("No memories");

        store.upsert("ns", Memory.of("ns", "a", "1"));
        store.upsert("ns", Memory.of("ns", "b", "2"));
        String out = svc.listMemories();
        assertThat(out).contains("Stored memories (2)").contains("a").contains("b");
    }

    @Test
    void namespace_accessor() {
        assertThat(new SearchMemoryService(new InMemoryMemoryStore(), "u1")
                .namespace()).isEqualTo("u1");
    }

    private static int countLines(String s) {
        if (s == null || s.isEmpty() || s.startsWith("No memories")) return 0;
        return s.split("\n").length;
    }
}
