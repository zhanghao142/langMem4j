# Changelog

## [Unreleased]

### Added

- **Observability (metrics + health checks)** — langMem4j is no longer a black box.
  - `MemoryMetricsRecorder` SPI in `langmem4j-core` (`com.langmem4j.core.metrics`): framework-free, all methods default to no-op, `NOOP` constant for zero-overhead default. `MemoryManager.withMetricsRecorder(...)` wires it in; add / search / get / compact / decay / namespace-resolution events are recorded (success/failure, durations, hit/miss).
  - New `langmem4j-observability` module: `MicrometerMemoryMetricsRecorder` — `langmem4j_add_total{namespace,status}`, `langmem4j_search_total`, `langmem4j_search_duration_seconds`, `langmem4j_get_total{namespace,hit}`, `langmem4j_compact_total{namespace,policy,status}`, `langmem4j_compact_duration_seconds`, `langmem4j_decay_factor` summary, `langmem4j_namespace_resolve_total{source}`. Meters are cached per tag combination (no builder allocation on the hot path); the namespace tag is cardinality-capped (first 100 distinct values, the rest collapse into `_other`) so multi-tenant routing can't explode the registry.
  - Starter: `langmem4j.observability.metrics.enabled` / `langmem4j.observability.health.enabled` (both default `true`) with `@ConditionalOnClass` guards — no Micrometer / no Actuator on the classpath, or no `MeterRegistry` bean, means the recorder silently degrades to NOOP and the application still starts; a custom `MemoryMetricsRecorder` bean always wins.
  - `LangMem4jHealthIndicator` (starter): `/actuator/health` "langMem4j" component running a write→read→delete probe through the real `MemoryManager`. Probe keys use the `__health_check_` prefix and are cleaned up on every path including failures; a silently-lossy store (write swallowed) reports DOWN; `memoryCount` is omitted for stores without `listKeys()`.
  - Example: `ObservabilityEndpointsTest` in `langmem4j-example-springboot` (with `spring-boot-starter-actuator`) verifies `/actuator/metrics/langmem4j_add_total` counts requests with namespace/status tags, the search timer records, and `/actuator/health` reports the langMem4j component with store details.

- **Runtime namespace resolution (multi-tenancy, zero code)** — namespaces are no longer a static yml value.
  - `NamespaceResolver` SPI in `langmem4j-core` (`com.langmem4j.core.namespace`): pure Java, zero framework deps. Returns null/blank to fall back to the default namespace; consulted on every manager call, so per-request / per-thread context switches are isolated. Explicit `add(ns, key, value)` arguments still win over the resolver.
  - `MemoryManager.withNamespaceResolver(...)` builder method (optional; without it, behavior is unchanged).
  - Spring Boot starter: `langmem4j.namespace-pattern` SpEL template (e.g. `user_#{#header['X-User-Id'] ?: 'anonymous'}`) with built-in variable providers:
    - `#header['Name']` — current request's HTTP headers (requires spring-web on the classpath; case-insensitive lookups)
    - `#principal` — authenticated user name (requires spring-security-core on the classpath)
  - `langmem4j.namespace-cache.*` — optional LRU result cache (max-size / expire-after-write) for principal-only patterns; header-dependent patterns are never cached.
  - Security: patterns are evaluated with `SimpleEvaluationContext` — no `T()` type references, no constructors, no static methods; only whitelisted variables are visible.
  - Example: `MultiTenancyIsolationTest` in `langmem4j-example-springboot` demonstrates per-user isolation (`alice`/`bob` same key, different namespaces) against a real servlet container.

### Fixed

- Starter `requestNamespaceVariables` provider: headers are now exposed as a single `header` map variable (previously flattened into top-level variables, which silently disabled `#header`-based patterns); header lookups are now case-insensitive (`X-User-Id` matches `x-user-id`).

### Tests

- 242 passed · 4 skipped (`@Disabled` Qdrant integration tests require Docker) · 0 failures, up from 213: +9 core (`MemoryManagerMetricsTest`), +8 observability (`MicrometerMemoryMetricsRecorderTest`), +9 starter (`LangMem4jHealthIndicatorTest`, `ObservabilityAutoConfigurationTest`), +3 example (`ObservabilityEndpointsTest`).
