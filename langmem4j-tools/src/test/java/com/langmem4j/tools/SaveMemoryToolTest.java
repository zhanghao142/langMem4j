package com.langmem4j.tools;

import com.langmem4j.core.embedding.EmbeddingGenerator;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SaveMemoryToolTest {

    private InMemoryMemoryStore store;
    private SaveMemoryTool tool;

    @BeforeEach
    void setUp() {
        store = new InMemoryMemoryStore();
        tool = new SaveMemoryTool(store, "user1");
    }

    @Test
    void saveMemory_persists_fact() {
        String result = tool.saveMemory("favorite_food", "Alice likes hot pot");

        assertThat(result).contains("Memory saved");
        assertThat(store.getByKey("user1", "favorite_food"))
                .map(Memory::value).contains("Alice likes hot pot");
    }

    @Test
    void saveMemoryWithMetadata_parses_key_value_pairs() {
        String result = tool.saveMemoryWithMetadata("birthday",
                "Alice was born on March 15",
                "source=profile,category=personal");

        assertThat(result).contains("Memory saved");
        assertThat(result).contains("source");
        assertThat(result).contains("profile");

        Memory saved = store.getByKey("user1", "birthday").orElseThrow();
        assertThat(saved.metadata())
                .containsEntry("source", "profile")
                .containsEntry("category", "personal");
    }

    @Test
    void saveMemoryWithMetadata_empty_is_noop() {
        String result = tool.saveMemoryWithMetadata("k", "v", "");

        Memory saved = store.getByKey("user1", "k").orElseThrow();
        assertThat(saved.metadata()).isEmpty();
    }

    @Test
    void saveMemoryWithMetadata_boolean_tag_when_no_equals() {
        tool.saveMemoryWithMetadata("k", "v", "verified");

        Memory saved = store.getByKey("user1", "k").orElseThrow();
        assertThat(saved.metadata()).containsEntry("verified", Boolean.TRUE);
    }

    @Test
    void deleteMemory_removes_entry() {
        tool.saveMemory("temp", "to be deleted");
        String result = tool.deleteMemory("temp");

        assertThat(result).contains("deleted");
        assertThat(store.getByKey("user1", "temp")).isEmpty();
    }

    @Test
    void deleteMemory_missing_key_is_idempotent() {
        String result = tool.deleteMemory("ghost");
        assertThat(result).contains("deleted"); // no exception
    }

    @Test
    void namespace_isolation() {
        SaveMemoryTool otherNs = new SaveMemoryTool(store, "user2");
        tool.saveMemory("k", "alice's fact");
        otherNs.saveMemory("k", "bob's fact");

        assertThat(store.getByKey("user1", "k"))
                .map(com.langmem4j.core.memory.Memory::value)
                .hasValueSatisfying(v -> assertThat(v).contains("alice"));
        assertThat(store.getByKey("user2", "k"))
                .map(com.langmem4j.core.memory.Memory::value)
                .hasValueSatisfying(v -> assertThat(v).contains("bob"));
    }
}
