package com.langmem4j.core.namespace;

/**
 * Resolves the namespace for the <em>current execution context</em> at runtime.
 * <p>
 * Without a resolver, the namespace of the single-argument
 * {@code MemoryManager} methods ({@code add(key, value)}, {@code search(q, n)},
 * …) is the static {@code defaultNamespace} configured on the builder. With a
 * resolver, that namespace is computed <strong>per call</strong> — the basis
 * for multi-tenant routing with zero per-tenant code:
 *
 * <pre>{@code
 * MemoryManager manager = MemoryManager.inMemory()
 *         .withDefaultNamespace("default")
 *         .withNamespaceResolver(() -> "user_" + currentUser())  // e.g. from ThreadLocal
 *         .build();
 *
 * manager.add("food", "Alice likes hot pot");   // lands in user_alice
 * manager.get("food");                          // reads from user_alice
 * }</pre>
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Return the namespace for the current call, or {@code null}/{@code blank}
 *       to fall back to the manager's {@code defaultNamespace}.</li>
 *   <li>Implementations are consulted on <strong>every</strong> manager call
 *       that does not take an explicit namespace — they should be cheap and
 *       side-effect free.</li>
 *   <li>Implementations must be thread-safe. Typical sources: thread-local
 *       request context, {@code SecurityContext}, or an explicit argument
 *       captured in a closure.</li>
 *   <li>Resolved values follow the naming convention
 *       {@code user_{id} / org_{id} / agent_{name}} — named by business entity.</li>
 * </ul>
 *
 * <h3>Design notes</h3>
 * <ul>
 *   <li>This SPI lives in core (zero framework dependencies) on purpose:
 *       Spring users implement it with Spring primitives
 *       (SecurityContext / request headers — see the starter's
 *       {@code PatternNamespaceResolver}), non-Spring users with plain
 *       {@code ThreadLocal}s.</li>
 *   <li>Methods that take an explicit namespace are unaffected — the resolver
 *       only backs the no-namespace overloads.</li>
 * </ul>
 */
@FunctionalInterface
public interface NamespaceResolver {

    /**
     * Resolves the namespace for the current call.
     *
     * @return the namespace to route this call to, or {@code null}/blank to
     *         use the manager's configured default namespace
     */
    String resolve();

    /**
     * No dynamic resolution — every no-namespace call falls back to the
     * manager's {@code defaultNamespace}. This is the default behavior when
     * no resolver is configured, so existing code is unaffected.
     */
    NamespaceResolver FIXED = () -> null;
}
