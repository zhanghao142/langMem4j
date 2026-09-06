package com.langmem4j.core.manager;

import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryCompactionPolicy;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.metrics.MemoryMetricsRecorder;
import com.langmem4j.core.namespace.NamespaceResolver;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies that {@link MemoryManager} fires {@link MemoryMetricsRecorder}
 * events with the right namespace / outcome on every instrumented operation,
 * and that the NOOP default (no recorder) changes nothing.
 */
class MemoryManagerMetricsTest {

    /** Collects every event as a parseable string for assertion. */
    static class RecordingRecorder implements MemoryMetricsRecorder {
        final List<String> adds = new CopyOnWriteArrayList<>();
        final List<String> searches = new CopyOnWriteArrayList<>();
        final List<String> gets = new CopyOnWriteArrayList<>();
        final List<String> compacts = new CopyOnWriteArrayList<>();
        final List<String> decayFactors = new CopyOnWriteArrayList<>();
        final List<String> resolves = new CopyOnWriteArrayList<>();

        @Override public void recordAdd(String ns, boolean success) { adds.add(ns + "|" + success); }
        @Override public void recordSearch(String ns, Duration d, boolean success) {
            searches.add(ns + "|" + success + "|" + !d.isNegative()); }
        @Override public void recordGet(String ns, boolean hit) { gets.add(ns + "|" + hit); }
        @Override public void recordCompact(String ns, String policy, Duration d, boolean success) {
            compacts.add(ns + "|" + policy + "|" + success + "|" + !d.isNegative()); }
        @Override public void recordDecayFactor(String ns, float factor) {
            decayFactors.add(ns + "|" + factor); }
        @Override public void recordNamespaceResolve(String source) { resolves.add(source); }
    }

    /** Minimal store whose write and search operations always fail. */
    static class ThrowingStore implements MemoryStore {
        @Override public void upsert(String namespace, Memory memory) {
            throw new IllegalStateException("store down"); }
        @Override public Optional<Memory> getByKey(String namespace, String key) {
            return Optional.empty(); }
        @Override public List<String> listKeys(String namespace) { return List.of(); }
        @Override public List<Memory> search(String namespace, String queryText,
                                             int limit, MemoryFilter filter) {
            throw new IllegalStateException("store down"); }
        @Override public void deleteByKey(String namespace, String key) {}
        @Override public void clearNamespace(String namespace) {}
    }

    // ── add ────────────────────────────────────────────────────

    @Test
    void add_success_and_failure_are_recorded() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("user_alice")
                .build();

        manager.add("food", "hot pot");
        assertThat(recorder.adds).containsExactly("user_alice|true");

        MemoryManager failing = MemoryManager.withStore(new ThrowingStore())
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("user_bob")
                .build();
        assertThatThrownBy(() -> failing.add("food", "sushi"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recorder.adds).containsExactly("user_alice|true", "user_bob|false");
    }

    @Test
    void add_memory_and_addAll_record_add_events() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .build();

        manager.add(Memory.of("ns_a", "k1", "v1", null));
        manager.addAll("ns_b", List.of(Memory.of("ns_b", "k2", "v2", null)));

        assertThat(recorder.adds).containsExactly("ns_a|true", "ns_b|true");
    }

    // ── search ─────────────────────────────────────────────────

    @Test
    void search_records_duration_and_success() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("user_alice")
                .build();
        manager.add("food", "hot pot");

        manager.search("hot", 5);
        assertThat(recorder.searches).hasSize(1);
        assertThat(recorder.searches.get(0)).startsWith("user_alice|true|");

        MemoryManager failing = MemoryManager.withStore(new ThrowingStore())
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("user_bob")
                .build();
        assertThatThrownBy(() -> failing.search("anything", 5))
                .isInstanceOf(IllegalStateException.class);
        assertThat(recorder.searches).hasSize(2);
        assertThat(recorder.searches.get(1)).startsWith("user_bob|false|");
    }

    @Test
    void filtered_search_is_also_recorded_exactly_once() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("ns")
                .build();
        manager.add("food", "hot pot", Map.of("category", "food"));

        manager.search("hot", 5, MemoryFilter.builder().metadata("category", "food").build());

        // exactly once — the default-namespace variant must not double-record
        assertThat(recorder.searches).hasSize(1);
    }

    // ── get ────────────────────────────────────────────────────

    @Test
    void get_records_hit_and_miss() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("ns")
                .build();
        manager.add("food", "hot pot");

        manager.get("food");
        manager.get("missing");

        assertThat(recorder.gets).containsExactly("ns|true", "ns|false");
    }

    // ── compact ────────────────────────────────────────────────

    @Test
    void compact_records_policy_name_and_duration() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withCompactionPolicy(MemoryCompactionPolicy.categoryGroup())
                .build();
        manager.add("ns", "k1", "hot pot", Map.of("category", "food"));
        manager.add("ns", "k2", "sushi", Map.of("category", "food"));

        manager.compact("ns");

        assertThat(recorder.compacts).hasSize(1);
        // categoryGroup() is a lambda → sanitized to a stable "lambda" tag
        assertThat(recorder.compacts.get(0)).matches("ns\\|lambda\\|true\\|true");
    }

    // ── namespace resolution ───────────────────────────────────

    @Test
    void namespace_resolve_source_reflects_fixed_resolver_and_fallback() {
        RecordingRecorder recorder = new RecordingRecorder();

        MemoryManager fixed = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDefaultNamespace("default")
                .build();
        fixed.add("k", "v");
        assertThat(recorder.resolves).containsExactly("fixed");

        RecordingRecorder recorder2 = new RecordingRecorder();
        MemoryManager resolving = MemoryManager.inMemory()
                .withMetricsRecorder(recorder2)
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> "user_carol")
                .build();
        resolving.add("k", "v");
        assertThat(recorder2.resolves).containsExactly("resolver");

        RecordingRecorder recorder3 = new RecordingRecorder();
        MemoryManager falling = MemoryManager.inMemory()
                .withMetricsRecorder(recorder3)
                .withDefaultNamespace("default")
                .withNamespaceResolver(() -> null)
                .build();
        falling.add("k", "v");
        assertThat(recorder3.resolves).containsExactly("fallback");
    }

    // ── decay ──────────────────────────────────────────────────

    @Test
    void decay_factors_are_recorded_for_surviving_search_results() {
        RecordingRecorder recorder = new RecordingRecorder();
        MemoryManager manager = MemoryManager.inMemory()
                .withMetricsRecorder(recorder)
                .withDecayPolicy(MemoryDecayPolicy.exponential())
                .build();
        manager.add("ns", "food", "hot pot", null);

        manager.search("ns", "hot", 5);

        // fresh memory → factor 1.0, recorded once
        assertThat(recorder.decayFactors).hasSize(1);
        assertThat(recorder.decayFactors.get(0)).startsWith("ns|1.0");
    }

    // ── NOOP default ───────────────────────────────────────────

    @Test
    void without_recorder_all_operations_still_work() {
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("ns")
                .build();
        assertThat(manager.metricsRecorder()).isSameAs(MemoryMetricsRecorder.NOOP);

        manager.add("k", "v");
        manager.search("v", 5);
        manager.get("k");

        // withMetricsRecorder(null) also falls back to NOOP instead of NPE-ing
        MemoryManager nullSafe = MemoryManager.inMemory()
                .withMetricsRecorder(null)
                .build();
        assertThat(nullSafe.metricsRecorder()).isSameAs(MemoryMetricsRecorder.NOOP);
    }
}
