package com.langmem4j.observability;

import com.langmem4j.core.metrics.MemoryMetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Micrometer implementation of {@link MemoryMetricsRecorder} — the production
 * recorder wired by the Spring Boot starter when a {@link MeterRegistry} is
 * available.
 *
 * <h3>Meters</h3>
 * <pre>
 * langmem4j_add_total{namespace, status}                Counter
 * langmem4j_search_total{namespace, status}             Counter
 * langmem4j_search_duration_seconds{namespace}          Timer
 * langmem4j_get_total{namespace, hit}                   Counter
 * langmem4j_compact_total{namespace, policy, status}    Counter
 * langmem4j_compact_duration_seconds{namespace}         Timer
 * langmem4j_decay_factor{namespace}                     DistributionSummary (0..1)
 * langmem4j_namespace_resolve_total{source}             Counter (fixed|resolver|fallback)
 * </pre>
 *
 * <h3>Cardinality guard</h3>
 * The {@code namespace} tag is user-controlled (e.g. per-tenant
 * {@code user_alice}), so unbounded cardinality would explode the meter
 * map. After {@link #DEFAULT_MAX_NAMESPACES} distinct namespaces, further
 * namespaces are aggregated into the {@value #OTHER_NAMESPACE} tag.
 *
 * <h3>Performance</h3>
 * Meters are cached per tag combination (one map lookup per record — no
 * builder allocation on the hot path), and everything is thread-safe:
 * {@link ConcurrentHashMap} caches + Micrometer's thread-safe meter types.
 */
public class MicrometerMemoryMetricsRecorder implements MemoryMetricsRecorder {

    /** Default cap on distinct namespace tag values before aggregation. */
    public static final int DEFAULT_MAX_NAMESPACES = 100;

    /** Tag value namespaces are collapsed into beyond the cap. */
    public static final String OTHER_NAMESPACE = "_other";

    private final MeterRegistry registry;
    private final int maxNamespaces;
    private final Set<String> knownNamespaces = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> summaries = new ConcurrentHashMap<>();

    public MicrometerMemoryMetricsRecorder(MeterRegistry registry) {
        this(registry, DEFAULT_MAX_NAMESPACES);
    }

    /**
     * @param registry      the application's meter registry
     * @param maxNamespaces max distinct {@code namespace} tag values before
     *                      further namespaces collapse into
     *                      {@value #OTHER_NAMESPACE}
     */
    public MicrometerMemoryMetricsRecorder(MeterRegistry registry, int maxNamespaces) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        if (maxNamespaces < 1) {
            throw new IllegalArgumentException("maxNamespaces must be >= 1");
        }
        this.registry = registry;
        this.maxNamespaces = maxNamespaces;
    }

    @Override
    public void recordAdd(String namespace, boolean success) {
        String ns = capNamespace(namespace);
        counter("langmem4j_add_total",
                "namespace", ns, "status", success ? "success" : "failure").increment();
    }

    @Override
    public void recordSearch(String namespace, Duration duration, boolean success) {
        String ns = capNamespace(namespace);
        counter("langmem4j_search_total",
                "namespace", ns, "status", success ? "success" : "failure").increment();
        timer("langmem4j_search_duration_seconds", ns).record(duration);
    }

    @Override
    public void recordGet(String namespace, boolean hit) {
        String ns = capNamespace(namespace);
        counter("langmem4j_get_total",
                "namespace", ns, "hit", hit ? "hit" : "miss").increment();
    }

    @Override
    public void recordCompact(String namespace, String policy, Duration duration, boolean success) {
        String ns = capNamespace(namespace);
        counter("langmem4j_compact_total",
                "namespace", ns, "policy", policy, "status", success ? "success" : "failure")
                .increment();
        timer("langmem4j_compact_duration_seconds", ns).record(duration);
    }

    @Override
    public void recordDecayFactor(String namespace, float decayFactor) {
        String ns = capNamespace(namespace);
        summary("langmem4j_decay_factor", ns).record(decayFactor);
    }

    @Override
    public void recordNamespaceResolve(String source) {
        counter("langmem4j_namespace_resolve_total", "source", source).increment();
    }

    /** Number of distinct namespaces tracked so far (visible for tests). */
    public int trackedNamespaces() {
        return knownNamespaces.size();
    }

    /**
     * Returns the given namespace, or {@value #OTHER_NAMESPACE} once more than
     * {@code maxNamespaces} distinct namespaces have been seen. Races can
     * only cause a transient overshoot by a few namespaces, never unbounded
     * growth — the set size check bounds it.
     */
    private String capNamespace(String namespace) {
        if (knownNamespaces.contains(namespace)) {
            return namespace;
        }
        if (knownNamespaces.size() < maxNamespaces) {
            knownNamespaces.add(namespace);
            return namespace;
        }
        return OTHER_NAMESPACE;
    }

    private Counter counter(String name, String... tagPairs) {
        String key = name + '|' + String.join("=", tagPairs);
        return counters.computeIfAbsent(key,
                k -> Counter.builder(name).tags(tagPairs).register(registry));
    }

    private Counter counter(String name, String k1, String v1, String k2, String v2, String k3, String v3) {
        String key = name + '|' + k1 + '=' + v1 + ';' + k2 + '=' + v2 + ';' + k3 + '=' + v3;
        return counters.computeIfAbsent(key,
                k -> Counter.builder(name).tags(k1, v1, k2, v2, k3, v3).register(registry));
    }

    private Timer timer(String name, String ns) {
        return timers.computeIfAbsent(name + "|namespace=" + ns,
                k -> Timer.builder(name).tag("namespace", ns).register(registry));
    }

    private DistributionSummary summary(String name, String ns) {
        return summaries.computeIfAbsent(name + "|namespace=" + ns,
                k -> DistributionSummary.builder(name)
                        .tag("namespace", ns)
                        .register(registry));
    }
}
