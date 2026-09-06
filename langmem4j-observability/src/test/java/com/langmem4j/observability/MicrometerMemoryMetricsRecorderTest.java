package com.langmem4j.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the meter mapping (names, tags, values), the namespace
 * cardinality cap, and constructor validation.
 */
class MicrometerMemoryMetricsRecorderTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MicrometerMemoryMetricsRecorder recorder =
            new MicrometerMemoryMetricsRecorder(registry);

    @Test
    void record_add_increments_counter_with_namespace_and_status_tags() {
        recorder.recordAdd("user_alice", true);
        recorder.recordAdd("user_alice", true);
        recorder.recordAdd("user_bob", false);

        assertThat(registry.get("langmem4j_add_total")
                .tag("namespace", "user_alice").tag("status", "success")
                .counter().count()).isEqualTo(2.0);
        assertThat(registry.get("langmem4j_add_total")
                .tag("namespace", "user_bob").tag("status", "failure")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void record_search_increments_counter_and_records_timer() {
        recorder.recordSearch("user_alice", Duration.ofMillis(120), true);

        assertThat(registry.get("langmem4j_search_total")
                .tag("namespace", "user_alice").tag("status", "success")
                .counter().count()).isEqualTo(1.0);

        io.micrometer.core.instrument.Timer timer =
                registry.get("langmem4j_search_duration_seconds")
                        .tag("namespace", "user_alice").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isPositive();
    }

    @Test
    void record_get_separates_hit_and_miss_tags() {
        recorder.recordGet("user_alice", true);
        recorder.recordGet("user_alice", false);

        assertThat(registry.get("langmem4j_get_total")
                .tag("namespace", "user_alice").tag("hit", "hit")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("langmem4j_get_total")
                .tag("namespace", "user_alice").tag("hit", "miss")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void record_compact_writes_counter_and_timer_with_policy_tag() {
        recorder.recordCompact("user_alice", "lambda", Duration.ofMillis(5), true);

        assertThat(registry.get("langmem4j_compact_total")
                .tag("namespace", "user_alice").tag("policy", "lambda").tag("status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("langmem4j_compact_duration_seconds")
                .tag("namespace", "user_alice").timer().count()).isEqualTo(1);
    }

    @Test
    void record_decay_factor_writes_distribution_summary() {
        recorder.recordDecayFactor("user_alice", 1.0f);
        recorder.recordDecayFactor("user_alice", 0.5f);

        io.micrometer.core.instrument.DistributionSummary summary =
                registry.get("langmem4j_decay_factor")
                        .tag("namespace", "user_alice").summary();
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.totalAmount()).isEqualTo(1.5);
    }

    @Test
    void record_namespace_resolve_uses_source_tag() {
        recorder.recordNamespaceResolve("fixed");
        recorder.recordNamespaceResolve("resolver");
        recorder.recordNamespaceResolve("fallback");

        assertThat(registry.get("langmem4j_namespace_resolve_total")
                .tag("source", "fixed").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("langmem4j_namespace_resolve_total")
                .tag("source", "resolver").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("langmem4j_namespace_resolve_total")
                .tag("source", "fallback").counter().count()).isEqualTo(1.0);
    }

    @Test
    void namespaces_beyond_the_cap_collapse_into_other() {
        MicrometerMemoryMetricsRecorder capped =
                new MicrometerMemoryMetricsRecorder(registry, 2);

        capped.recordAdd("user_a", true);
        capped.recordAdd("user_b", true);
        capped.recordAdd("user_c", true);   // beyond cap → _other

        assertThat(capped.trackedNamespaces()).isEqualTo(2);
        assertThat(registry.get("langmem4j_add_total")
                .tag("namespace", "user_a").tag("status", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("langmem4j_add_total")
                .tag("namespace", MicrometerMemoryMetricsRecorder.OTHER_NAMESPACE)
                .tag("status", "success").counter().count()).isEqualTo(1.0);
        // user_c never materialized as its own meter
        assertThat(registry.find("langmem4j_add_total")
                .tag("namespace", "user_c").counter()).isNull();
    }

    @Test
    void constructor_validates_arguments() {
        assertThatThrownBy(() -> new MicrometerMemoryMetricsRecorder(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MicrometerMemoryMetricsRecorder(registry, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
