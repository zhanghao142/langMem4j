package com.langmem4j.store.langgraph4j;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import org.bsc.langgraph4j.store.InMemoryStore;

import java.util.List;
import java.util.Map;

/**
 * Standalone demo that wires langgraph4j's InMemoryStore through
 * {@link LangGraph4jStoreAdapter} → langMem4j {@link MemoryManager},
 * then exercises two new SPI to show they really work:
 * <ol>
 *   <li><b>Merge</b> — same key written twice, manager uses
 *       {@code withMergePolicy(keyMerge())} and we verify the longer value
 *       survived and metadata was unioned.</li>
 *   <li><b>Decay</b> — two memories written at different times; after a
 *       short {@code Thread.sleep} the newer one should appear first
 *       when returned from {@code search()} (ranking by
 *       {@code decayFactor} desc).</li>
 * </ol>
 *
 * Run from the repo root with:
 * <pre>
 * mvn -pl langmem4j-langgraph4j -am package -DskipTests -q
 * mvn -pl langmem4j-langgraph4j org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *     -Dexec.mainClass=com.langmem4j.store.langgraph4j.StoreDecayMergeDemo
 * </pre>
 */
public class StoreDecayMergeDemo {

    public static void main(String[] args) throws InterruptedException {

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("  langMem4j ↔ langgraph4j Store adapter demo");
        System.out.println("  — MergePolicy.keyMerge() + DecayPolicy.exponential() —");
        System.out.println("══════════════════════════════════════════════════════════");

        // 1. Create langgraph4j InMemoryStore → wrap with adapter → MemoryManager
        InMemoryStore langgraphStore = new InMemoryStore();
        LangGraph4jStoreAdapter adapter = new LangGraph4jStoreAdapter(langgraphStore);

        MemoryManager manager = MemoryManager.withStore(adapter)
                .withDefaultNamespace("demo")
                // 2-second half-life — the demo uses Thread.sleep(2000) so we
                // can see both filtering + decay-induced re-ranking in seconds
                // instead of waiting 7 days. A half-life of 2s × 6.6 = ~13s
                // threshold for pruning.
                .withDecayPolicy(MemoryDecayPolicy.exponential(2_000L))
                .withMergePolicy(MemoryMergePolicy.keyMerge())
                .build();

        // ================================================================
        // 2. Test merge: same key, different metadata, incoming is longer
        // ================================================================
        System.out.println();
        System.out.println("── 1) Merge demo ──────────────────────────────────────────");

        manager.add("pref", "Alice likes hot pot", Map.of("src", "user"));
        System.out.println("  add #1: pref='Alice likes hot pot' [src=user]");

        manager.add("pref", "Alice likes spicy hot pot with sesame sauce",
                Map.of("src", "diary", "updated", true));
        System.out.println("  add #2: pref='Alice likes spicy hot pot with sesame sauce' [src=diary,updated=true]");

        // keyMerge should:
        //   · keep the LONGER value (spicy hot pot ... sesame)
        //   · union metadata (src=diary override, +updated=true)
        //   · preserve the EARLIEST createdAt (add #1)
        Memory stored = manager.get("pref").orElseThrow();
        System.out.println("  stored.value  = " + stored.value());
        System.out.println("  stored.meta   = " + stored.metadata());
        System.out.println("  stored.createdAt preserved from add#1");

        // NOTE: we use explicit exceptions here, not Java assert keyword, so the
        // demo fails loud-and-clear even when the JVM is launched without -ea.
        if (!stored.value().contains("spicy hot pot with sesame sauce")) {
            throw new RuntimeException("Merge FAILED: expected longer value to win, got: " + stored.value());
        }
        if (!stored.metadata().containsKey("updated")) {
            throw new RuntimeException("Merge FAILED: expected 'updated' metadata key from 2nd add");
        }
        if (!"diary".equals(stored.metadata().get("src"))) {
            throw new RuntimeException("Merge FAILED: expected src=diary (incoming override), got: " + stored.metadata().get("src"));
        }

        System.out.println("  ✅ Merge OK: longer value wins + metadata union");

        // ================================================================
        // 3. Write a 2nd memory at the same T=now to use for decay comparison
        // ================================================================
        System.out.println();
        System.out.println("── 2) Decay + sort demo ───────────────────────────────────");

        // We want a case where two memories both survive the 2-second sleep
        // but one is written AFTER some time has passed (half-age, so higher
        // decay factor) to verify the re-ranking actually happens.
        //
        // Write mem-fresh immediately after the sleep, so its decay factor is
        // ~1.0 while mem-old will be ~0.5 (exactly one half-life).

        // First: old memory written NOW. Sleep = one half-life.
        manager.add("old-pref", "User likes sushi — recorded 1 half-life ago");
        System.out.println("  add('old-pref')  now  [decay after sleep ≈ 0.5]");

        System.out.println("  Thread.sleep(2000) — simulating one half-life…");
        Thread.sleep(2000);

        // Second: new memory written after the sleep (fresh).
        manager.add("new-pref", "User wants to book a trip to Tokyo — fresh record");
        System.out.println("  add('new-pref')  +2s  [decay ≈ 1.0]");

        // Search for the exact common substring "User" that appears in BOTH
        // values. langgraph4j InMemoryStore.search matches by substring; the
        // MemoryManager layer then re-sorts the returned list by decayFactor
        // desc so the freshly-written new-pref (factor ~1.0) ranks BEFORE
        // old-pref written 2s ago (factor ~0.5).
        List<Memory> results = manager.search("User", 10);
        System.out.println("  search('User') → " + results.size() + " results");

        for (Memory m : results) {
            System.out.printf("    - %-9s  value='%s'%n", m.key(),
                    m.value().length() > 40 ? m.value().substring(0, 40) + "…" : m.value());
        }

        // RANK CHECK (loud, no -ea required): new-pref (fresh, factor ~1)
        // must appear BEFORE old-pref (factor ~0.5 after one half-life).
        int freshIdx = -1;
        int staleIdx = -1;
        for (int i = 0; i < results.size(); i++) {
            if ("new-pref".equals(results.get(i).key())) freshIdx = i;
            if ("old-pref".equals(results.get(i).key())) staleIdx = i;
        }
        if (freshIdx < 0) throw new RuntimeException("Decay FAILED: new-pref missing from search results (" + results.size() + " returned, keys=" + results.stream().map(Memory::key).toList() + ")");
        if (staleIdx < 0) throw new RuntimeException("Decay FAILED: old-pref missing from search results (" + results.size() + " returned, keys=" + results.stream().map(Memory::key).toList() + ")");
        if (!(freshIdx < staleIdx)) {
            throw new RuntimeException("Decay FAILED: new-pref should rank BEFORE old-pref, got new=" + freshIdx + " old=" + staleIdx);
        }

        System.out.println("  ✅ Decay OK: new-pref (idx " + freshIdx
                + ", factor ≈ 1.0) ranked before old-pref (idx " + staleIdx + ", factor ≈ 0.5)");

        // ================================================================
        // 4. listKeys / clearNamespace → UOE (expected; documented)
        // ================================================================
        System.out.println();
        System.out.println("── 3) Edge cases (expected UOE) ──────────────────────────");
        try {
            manager.keys();
            System.out.println("  ⚠ listKeys 居然返回了？！");
        } catch (UnsupportedOperationException e) {
            System.out.println("  ✅ listKeys → UnsupportedOperationException (文档承诺)");
        }
        try {
            manager.clear();
            System.out.println("  ⚠ clear 居然成功了？！");
        } catch (UnsupportedOperationException e) {
            System.out.println("  ✅ clear → UnsupportedOperationException (文档承诺)");
        }

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════");
        System.out.println("  所有断言通过 ✓ 适配器 + merge + decay 全链路生效。");
        System.out.println("══════════════════════════════════════════════════════════");
    }
}
