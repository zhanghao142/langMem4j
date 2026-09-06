package com.langmem4j.spring;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the write-read-delete probe: UP on a working store (with details
 * and no probe-key residue), DOWN on a failing or silently-lossy store.
 */
class LangMem4jHealthIndicatorTest {

    /** Accepts writes but never returns them — store is "up" yet lossy. */
    static class BlackHoleStore implements MemoryStore {
        @Override public void upsert(String namespace, Memory memory) {}
        @Override public Optional<Memory> getByKey(String namespace, String key) {
            return Optional.empty(); }
        @Override public List<String> listKeys(String namespace) { return List.of(); }
        @Override public List<Memory> search(String namespace, String queryText,
                                             int limit, MemoryFilter filter) {
            return List.of(); }
        @Override public void deleteByKey(String namespace, String key) {}
        @Override public void clearNamespace(String namespace) {}
    }

    /** Every write fails — simulates a dead backend. */
    static class ThrowingStore extends BlackHoleStore {
        @Override public void upsert(String namespace, Memory memory) {
            throw new IllegalStateException("store down"); }
    }

    private static MemoryManager manager(MemoryStore store) {
        return MemoryManager.withStore(store)
                .withDefaultNamespace("health_ns")
                .build();
    }

    @Test
    void healthy_store_reports_up_with_details_and_leaves_no_residue() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("health_ns")
                .build();
        manager.add("real_key", "real value");
        int countBefore = manager.keys().size();

        Health health = new LangMem4jHealthIndicator(manager, manager.store()).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("store", "InMemoryMemoryStore")
                .containsEntry("memoryCount", countBefore); // stable count: probe key already cleaned up
        // after the probe: probe key cleaned up, real memory untouched
        assertThat(manager.keys())
                .hasSize(countBefore)
                .contains("real_key")
                .noneMatch(k -> k.startsWith(LangMem4jHealthIndicator.TEST_KEY_PREFIX));
    }

    @Test
    void failing_store_reports_down_with_exception_detail() {
        ThrowingStore store = new ThrowingStore();

        Health health = new LangMem4jHealthIndicator(manager(store), store).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("store", "ThrowingStore")
                .containsKey("error");
    }

    @Test
    void lossy_store_that_swallows_writes_reports_down() {
        BlackHoleStore store = new BlackHoleStore();

        Health health = new LangMem4jHealthIndicator(manager(store), store).health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("store", "BlackHoleStore")
                .containsEntry("error", "health-check write was not readable back");
    }
}
