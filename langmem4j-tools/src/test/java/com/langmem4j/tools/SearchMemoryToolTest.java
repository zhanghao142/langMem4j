package com.langmem4j.tools;

import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchMemoryToolTest {

    private InMemoryMemoryStore store;
    private SearchMemoryTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        tool = new SearchMemoryTool(store, "user1");
    }

    @Test
    void getMemory_returns_content_when_found() {
        store.upsert("user1", com.langmem4j.core.memory.Memory.of(
                "user1", "favorite_food", "Alice likes hot pot"));

        String result = tool.getMemory("favorite_food");
        assertThat(result).contains("Alice likes hot pot");
    }

    @Test
    void getMemory_returns_not_found_when_missing() {
        String result = tool.getMemory("ghost");
        assertThat(result).contains("No memory found");
    }

    @Test
    void searchMemory_uses_substring_match_in_inmemory_mode() {
        store.upsert("user1", com.langmem4j.core.memory.Memory.of(
                "user1", "food", "I like hot pot"));
        store.upsert("user1", com.langmem4j.core.memory.Memory.of(
                "user1", "hobby", "I play guitar"));

        String result = tool.searchMemory("like", 5);
        assertThat(result).contains("hot pot");
        assertThat(result).doesNotContain("guitar");
    }

    @Test
    void searchMemory_respects_limit() {
        for (int i = 0; i < 10; i++) {
            store.upsert("user1", com.langmem4j.core.memory.Memory.of(
                    "user1", "k" + i, "memory with keyword"));
        }

        String result = tool.searchMemory("keyword", 3);
        // Only 3 results listed
        long count = result.lines().filter(l -> l.startsWith("-")).count();
        assertThat(count).isEqualTo(3);
    }

    @Test
    void searchMemory_defaults_to_limit_5_when_null() {
        for (int i = 0; i < 10; i++) {
            store.upsert("user1", com.langmem4j.core.memory.Memory.of(
                    "user1", "k" + i, "memory"));
        }

        String result = tool.searchMemory("memory", null);
        long count = result.lines().filter(l -> l.startsWith("-")).count();
        assertThat(count).isEqualTo(5);
    }

    @Test
    void listMemories_returns_all_keys() {
        store.upsert("user1", com.langmem4j.core.memory.Memory.of("user1", "a", "v"));
        store.upsert("user1", com.langmem4j.core.memory.Memory.of("user1", "b", "v"));

        String result = tool.listMemories();
        assertThat(result).contains("a");
        assertThat(result).contains("b");
        assertThat(result).contains("2");
    }

    @Test
    void listMemories_empty_namespace_returns_none() {
        String result = tool.listMemories();
        assertThat(result).contains("No memories");
    }
}
