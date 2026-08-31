package com.langmem4j.example.plain;

import com.langmem4j.core.manager.MemoryManager;
import com.langmem4j.core.memory.Memory;
import com.langmem4j.core.store.MemoryFilter;
import com.langmem4j.core.store.MemoryStore;
import com.langmem4j.core.store.inmemory.InMemoryMemoryStore;
import com.langmem4j.tools.core.SaveMemoryService;
import com.langmem4j.tools.core.SearchMemoryService;

import java.util.List;
import java.util.Map;

/**
 * 最小 Java SE 示例：展示 langMem4j 的两条典型接入路径。
 *
 * <h3>路径 A：只用核心 SPI（MemoryStore + MemoryManager）</h3>
 * 适合你已经在其他地方定义了"写/搜索记忆"的规则，直接用 manager 调就行。
 *
 * <h3>路径 B：接上 LLM 工具层（langmem4j-tools-core，纯 Java，零 LangChain4j 依赖）</h3>
 * SaveMemoryService / SearchMemoryService 封装了 LLM 工具最常见的 3 个业务语义：
 * <ul>
 *   <li>"a=b,c=d" 字符串 → metadata Map（"shared" 这种只有 key 的解析成 Boolean.TRUE）</li>
 *   <li>limit clamp（null→5，>20→20），避免 LLM 把 limit 设成 999 拖垮存储</li>
 *   <li>string 出入参，直接丢进 LLM context 就能读</li>
 * </ul>
 *
 * <h3>构建 & 运行</h3>
 * <pre>{@code
 * # 打 jar（也会把 Memory + tools-core Service 一起编译）
 * mvn -pl langmem4j-examples/langmem4j-example-plain -am package
 *
 * # 跑 main（用 java 或 IDE 都行，不需要 exec:java / mvn 插件）
 * java -cp target/langmem4j-example-plain-0.1.0-SNAPSHOT.jar:target/libs/* \
 *      com.langmem4j.example.plain.PlainExample
 * }</pre>
 */
public class PlainExample {

    public static void main(String[] args) {
        //
        // 路径 A：MemoryManager（上层入口）
        //
        MemoryManager manager = MemoryManager.inMemory()
                .withDefaultNamespace("user_alice")
                .build();

        manager.add("favorite_food", "Alice loves hot pot",
                Map.of("category", "food", "source", "user"));
        manager.add("hobby", "Alice plays guitar",
                Map.of("category", "hobby", "source", "user"));
        manager.add("diary_01", "Finished MemoryFilter + tools-core",
                Map.of("category", "work", "source", "sync", "confidential", true));

        // Namespace 隔离：同一 store 下 bob 的记忆不混进 alice
        manager.add("user_bob", "bob_pref", "Bob hates hot pot",
                Map.of("category", "food"));

        // MemoryFilter（V1 新增的 SPI 级 4 参 search）
        //   语义：metadata 所有 key 做 AND；minScore 走余弦阈值（没配 embedder 时 substring 搜索不看）
        List<Memory> onlyFood = manager.search("loves", 5,
                MemoryFilter.metadata(Map.of("category", "food")));
        System.out.println("路径 A — search(category=food) 命中:");
        onlyFood.forEach(m -> System.out.printf("  %-16s  %s%n", m.key(), m.value()));

        //
        // 路径 B：tools-core 纯 Java 服务层（SaveMemoryService / SearchMemoryService）
        //
        MemoryStore store = new InMemoryMemoryStore();
        SaveMemoryService saveSvc = new SaveMemoryService(store, "user_alice");
        SearchMemoryService searchSvc = new SearchMemoryService(store, "user_alice");

        saveSvc.saveMemoryWithMetadata("snack_rec",
                "推荐长沙的臭豆腐和幽兰拿铁",
                "category=food,source=recommend"); // a=b,c=d 自动解析
        saveSvc.saveMemoryWithMetadata("todo_share",
                "明天把 langMem4j 发版文档写完",
                "shared,category=todo"); // "shared" 只有 key → true

        // limit=null → clamp→5；metadata="category=food" → 转 MemoryFilter.metadata(...)
        String answer = searchSvc.searchMemory("长沙小吃", null, "category=food");
        System.out.println("\n路径 B — searchMemory(category=food) 返回 (LLM 直接读的文本块):");
        System.out.println(answer);

        System.out.println("\nlistMemories: " + searchSvc.listMemories());
    }
}
