# Changelog

## [Unreleased]

### Added

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

- 213 passed · 4 skipped (`@Disabled` Qdrant integration tests require Docker) · 0 failures, up from 180: +8 core (`MemoryManagerNamespaceResolverTest`), +22 starter (`PatternNamespaceResolverTest`, `RequestNamespaceVariablesTest`, resolver wiring in `LangMem4jAutoConfigurationTest`), +3 example (`MultiTenancyIsolationTest`).
