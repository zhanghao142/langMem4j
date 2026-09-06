package com.langmem4j.spring;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.metrics.MemoryMetricsRecorder;
import com.langmem4j.observability.MicrometerMemoryMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Auto-configuration contract for observability beans: Micrometer recorder
 * wired when a MeterRegistry exists (and actually recording through the
 * manager), health indicator registered when actuator exists, both
 * disable-able, both absent when the underlying class is missing.
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LangMem4jAutoConfiguration.class));

    // ── Metrics ────────────────────────────────────────────────

    @Test
    void micrometer_recorder_is_wired_and_records_through_the_manager() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryMetricsRecorder.class);
                    assertThat(context.getBean(MemoryMetricsRecorder.class))
                            .isInstanceOf(MicrometerMemoryMetricsRecorder.class);

                    // the recorder is actually wired INTO the manager
                    context.getBean(MemoryManager.class).add("obs_key", "obs value");
                    assertThat(context.getBean(MeterRegistry.class)
                            .get("langmem4j_add_total")
                            .tag("namespace", "default")
                            .tag("status", "success")
                            .counter().count()).isEqualTo(1.0);
                });
    }

    @Test
    void metrics_disabled_leaves_manager_on_noop_recorder() {
        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("langmem4j.observability.metrics.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MemoryMetricsRecorder.class);
                    assertThat(context.getBean(MemoryManager.class).metricsRecorder())
                            .isSameAs(MemoryMetricsRecorder.NOOP);
                });
    }

    @Test
    void without_micrometer_the_app_still_starts_and_no_recorder_exists() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(MeterRegistry.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(MemoryManager.class);
                    assertThat(context).doesNotHaveBean(MemoryMetricsRecorder.class);
                });
    }

    // ── Health ─────────────────────────────────────────────────

    @Test
    void health_indicator_is_registered_by_default() {
        runner.run(context ->
                assertThat(context).hasSingleBean(LangMem4jHealthIndicator.class));
    }

    @Test
    void health_indicator_disabled_via_property() {
        runner.withPropertyValues("langmem4j.observability.health.enabled=false")
                .run(context ->
                        assertThat(context).doesNotHaveBean(LangMem4jHealthIndicator.class));
    }

    @Test
    void health_indicator_absent_without_actuator() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(HealthIndicator.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LangMem4jHealthIndicator.class);
                    // the rest of the starter still works
                    assertThat(context).hasSingleBean(MemoryManager.class);
                });
    }
}
