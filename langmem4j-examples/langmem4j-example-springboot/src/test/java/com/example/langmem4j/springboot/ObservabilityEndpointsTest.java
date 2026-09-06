package com.example.langmem4j.springboot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end observability contract: the starter's conditional wiring must
 * surface langMem4j through the standard actuator endpoints once
 * spring-boot-starter-actuator is on the classpath —
 * {@code /actuator/metrics} exposes {@code langmem4j_*} meters that actually
 * move as the app is used, and {@code /actuator/health} reports a
 * {@code langMem4j} component backed by the write-read-delete probe.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ObservabilityEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void metrics_endpoint_exposes_langmem4j_add_total_that_counts_requests() throws Exception {
        mockMvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"obs_key","value":"observability demo value"}
                        """))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics/langmem4j_add_total"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("langmem4j_add_total"))
                // at least the POST above (plus any earlier probe writes)
                .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                .andExpect(jsonPath("$.measurements[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)))
                // multi-tenant routing is visible in the tag dimension
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'namespace')]").exists())
                .andExpect(jsonPath("$.availableTags[?(@.tag == 'status')]").exists());
    }

    @Test
    void metrics_endpoint_exposes_search_timer_once_searched() throws Exception {
        mockMvc.perform(get("/api/memories/search").param("q", "observability"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics/langmem4j_search_duration_seconds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("langmem4j_search_duration_seconds"))
                .andExpect(jsonPath("$.measurements[0].statistic").value("COUNT"))
                .andExpect(jsonPath("$.measurements[0].value").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1.0)));
    }

    @Test
    void health_endpoint_reports_langmem4j_component_with_store_details() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.langMem4j.status").value("UP"))
                .andExpect(jsonPath("$.components.langMem4j.details.store").value("InMemoryMemoryStore"))
                .andExpect(jsonPath("$.components.langMem4j.details.memoryCount").exists());
    }
}
