package com.langmem4j.core.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MemoryDecayPolicyTest {

    // ----- NONE -----

    @Test
    void NONE_always_returns_one() {
        MemoryDecayPolicy p = MemoryDecayPolicy.NONE;
        assertThat(p.decayFactor(0, 0, 0)).isEqualTo(1.0f);
        assertThat(p.decayFactor(1000, 2000, 999999)).isEqualTo(1.0f);
    }

    @Test
    void NONE_prune_threshold_is_default() {
        assertThat(MemoryDecayPolicy.NONE.pruneThreshold()).isEqualTo(0.01f);
    }

    // ----- exponential() — default 7-day half-life -----

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long WEEK = 7 * DAY;

    @Test
    void exponential_age_zero_returns_one() {
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential();
        long now = System.currentTimeMillis();
        assertThat(p.decayFactor(now, now, now)).isEqualTo(1.0f);
    }

    @Test
    void exponential_at_one_half_life_returns_half() {
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential(); // 7-day half-life
        long now = 1_000_000L;
        long created = now - WEEK;
        assertThat(p.decayFactor(created, created, now))
                .isCloseTo(0.5f, within(0.001f));
    }

    @Test
    void exponential_at_two_half_lives_returns_quarter() {
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential();
        long now = 1_000_000L;
        long created = now - 2 * WEEK;
        assertThat(p.decayFactor(created, created, now))
                .isCloseTo(0.25f, within(0.001f));
    }

    @Test
    void exponential_decays_based_on_lastAccessed_not_created() {
        // Memory created 3 weeks ago but accessed 1 day ago.
        // Decay should be based on lastAccessedAt (1 day), not createdAt (3 weeks).
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential();
        long now = 1_000_000L;
        long created = now - 3 * WEEK;
        long accessed = now - DAY;
        float factor = p.decayFactor(created, accessed, now);
        // 1 day / 7 days → 0.5^(1/7) ≈ 0.906
        assertThat(factor).isCloseTo(0.906f, within(0.01f));
        // If it were based on createdAt: 0.5^(21/7) = 0.125 — very different
        assertThat(factor).isGreaterThan(0.5f);
    }

    // ----- exponential(long halfLifeMs) — custom -----

    @Test
    void exponential_custom_half_life_at_exact_half() {
        long halfLife = 10_000L; // 10 seconds
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential(halfLife);
        long now = 500_000L;
        long created = now - halfLife;
        assertThat(p.decayFactor(created, created, now))
                .isCloseTo(0.5f, within(0.001f));
    }

    @Test
    void exponential_non_positive_half_life_yields_no_decay() {
        MemoryDecayPolicy zero = MemoryDecayPolicy.exponential(0);
        MemoryDecayPolicy neg = MemoryDecayPolicy.exponential(-1);
        long now = 1_000_000L;
        assertThat(zero.decayFactor(0, 0, now)).isEqualTo(1.0f);
        assertThat(neg.decayFactor(0, 0, now)).isEqualTo(1.0f);
    }

    @Test
    void exponential_future_age_returns_one() {
        // lastAccessedAt in the future → no decay (age <= 0)
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential();
        long now = 1_000_000L;
        assertThat(p.decayFactor(0, now + 1000, now)).isEqualTo(1.0f);
    }

    // ----- pruneThreshold -----

    @Test
    void exponential_inherits_default_prune_threshold() {
        MemoryDecayPolicy p = MemoryDecayPolicy.exponential();
        assertThat(p.pruneThreshold()).isEqualTo(0.01f);
    }

    @Test
    void custom_prune_threshold_can_be_overridden() {
        MemoryDecayPolicy custom = new MemoryDecayPolicy() {
            @Override
            public float decayFactor(long createdAt, long lastAccessedAt, long now) {
                return 1.0f;
            }
            @Override
            public float pruneThreshold() {
                return 0.05f;
            }
        };
        assertThat(custom.pruneThreshold()).isEqualTo(0.05f);
    }
}
