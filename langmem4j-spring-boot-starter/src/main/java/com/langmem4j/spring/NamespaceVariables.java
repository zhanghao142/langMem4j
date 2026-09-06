package com.langmem4j.spring;

import java.util.Map;

/**
 * Supplies SpEL template variables for {@link PatternNamespaceResolver}.
 * <p>
 * Each provider contributes a small, cheap-to-compute set of named variables
 * (e.g. {@code principal}, {@code header}) read from the current thread's
 * context. Providers are consulted on <strong>every</strong>
 * {@link com.langmem4j.core.namespace.NamespaceResolver#resolve()} call and
 * must be thread-safe and side-effect free.
 * <p>
 * The starter auto-configuration registers up to two providers, each guarded
 * by {@code @ConditionalOnClass} so only the ones whose backing library is on
 * the classpath are created:
 * <ul>
 *   <li><strong>principal</strong> — from Spring Security's
 *       {@code SecurityContextHolder} (requires spring-security-core)</li>
 *   <li><strong>header</strong> — read-only map of the current request's HTTP
 *       headers (requires spring-web)</li>
 * </ul>
 * Applications can register additional {@code NamespaceVariables} beans to
 * expose their own template variables.
 */
@FunctionalInterface
public interface NamespaceVariables {

    /**
     * Returns the variables contributed by this provider for the current
     * call. Empty map (never null) when the underlying context is absent
     * (e.g. unauthenticated, no active request).
     */
    Map<String, Object> variables();
}
