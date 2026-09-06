package com.langmem4j.spring;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.Optional;

/**
 * Actuator health contribution for langMem4j — surfaces as the
 * {@code "langMem4j"} component under {@code /actuator/health}:
 *
 * <pre>{@code
 * "langMem4j": {
 *   "status": "UP",
 *   "details": { "store": "InMemoryMemoryStore", "memoryCount": 142 }
 * }
 * }</pre>
 *
 * <p>Probe semantics: a write → read → delete round-trip against the real
 * store through the {@link MemoryManager} (so namespace resolution, merge
 * policies and the store itself are all exercised). The probe key uses the
 * {@value #TEST_KEY_PREFIX} prefix and is always cleaned up, including on
 * failure paths.
 * <p>
 * {@code memoryCount} is omitted for stores that cannot enumerate keys
 * (e.g. the langgraph4j adapter throws {@code UnsupportedOperationException}
 * on {@code listKeys}) — that alone never fails the health check.
 * <p>
 * Registered by the starter only when spring-boot-actuator is on the
 * classpath and {@code langmem4j.observability.health.enabled=true}
 * (the default).
 */
public class LangMem4jHealthIndicator implements HealthIndicator {

    /** Prefix of the throwaway probe key; safe to filter from listings. */
    public static final String TEST_KEY_PREFIX = "__health_check_";

    private final MemoryManager manager;
    private final MemoryStore store;

    public LangMem4jHealthIndicator(MemoryManager manager, MemoryStore store) {
        this.manager = manager;
        this.store = store;
    }

    @Override
    public Health health() {
        String testKey = TEST_KEY_PREFIX + System.nanoTime();
        String storeName = store.getClass().getSimpleName();
        try {
            manager.add(testKey, "ping");
            Optional<Memory> found = manager.get(testKey);
            manager.remove(testKey);

            if (found.isEmpty()) {
                return Health.down()
                        .withDetail("store", storeName)
                        .withDetail("error", "health-check write was not readable back")
                        .build();
            }

            Health.Builder up = Health.up().withDetail("store", storeName);
            try {
                up.withDetail("memoryCount", manager.keys().size());
            } catch (UnsupportedOperationException unsupportedListKeys) {
                // Backends without key enumeration (langgraph4j adapter) —
                // not a health problem, just no count to report.
            }
            return up.build();
        } catch (Exception e) {
            // Best-effort cleanup so a failing store never leaks probe keys.
            try {
                manager.remove(testKey);
            } catch (Exception cleanupIgnored) {
                // Store is failing — the remove failing too is expected.
            }
            return Health.down(e)
                    .withDetail("store", storeName)
                    .build();
        }
    }
}
