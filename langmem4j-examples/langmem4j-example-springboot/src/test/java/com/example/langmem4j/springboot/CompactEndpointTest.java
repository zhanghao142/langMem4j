package com.example.langmem4j.springboot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Compaction endpoint test — runs in its own Spring context with a dedicated
 * namespace, because compact() collapses every memory in the namespace and
 * would otherwise interfere with the other tests' fixtures.
 */
@SpringBootTest(properties = "langmem4j.default-namespace=compact_test")
@AutoConfigureMockMvc
class CompactEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void compact_endpoint_reduces_fragments_to_category_summaries() throws Exception {
        // Two same-category fragments + one single-category memory
        mockMvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"c1","value":"likes ramen","metadata":{"category":"food"}}
                        """));
        mockMvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"c2","value":"likes sushi","metadata":{"category":"food"}}
                        """));
        mockMvc.perform(post("/api/memories").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"c3","value":"has a cat","metadata":{"category":"pet"}}
                        """));

        MvcResult result = mockMvc.perform(post("/api/memories/compact"))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        // food fragments (c1+c2) collapsed into food_compacted; pet stays
        assertThat(body).contains("food_compacted");
        assertThat(body).contains("c3");
        assertThat(body).doesNotContain("c1");
        assertThat(body).doesNotContain("c2");

        // The compacted summary contains both original facts
        mockMvc.perform(get("/api/memories/food_compacted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value(org.hamcrest.Matchers.containsString("ramen")))
                .andExpect(jsonPath("$.value").value(org.hamcrest.Matchers.containsString("sushi")));
    }
}
