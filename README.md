# langMem4j

> Java 生态设计最干净的长期记忆管理中间件。

为 LLM Agent 提供可插拔的长期记忆（Facts / Preferences / Patterns）读写与语义检索能力。
可独立使用，也可作为 **LangChain4j** / **Spring AI** 的工具函数暴露给大模型。

---

## ✨ 特性

- **一个入口，两个 SPI** — `MemoryManager` 是用户唯一需要认识的类；底层通过 `MemoryStore` 切换存储后端、`EmbeddingGenerator` 切换向量模型
- **Namespace 原生隔离** — 同一 store 下 `user_alice / user_bob / agent_prod` 互不干扰，不用你拼前缀
- **Search 自带 Metadata Filter（V1 已支持）** — SPI 统一暴露 `MemoryStore.search(ns, query, limit, MemoryFilter)`，所有实现均承诺 AND 语义 metadata 过滤 + minScore：
  - InMemoryMemoryStore：排序前先剪候选（零网络成本，最严格）
  - QdrantMemoryStore：V1 以探针 limit×10 + 客户端 `MemoryFilter.matchesMetadata()` 精过滤实现；proto 表面稳定后补 `setFilter()` 服务端下推（接口签名不变）
- **零依赖运行时** — core / tools-core 模块只有 `slf4j-api`，生产接入 Qdrant 也仅引入官方 gRPC client
- **开箱即用** — 内置 `InMemoryMemoryStore`，单元测试 / 原型开发不需要任何外部基础设施
- **工具层与框架解耦** — LLM 业务语义（save/search/get/list/delete 的 string 出入参、key=value metadata 解析、limit clamp）放在 `langmem4j-tools-core`（纯 Java）；`langmem4j-tools` 只做 1 行的 LangChain4j `@Tool`/`@P` 薄封装，切换 Spring AI / Semantic Kernel / 自研 JSON-RPC 都无需重复造轮子

---

## 🧩 模块结构

```
langmem4j/
├── langmem4j-core              ← 必选：主依赖
│   ├── memory/Memory.java                 // 不可变 record
│   ├── embedding/EmbeddingGenerator.java  // 向量生成 SPI
│   ├── store/MemoryStore.java             // 存储 SPI（search 已含 MemoryFilter）
│   ├── store/MemoryFilter.java            // metadata + minScore 过滤
│   ├── store/inmemory/InMemoryMemoryStore.java
│   └── manager/MemoryManager.java         // ✅ 你每天会调用的门面类
│
├── langmem4j-store-qdrant       ← 可选：Qdrant 向量数据库存储适配器
│   └── （collection = namespace，payload 展平，filter 原生下推）
│
├── langmem4j-tools-core         ← 可选：无框架依赖的 LLM 服务层 ✨ 新模块
│   ├── SaveMemoryService        // save / saveWithMetadata / delete
│   └── SearchMemoryService      // get / search + metadataFilter / list
│
├── langmem4j-tools              ← 可选：LangChain4j @Tool 薄封装
│   ├── SaveMemoryTool           // 1:1 委托给 SaveMemoryService
│   └── SearchMemoryTool         // 1:1 委托给 SearchMemoryService
│
└── langmem4j-examples/
    └── langmem4j-example-plain  // 纯 Java SE main() 最小示例
    └── langmem4j-example-springboot  // ⚠️ 占位骨架（pom 而已，还未实现）
```

> **Spring Boot 示例状态**：目前只有一个挂空模块的 pom.xml，还没有可运行的 `@AiService` + AutoConfig 代码。如果你只关心 core 能力，参考 `example-plain` 即可。真实示例将在 0.2.0 补全。

---

## ⚡ Quick Start

### 0. 依赖（Maven）

```xml
<!-- 必选：core 已包含 InMemoryMemoryStore + MemoryManager + MemoryFilter -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- 可选：不绑定 LangChain4j 的工具服务层（Spring AI / 自用都推荐） -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-tools-core</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- 可选：LangChain4j @Tool 注解层。若你用 Spring AI 请直接依赖 tools-core -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-tools</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>

<!-- 可选：Qdrant 后端 -->
<dependency>
    <groupId>com.langmem4j</groupId>
    <artifactId>langmem4j-store-qdrant</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### 1. 3 行代码跑起来（InMemory + Metadata Filter）

```java
MemoryManager manager = MemoryManager.inMemory()
        .withDefaultNamespace("user_alice")
        .build();

manager.add("food",  "Alice loves hot pot",       Map.of("category", "preference", "source", "chat"));
manager.add("friend","Alice goes hiking with Bob", Map.of("category", "social",    "source", "chat"));
manager.add("work",  "Alice works at Acme as SRE", Map.of("category", "work",      "source", "email"));

// 普通子串检索
List<Memory> allHotpot = manager.search("Alice", 10);

// 仅在 category=preference 中检索：无论其他结果多相关，都不会越过 filter
List<Memory> pref = manager.search("Alice", 10,
        MemoryFilter.builder().metadata("category", "preference").build());
// pref 只包含 "Alice loves hot pot"
```

### 2. 5 行代码上 Qdrant（含服务端 metadata filter 下推）

```java
// 1. 向量生成器
EmbeddingGenerator embedder = text ->
        openAiEmbeddingModel.embed(text).content().vector();

// 2. Qdrant store（gRPC 端口 6334；支持 apiKey/useTls/client 显式注入）
MemoryStore qdrant = QdrantMemoryStore.builder()
        .host("localhost")
        .port(6334)
        .embeddingGenerator(embedder)
        .vectorSize(1536)
        .build();
// 需要连接 Qdrant Cloud / API Key？
//   .useTls(true).apiKey("your-api-key")
// 已有 gRPC client 实例？
//   .client(existingQdrantClient)

// 3. MemoryManager 一行接入
MemoryManager manager = MemoryManager.withStore(qdrant)
        .withEmbeddingGenerator(embedder)
        .withDefaultNamespace("user_alice")
        .build();

manager.add("food", "Alice loves 成都火锅", Map.of("category", "preference"));

// MemoryFilter 语义：metadata(AND) + minScore 统一在 store 层内执行
// - InMemoryMemoryStore：先过滤候选再排序（毫秒级，无网络成本）
// - QdrantMemoryStore：目前 V1 以「探针式放大 limit(×10, capped 500) + 客户端逐行 match」实现，
//   保证 MemoryFilter.matchesMetadata() 语义 100% 一致；Qdrant Java Client proto 的 Filter/Match
//   公开表面在 1.x 小版本间存在差异（历史经验），稳定后会补 setFilter 服务端下推
List<Memory> r = manager.search("spicy food", 5,
        MemoryFilter.builder()
                .metadata("category", "preference")
                .minScore(0.6f)
                .build());
```

Qdrant 一行启动：

```bash
docker run -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

### 3. 暴露给大模型（两种方式任选）

#### 方式 A：用 LangChain4j @Tool（零代码接入）

```java
var store = manager.store();
var tools = List.of(
        new SaveMemoryTool(store, "user_alice"),
        new SearchMemoryTool(store, "user_alice")
);
Assistant assistant = AiServices.builder(Assistant.class)
        .chatLanguageModel(model)
        .tools(tools)
        .build();

assistant.chat("我叫 Alice，我爱吃成都火锅，下次提醒我。");
// → LLM 内部调用 save_memory(key="favorite_food", content="Alice loves 成都火锅")
```

#### 方式 B：不用 LangChain4j，直接用 tools-core（Spring AI / 自研桥接）

```java
SaveMemoryService  save   = new SaveMemoryService(manager.store(), "user_alice");
SearchMemoryService search = new SearchMemoryService(manager.store(), "user_alice");

// 这两个对象出/入参和 @Tool 版 100% 一致，你自己接 @JsonSchema + function call 即可
String ok   = save.saveMemoryWithMetadata("food", "Alice likes hot pot", "source=user,category=preference");
String hits = search.searchMemory("food", 5, "category=preference");
```

---

## 🆚 与 mem4j / Mem0 的区别

| | langMem4j | mem4j / Mem0 |
|---|---|---|
| **语言** | 原生 Java 17+，字节级与 Spring / LangChain4j 互通 | Python 为主，Java 侧通常走 REST + 二次序列化 |
| **设计** | 两个 SPI + 一个门面；**core + tools-core 运行时零框架依赖** | 大而全的一体化 SDK，强绑定具体 Vector DB / LLM Provider |
| **入口** | `MemoryManager.add()`，一行生成 embedding + 写入；search 原生 metadata filter | 需要你手动组合 `Memory + Embedding + Store` 三层对象 |
| **工具层** | LangChain4j 独立模块 + 纯 Java `tools-core`，可切 Spring AI / 自研桥接不重复代码 | 仅随官方框架发布 |
| **测试友好** | `InMemoryMemoryStore` 开箱即测，几毫秒跑完 100+ 单测 | 常需起容器或 mock 整个 client 层 |

一句话：**langMem4j 是 Java 开发者的"够用就好"记忆层 — 只做 SPI + 门面 + 少量生产级适配，不把你绑死在任何一家 LLM / DB 上。**

---

## 🧪 构建与测试

```bash
# 全部模块的单测（<15s，Qdrant 集成测试被 @Disabled 跳过）
mvn test

# 跳过测试打包所有模块 / 安装到本地仓库
mvn install -DskipTests

# 跑最小示例（PlainExample 是普通 main()；用 Maven 全限定坐标运行，不依赖 example pom 中声明插件）
mvn -pl langmem4j-examples/langmem4j-example-plain -am package -DskipTests -q
mvn -pl langmem4j-examples/langmem4j-example-plain \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.langmem4j.example.plain.PlainExample

# 启 Qdrant 后，打开集成测试（类上有 @Disabled，去掉它即可）
#   见 langmem4j-store-qdrant/src/test/java/.../QdrantMemoryStoreIntegrationTest.java
#   docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
```

当前测试矩阵（`mvn test`，**102 个单测全绿 ✅**，另有 8 个 `@Disabled` 预留方法）：

| 模块 | 测试方法数 | 核心覆盖点 |
|---|---|---|
| langmem4j-core | **66 passed** | Memory (10) · InMemoryMemoryStore (18) · CosineSearch (7) · MemoryManager (15) · **MemoryFilter** (11，含 null-required、类型严格) · **InMemoryMemoryStore-Filter** (5，排序前剪候选) |
| langmem4j-store-qdrant | 4 passed **+ 4 @Disabled** | QdrantMemoryStoreTest：deterministicId 纯函数 4 条（FNV×32/64/utf16-leak/中文）；另有 4 条集成测试方法 **@Disabled**（见下） |
| langmem4j-tools-core | **14 passed** | SaveMemoryService (6：构造校验 / KV 解析 / 布尔标签 / 空 metadata / 删除 / ns accessor)；SearchMemoryService (8：get 命中/缺失 / substring / 空消息 / limit clamp 4 边界 × 含 >20→20 / **metadata filter 搜索** / list 空+非空 / accessor) |
| langmem4j-tools | **14 passed** | SaveMemoryTool (7，薄封装正确委托 + **namespace 隔离**)；SearchMemoryTool (7，薄封装正确委托) |
| **合计** | **102 passed · 8 skipped · 0 failures** | 全绿 ✅ |

> **集成测试状态**：V1 交付了代码与 `@Disabled` 占位文件 `QdrantMemoryStoreIntegrationTest`（4 个 E2E 方法：①upsert+get round-trip（metadata 还原）②search 余弦排序 ③**search + MemoryFilter 过滤**（category=drink 精确命中）④delete + listKeys 清理验证）。本地跑：
> ```bash
> docker run -d -p 6333:6333 -p 6334:6334 qdrant/qdrant
> # 去掉 QdrantMemoryStoreIntegrationTest.java 顶上的 @Disabled，然后：
> mvn test -pl langmem4j-store-qdrant -am -Dtest=QdrantMemoryStoreIntegrationTest
> ```

---

## 🗺️ 路线图

- [x] Core SPI + InMemory 实现 + MemoryManager 门面
- [x] **MemoryFilter（metadata + minScore）** SPI 级支持 + InMemory/Qdrant 落地
- [x] Qdrant 适配器（V1：探针 limit×10 + 客户端 `MemoryFilter.matchesMetadata()` 精过滤；MemoryFilter SPI 语义 100% 一致；proto 稳定后补 `setFilter` 服务端下推）+ 集成测试占位
- [x] **langmem4j-tools-core（纯 Java）** + **langmem4j-tools（LangChain4j）** 分层
- [x] Plain Java 示例（example-plain）
- [ ] Spring Boot 示例（`example-springboot` 目前还是 pom 骨架）
- [ ] Spring Boot Starter：`@EnableLangMem4j` 自动装配
- [ ] Milvus / PGVector 适配（等首个真实用户需求）
- [ ] Memory Evolve：定期让 LLM 合并 / 淘汰过期记忆（Functional Core 层，无状态）

---

## 📜 License

MIT © langMem4j Contributors
