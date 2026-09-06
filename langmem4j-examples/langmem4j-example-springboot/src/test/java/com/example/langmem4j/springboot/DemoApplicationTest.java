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
 * Integration tests: full Spring context with the langMem4j starter
 * auto-configuration applied from application.yml. No LLM, no external
 * infra — InMemory store makes everything runnable in milliseconds.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTest {

    @Autowired
    private MockMvc mockMvc;

    // ── Context loads with starter auto-configuration ─────────

    @Test
    void context_loads_with_autoconfigured_memory_manager() throws Exception {
        mockMvc.perform(get("/api/memories/search").param("q", "anything"))
                .andExpect(status().isOk());
    }

    // ── REST endpoints ────────────────────────────────────────

    @Test
    void add_then_get_returns_saved_memory_with_metadata() throws Exception {
        mockMvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"key":"favorite_food","value":"Alice loves hot pot","metadata":{"category":"food"}}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("saved"));

        mockMvc.perform(get("/api/memories/favorite_food"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("favorite_food"))
                .andExpect(jsonPath("$.value").value("Alice loves hot pot"))
                .andExpect(jsonPath("$.metadata.category").value("food"));
    }

    @Test
    void memory_persists_across_calls_and_search_finds_it() throws Exception {
        // First call: save
        mockMvc.perform(post("/api/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"user_city","value":"Alice lives in Shanghai","metadata":{"category":"profile"}}
                        """));

        // Second call: search finds what the first call stored
        mockMvc.perform(get("/api/memories/search")
                        .param("q", "Shanghai")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'user_city')]").exists());
    }

    @Test
    void merge_enabled_two_adds_same_key_keep_longer_value() throws Exception {
        mockMvc.perform(post("/api/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"merge_demo","value":"short","metadata":{}}
                        """));
        mockMvc.perform(post("/api/memories")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"key":"merge_demo","value":"a much longer and more specific value","metadata":{}}
                        """));

        mockMvc.perform(get("/api/memories/merge_demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.value").value("a much longer and more specific value"));
    }
}
