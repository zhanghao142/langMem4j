package com.langmem4j.spring;

import com.langmem4j.core.namespace.NamespaceResolver;
import org.springframework.expression.Expression;
import org.springframework.expression.ParseException;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves namespaces from a SpEL template such as
 * {@code user_#{#principal}} or
 * {@code tenant_#{#header['X-Tenant-Id'] ?: 'anonymous'}}.
 * <p>
 * The template uses SpEL's {@code #{...}} template syntax; everything outside
 * {@code #{...}} is literal text. Variables come from the registered
 * {@link NamespaceVariables} providers — the starter supplies:
 * <ul>
 *   <li>{@code #principal} — the authenticated user name
 *       (when spring-security-core is on the classpath)</li>
 *   <li>{@code #header['Name']} — read-only map of the current request's
 *       HTTP headers (when spring-web is on the classpath)</li>
 * </ul>
 *
 * <h3>Fallback semantics</h3>
 * {@link #resolve()} returns {@code null} — making the manager fall back to
 * its configured default namespace — when:
 * <ol>
 *   <li>a variable referenced by the template is not present in the current
 *       context (e.g. unauthenticated, or a header is missing <em>and</em> the
 *       template has no {@code ?:} default of its own), or</li>
 *   <li>the template evaluates to null/blank.</li>
 * </ol>
 * This guarantees a well-defined namespace for every call instead of
 * rendering {@code "user_null"} buckets.
 *
 * <h3>Security</h3>
 * Expressions are evaluated with {@link SimpleEvaluationContext}
 * (read-only data binding): no {@code T()} type references, no constructors,
 * no static methods, no bean references. Only the whitelisted variables above
 * are visible to the expression. Misuse of {@code T()} fails loudly at
 * resolve time with a {@link org.springframework.expression.spel.SpelEvaluationException}.
 *
 * <h3>Performance</h3>
 * The template is parsed once at construction; {@link Expression#getValue}
 * on a pre-parsed expression is thread-safe. When a
 * {@link NamespaceResultCache} is provided and the template references
 * nothing but {@code #principal}, results are cached per principal with the
 * configured TTL — a principal-keyed cache is safe because such results are
 * a pure function of the principal. Header-dependent patterns are never
 * cached (see {@link NamespaceResultCache}).
 */
public class PatternNamespaceResolver implements NamespaceResolver {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final Pattern VARIABLE_REFERENCE = Pattern.compile("#([a-zA-Z_][a-zA-Z0-9_]*)");

    /** The only variable a result may depend on and still be cacheable. */
    private static final Set<String> CACHEABLE_VARIABLES = Set.of("principal");

    private final String pattern;
    private final Expression expression;
    private final List<NamespaceVariables> providers;
    private final NamespaceResultCache cache;
    private final Set<String> referencedVariables;
    private final boolean cacheEligible;
    private long evaluations;   // visible via evaluations(); not volatile — diagnostic only

    /**
     * @param pattern   SpEL template, e.g. {@code user_#{#principal}}; must not be blank
     * @param providers variable providers, consulted in order on every resolve
     * @param cache     optional result cache; null disables caching
     * @throws ParseException when the template is not a valid SpEL template
     */
    public PatternNamespaceResolver(String pattern,
                                    List<NamespaceVariables> providers,
                                    NamespaceResultCache cache) {
        if (pattern == null || pattern.isBlank()) {
            throw new IllegalArgumentException("namespace-pattern must not be blank");
        }
        this.pattern = pattern;
        this.expression = PARSER.parseExpression(pattern, new TemplateParserContext());
        this.providers = List.copyOf(providers);
        this.cache = cache;
        this.referencedVariables = extractReferencedVariables(pattern);
        this.cacheEligible = cache != null && CACHEABLE_VARIABLES.containsAll(referencedVariables);
    }

    @Override
    public String resolve() {
        Map<String, Object> vars = new LinkedHashMap<>();
        for (NamespaceVariables provider : providers) {
            vars.putAll(provider.variables());
        }
        // A referenced variable that no provider supplies means "no context
        // for this call" → fall back to the default namespace.
        for (String referenced : referencedVariables) {
            if (!vars.containsKey(referenced)) {
                return null;
            }
        }

        String cacheKey = null;
        if (cacheEligible) {
            cacheKey = "principal=" + vars.get("principal");
            String cached = cache.get(cacheKey);
            if (cached != null) {
                return cached;
            }
        }

        evaluations++;
        String result = evaluate(vars);

        if (result == null || result.isBlank()) {
            return null;   // blank namespace → manager falls back to default
        }
        if (cacheKey != null) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    /** Number of SpEL evaluations performed (parse is cached; this counts getValue calls). Exposed for tests and diagnostics. */
    public long evaluations() {
        return evaluations;
    }

    /** The configured template, as given. */
    public String pattern() {
        return pattern;
    }

    private String evaluate(Map<String, Object> vars) {
        SimpleEvaluationContext context = SimpleEvaluationContext.forReadOnlyDataBinding().build();
        vars.forEach(context::setVariable);
        return expression.getValue(context, String.class);
    }

    private static Set<String> extractReferencedVariables(String pattern) {
        Set<String> referenced = new LinkedHashSet<>();
        Matcher matcher = VARIABLE_REFERENCE.matcher(pattern);
        while (matcher.find()) {
            referenced.add(matcher.group(1));
        }
        return Set.copyOf(referenced);
    }
}
