package com.example.langmem4j.springboot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-tenant isolation demo: the starter's {@code namespace-pattern}
 * routes every request to {@code user_<X-User-Id>} at runtime — the
 * controller code is completely tenant-unaware (no header parsing, no
 * per-tenant branches).
 * <p>
 * Runs against a real servlet container (RANDOM_PORT) so the full
 * filter chain is active — that is what binds the incoming request to
 * {@code RequestContextHolder}, which the pattern's {@code #header}
 * variable reads from.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MultiTenancyIsolationTest {

    @Autowired
    private TestRestTemplate rest;

    // Keys are unique per test: the InMemory store is shared across tests
    // in the same JVM, and merge.enabled=true would otherwise collide.
    private static final String TENANT_FOOD =
            "{\"key\":\"tenant_food\",\"value\":\"%s\",\"metadata\":{\"category\":\"food\"}}";
    private static final String HOME_CITY =
            "{\"key\":\"home_city\",\"value\":\"%s\",\"metadata\":{\"category\":\"profile\"}}";
    private static final String ANONYMOUS_SNACK =
            "{\"key\":\"anonymous_snack\",\"value\":\"%s\",\"metadata\":{\"category\":\"food\"}}";

    private static HttpEntity<String> asUser(String userId, String json) {
        HttpHeaders headers = new HttpHeaders();
        if (userId != null) {
            headers.set("X-User-Id", userId);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    @Test
    void different_users_do_not_see_each_others_memories() {
        // Alice and Bob write to the SAME key — but different namespaces
        rest.postForEntity("/api/memories", asUser("alice", TENANT_FOOD.formatted("Alice loves hot pot")), String.class);
        rest.postForEntity("/api/memories", asUser("bob", TENANT_FOOD.formatted("Bob loves sushi")), String.class);

        // Each user reads back only their own memory
        assertThat(rest.exchange("/api/memories/tenant_food", org.springframework.http.HttpMethod.GET,
                        asUser("alice", ""), String.class).getBody())
                .contains("Alice loves hot pot");
        assertThat(rest.exchange("/api/memories/tenant_food", org.springframework.http.HttpMethod.GET,
                        asUser("bob", ""), String.class).getBody())
                .contains("Bob loves sushi");
    }

    @Test
    void search_is_scoped_to_the_requesting_user() {
        rest.postForEntity("/api/memories", asUser("carol", HOME_CITY.formatted("Carol lives in Chengdu")), String.class);

        // Carol finds her memory...
        assertThat(rest.exchange("/api/memories/search?q=Chengdu&limit=10", org.springframework.http.HttpMethod.GET,
                        asUser("carol", ""), String.class).getBody())
                .contains("home_city");

        // ...dave (empty namespace) does not
        assertThat(rest.exchange("/api/memories/search?q=Chengdu&limit=10", org.springframework.http.HttpMethod.GET,
                        asUser("dave", ""), String.class).getBody())
                .doesNotContain("home_city");
    }

    @Test
    void missing_header_falls_back_to_anonymous_bucket() {
        // No X-User-Id header → the pattern's ?: default routes to
        // user_anonymous instead of failing
        ResponseEntity<String> saved = rest.postForEntity("/api/memories",
                asUser(null, ANONYMOUS_SNACK.formatted("Anonymous snack preference")), String.class);
        assertThat(saved.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(rest.getForEntity("/api/memories/anonymous_snack", String.class).getBody())
                .contains("Anonymous snack preference");
    }
}
