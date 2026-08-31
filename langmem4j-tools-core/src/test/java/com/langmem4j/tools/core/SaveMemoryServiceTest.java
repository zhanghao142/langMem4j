package com.langmem4j.tools.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SaveMemoryServiceTest {

    @Test
    void constructor_rejects_bad_inputs() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        assertThatThrownBy(() -> new SaveMemoryService(null, "ns"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SaveMemoryService(store, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void saveMemory_round_trip() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        var svc = new SaveMemoryService(store, "user");

        String msg = svc.saveMemory("food", "I like hot pot");
        assertThat(msg).contains("hot pot").contains("food");

        var got = store.getByKey("user", "food").orElseThrow();
        assertThat(got.value()).isEqualTo("I like hot pot");
    }

    @Test
    void saveMemoryWithMetadata_parses_keyValue_csv() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        var svc = new SaveMemoryService(store, "user");

        String msg = svc.saveMemoryWithMetadata("k", "v",
                "category=pref, source=user , verified=, standalone");
        assertThat(msg).contains("category");

        Map<String, Object> md = store.getByKey("user", "k").orElseThrow().metadata();
        assertThat(md)
                .containsEntry("category", "pref")
                .containsEntry("source",   "user")
                .containsEntry("verified", "")   // empty right side → empty string
                .containsEntry("standalone", Boolean.TRUE);
    }

    @Test
    void saveMemoryWithMetadata_blank_is_noop_and_does_not_add_empty_metadata() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        var svc = new SaveMemoryService(store, "user");

        svc.saveMemoryWithMetadata("k", "v", "   ");
        Map<String, Object> md = store.getByKey("user", "k").orElseThrow().metadata();
        assertThat(md).isEmpty();
    }

    @Test
    void deleteMemory_removes_and_confirms() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        var svc = new SaveMemoryService(store, "user");
        svc.saveMemory("k", "v");
        assertThat(store.listKeys("user")).hasSize(1);

        String msg = svc.deleteMemory("k");
        assertThat(msg).contains("k").contains("deleted");
        assertThat(store.listKeys("user")).isEmpty();
    }

    @Test
    void namespace_accessor() {
        var store = new com.langmem4j.core.store.inmemory.InMemoryMemoryStore();
        assertThat(new SaveMemoryService(store, "bob").namespace()).isEqualTo("bob");
    }
}
