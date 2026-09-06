package com.langmem4j.spring;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the built-in {@code requestNamespaceVariables} provider:
 * headers must be exposed as a single {@code header} map variable (not flattened
 * into the variable namespace), lookups are case-insensitive, and outside a
 * request the provider contributes nothing so patterns fall back to the
 * default namespace.
 */
class RequestNamespaceVariablesTest {

    private final NamespaceVariables provider =
            new LangMem4jAutoConfiguration.RequestVariablesConfiguration().requestNamespaceVariables();

    @AfterEach
    void resetRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void headers_are_wrapped_under_a_single_header_variable() {
        // Regression: the provider once flattened headers into top-level
        // variables (X-User-Id=alice), so #header was never present and
        // every request silently fell back to the default namespace.
        bindRequest("X-User-Id", "alice");

        Map<String, Object> vars = provider.variables();

        assertThat(vars).containsOnlyKeys("header");
        @SuppressWarnings("unchecked")
        Map<String, String> headers = (Map<String, String>) vars.get("header");
        assertThat(headers).containsEntry("X-User-Id", "alice");
    }

    @Test
    void header_lookup_is_case_insensitive() {
        // Regression: some clients/servers normalize header names to lower
        // case; #header['X-User-Id'] must still match x-user-id.
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("x-user-id", "alice");   // lower case on the wire
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        Map<?, ?> headers = (Map<?, ?>) provider.variables().get("header");

        assertThat(headers.get("X-User-Id")).isEqualTo("alice");
        assertThat(headers.get("x-user-id")).isEqualTo("alice");
    }

    @Test
    void no_headers_yields_empty_but_present_header_variable() {
        // In a request with no relevant headers, #header must exist (empty)
        // so a pattern like #header['X-User-Id'] ?: 'anonymous' can still
        // resolve with its own default.
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest()));

        assertThat(provider.variables()).containsOnlyKeys("header");
        assertThat((Map<?, ?>) provider.variables().get("header")).isEmpty();
    }

    @Test
    void outside_a_request_nothing_is_contributed() {
        // Background jobs / scheduled tasks: no request bound → no variables
        // → the resolver returns null → manager falls back to defaultNamespace.
        assertThat(provider.variables()).isEmpty();
    }

    private static void bindRequest(String name, String value) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(name, value);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
