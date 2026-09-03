package com.langmem4j.example.plain;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.memory.MemoryDecayPolicy;
import com.langmem4j.core.memory.MemoryMergePolicy;
import com.langmem4j.core.store.MemoryFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 多轮对话记忆演示：模拟 10 轮对话，验证 decay 排序 + merge 语义。
 *
 * <h3>场景</h3>
 * <ol>
 *   <li>10 轮"对话"，每轮写入一条记忆</li>
 *   <li>第 4 轮写入 user_food，第 5-10 轮对同一 key 反复更新 → 触发 keyMerge</li>
 *   <li>每轮间隔 500ms，配合 2s 半衰期让 decay 效果肉眼可见</li>
 * </ol>
 *
 * <h3>验证 3 件事</h3>
 * <ul>
 *   <li><b>merge</b>：user_food 最终保留最长 value + union metadata + 最早 createdAt</li>
 *   <li><b>decay 排序</b>：新鲜记忆排前面；user_food 因 merge 刷新 lastAccessedAt → 排第一</li>
 *   <li><b>filter + decay 组合</b>：MemoryFilter(category=food) 只返回 1 条（merge 后的 user_food）</li>
 * </ul>
 *
 * <h3>运行</h3>
 * <pre>{@code
 * mvn -pl langmem4j-examples/langmem4j-example-plain -am install -DskipTests -q
 * mvn -pl langmem4j-examples/langmem4j-example-plain \
 *     org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
 *     -Dexec.mainClass=com.langmem4j.example.plain.ConversationMemoryDemo
 * }</pre>
 */
public class ConversationMemoryDemo {

    /** 2 秒半衰期 — 配合 500ms 间隔，10 轮 ≈ 5s 总耗时 */
    private static final long HALF_LIFE_MS = 2000;
    private static final long ROUND_INTERVAL_MS = 500;

    public static void main(String[] args) throws InterruptedException {

        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user_alice")
                .withDecayPolicy(MemoryDecayPolicy.exponential(HALF_LIFE_MS))
                .withMergePolicy(MemoryMergePolicy.keyMerge())
                .build();

        System.out.println("═".repeat(70));
        System.out.println("  ConversationMemoryDemo — 10 轮对话 + decay 排序 + keyMerge");
        System.out.printf("  halfLife=%dms  interval=%dms  total≈%.1fs%n",
                HALF_LIFE_MS, ROUND_INTERVAL_MS, 10 * ROUND_INTERVAL_MS / 1000.0);
        System.out.println("═".repeat(70));

        // ── 10 轮对话数据 ──────────────────────────────────────────
        //  rounds 1-3: 不同 key（user_name / user_job / user_hobby）
        //  round 4:    首次写 user_food
        //  rounds 5-10: 同 key user_food 反复更新 → 每次 add 都触发 keyMerge
        String[][] rounds = {
            {"1",  "user_name",     "Alice",
             "category=profile,round=1"},
            {"2",  "user_job",      "Alice works as a software engineer at Acme",
             "category=profile,round=2"},
            {"3",  "user_hobby",   "Alice plays guitar on weekends",
             "category=hobby,round=3"},
            {"4",  "user_food",    "Alice likes hot pot",
             "category=food,round=4"},
            {"5",  "user_food",    "Alice likes spicy hot pot with extra chili",
             "category=food,round=5,updated=true"},
            {"6",  "user_food",    "Alice likes spicy hot pot with extra chili and sesame sauce",
             "category=food,round=6,updated=true"},
            {"7",  "user_location","Alice lives in Shanghai",
             "category=profile,round=7"},
            {"8",  "user_food",    "Alice likes spicy hot pot with extra chili, sesame sauce, and iced tea",
             "category=food,round=8,updated=true"},
            {"9",  "user_pet",     "Alice has a cat named Luna",
             "category=pet,round=9"},
            {"10", "user_food",    "Alice likes spicy hot pot with extra chili, sesame sauce, iced tea, and garlic noodles",
             "category=food,round=10,updated=true"},
        };

        for (String[] r : rounds) {
            int round = Integer.parseInt(r[0]);
            String key = r[1];
            String value = r[2];
            String metaCsv = r[3];
            Map<String, Object> metadata = parseMetadata(metaCsv);

            System.out.printf("%n── Round %2d ──────────────────────────────────%n", round);
            System.out.printf("  [user]  says something…%n");
            System.out.printf("  [system] save: key='%s'  meta=%s%n", key, metaCsv);
            System.out.printf("           value = %s%n", value);

            manager.add(key, value, metadata);

            // 对 user_food（round ≥ 5）显示 merge 后的实际存储值
            if (round >= 5 && "user_food".equals(key)) {
                Memory stored = manager.get(key).orElseThrow();
                System.out.printf("  → MERGE: stored value = %s%n", truncate(stored.value(), 60));
                System.out.printf("           metadata  = %s%n", stored.metadata());
            }

            if (round < 10) {
                Thread.sleep(ROUND_INTERVAL_MS);
            }
        }

        // ── 验证 1：merge 结果 ────────────────────────────────────
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  验证 1：merge 结果（user_food 经过 5 次 keyMerge）");
        System.out.println("═".repeat(70));

        Memory food = manager.get("user_food").orElseThrow();
        System.out.printf("  key            = %s%n", food.key());
        System.out.printf("  value          = %s%n", food.value());
        System.out.printf("  metadata       = %s%n", food.metadata());
        System.out.printf("  createdAt      = %d  (round 4 的时刻，被 keyMerge 保留)%n", food.createdAt());
        System.out.printf("  lastAccessedAt = %d  (round 10 merge + get 刷新)%n", food.lastAccessedAt());

        String expectedValue =
                "Alice likes spicy hot pot with extra chili, sesame sauce, iced tea, and garlic noodles";
        if (!food.value().equals(expectedValue)) {
            throw new RuntimeException("MERGE FAILED: expected longest value, got: " + food.value());
        }
        if (!Boolean.TRUE.equals(food.metadata().get("updated"))) {
            throw new RuntimeException("MERGE FAILED: expected updated=true, got: " + food.metadata().get("updated"));
        }
        if (!"food".equals(food.metadata().get("category"))) {
            throw new RuntimeException("MERGE FAILED: expected category=food, got: " + food.metadata().get("category"));
        }
        System.out.println("  ✅ Merge 验证通过：最长 value + metadata union + 最早 createdAt 保留");

        // ── 验证 2：decay 排序 ────────────────────────────────────
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  验证 2：decay 排序（search 'Alice'，按新鲜度降序）");
        System.out.println("═".repeat(70));

        List<Memory> results = manager.search("Alice", 20);
        System.out.printf("  search('Alice', 20) → %d results%n%n", results.size());

        long now = System.currentTimeMillis();
        System.out.printf("  %-14s  %8s  %8s  %s%n", "key", "factor", "age(ms)", "value");
        System.out.println("  " + "-".repeat(90));

        for (Memory m : results) {
            long age = now - m.lastAccessedAt();
            double factor = Math.pow(0.5, (double) age / HALF_LIFE_MS);
            System.out.printf("  %-14s  %.4f   %6d    %s%n",
                    m.key(), factor, age, truncate(m.value(), 50));
        }

        // user_food 因 merge 刷新 lastAccessedAt → 应排第一
        // user_name 是 round 1（最老）→ 应排最后
        int foodIdx = indexOfKey(results, "user_food");
        int nameIdx = indexOfKey(results, "user_name");
        if (foodIdx != 0) {
            throw new RuntimeException("DECAY FAILED: expected user_food at index 0 (freshest from merge), "
                    + "got index " + foodIdx + " of " + results.size());
        }
        if (nameIdx >= 0 && nameIdx != results.size() - 1) {
            throw new RuntimeException("DECAY FAILED: expected user_name at the end (oldest, round 1), "
                    + "got index " + nameIdx + " of " + results.size());
        }
        System.out.println("\n  ✅ Decay 排序验证通过：user_food 排第一（merge 刷新 lastAccessedAt），"
                + "user_name 排最后（最老）");

        // ── 验证 3：MemoryFilter + decay 组合 ─────────────────────
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  验证 3：MemoryFilter(category=food) + decay 组合");
        System.out.println("═".repeat(70));

        List<Memory> foodOnly = manager.search("Alice", 20,
                MemoryFilter.metadata(Map.of("category", "food")));
        System.out.printf("  search('Alice', filter=category=food) → %d results%n", foodOnly.size());
        for (Memory m : foodOnly) {
            System.out.printf("    - %-14s  value.len=%d%n", m.key(), m.value().length());
        }
        if (foodOnly.size() != 1 || !"user_food".equals(foodOnly.get(0).key())) {
            throw new RuntimeException("FILTER FAILED: expected only user_food with category=food, got: " + foodOnly);
        }
        System.out.println("  ✅ Filter 验证通过：只返回 category=food 的 1 条（merge 合并后的 user_food）");

        // ── 总结 ──────────────────────────────────────────────────
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  所有验证通过 ✓");
        System.out.println("  10 轮对话 → 5 次 keyMerge → decay 重排序 → MemoryFilter 全链路生效");
        System.out.println("═".repeat(70));
    }

    // ── helpers ────────────────────────────────────────────────────

    /**
     * 简易 "a=b,c=d" 解析（和 SaveMemoryService.parseMetadata 同语义）。
     * 单 key 无 '=' → Boolean.TRUE（布尔标签语义）。
     */
    private static Map<String, Object> parseMetadata(String csv) {
        Map<String, Object> map = new HashMap<>();
        if (csv == null || csv.isBlank()) return map;
        for (String pair : csv.split(",")) {
            String t = pair.trim();
            if (t.isEmpty()) continue;
            int eq = t.indexOf('=');
            if (eq < 0) {
                map.put(t, Boolean.TRUE);
            } else {
                String k = t.substring(0, eq).trim();
                String v = t.substring(eq + 1).trim();
                if (!k.isEmpty()) {
                    if (v.equalsIgnoreCase("true"))        map.put(k, true);
                    else if (v.equalsIgnoreCase("false"))   map.put(k, false);
                    else                                     map.put(k, v);
                }
            }
        }
        return map;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int indexOfKey(List<Memory> results, String key) {
        for (int i = 0; i < results.size(); i++) {
            if (key.equals(results.get(i).key())) return i;
        }
        return -1;
    }
}
