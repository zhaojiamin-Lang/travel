# RAG 核心模块

本包实现知识 Markdown 加载、结构感知分片、Query Rewrite、实体/BM25/向量混合召回、
RRF 融合和可插拔 Rerank。它不改动现有 `GuideService` 或 Spring 配置，适合先离线评测，
再接入导游问答链路。

## 当前组件

| 组件 | 说明 |
| --- | --- |
| `KnowledgeLoader` | 读取 `knowledge/**/*.md` 和 front matter |
| `KnowledgeChunker` | H2 感知的父子分片，事实文档按原子规则切分 |
| `QueryRewriteService` | 当前景点、下一站、别名和意图规则改写 |
| `HybridRetriever` | 实体、BM25、Dense 三路召回与 RRF |
| `Reranker` | Cross-Encoder 适配接口 |
| `HeuristicReranker` | 无外部模型时的本地重排兜底 |
| `RagEngine` | 加载、检索和重排的统一门面 |

## 本地冒烟

```java
RagEngine engine = RagEngine.fromDirectory(
        Path.of("..", "knowledge"),
        new HashingEmbeddingProvider(512),
        new HeuristicReranker()
);

QueryContext context = QueryContext.atPoi(
        "上海",
        "shanghai-museum",
        "上海博物馆人民广场馆",
        Instant.parse("2026-10-29T04:00:00Z")
);

RagSearchResult result = engine.search("这里几点关门？", context);
```

`HashingEmbeddingProvider` 只用于测试和无模型环境。生产环境应实现
`EmbeddingProvider` 接入 BGE-M3 或当前供应商向量模型，并实现 `Reranker`
接入 Cross-Encoder。更换模型后必须重建全量向量，不能混用不同模型产生的向量。

当前已提供 `OpenAiCompatibleEmbeddingProvider`，可直接使用现有千问
OpenAI Compatible 配置：

```text
TRAVEL_AI_API_KEY
TRAVEL_AI_EMBEDDING_MODEL=text-embedding-v3
TRAVEL_AI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode
```

索引构建会批量请求 Embedding，默认每批最多 10 条，避免逐 chunk 发起网络请求。
Key 为空时不会创建真实 Embedding Bean，测试和离线环境仍可使用
`HashingEmbeddingProvider`。

## 召回约定

1. QueryPlan 保留原问题、独立问题、实体、事实类型、As-Of 时间和未解决指代。
2. 默认每路取实体 20、BM25 30、Dense 30，RRF 融合后取 20，Rerank 后取 8。
3. 召回结果保留每路排名，方便诊断 Query Rewrite、BM25 和 Dense 的贡献。
4. `DEMO`、`CITED`、`VERIFIED` 默认都进入开发索引，可通过
   `RetrievalOptions.allowedConfidence` 在生产环境收紧范围。
5. `知识 chunk` 只做检索；`资料说明` 不进入用户事实召回。

## 导游问答集成

`GuideService.ask()` 已接入 `GroundedAnswerService`：

1. 使用当前景点构造 QueryContext；
2. 检索并重排 Top 证据；
3. 将证据编号为 `[E1]`、`[E2]`；
4. 模型有合法引用时返回生成答案；
5. 模型不可用或没有引用时，回退到证据原文；
6. API 返回 `claims`、`citations`、`uncertainties`、`conflicts` 和检索元数据。

`AskResponse.claims[].support` 中：

- `CITED`：该句附有合法证据编号；
- `ADVICE`：建议类表达，不要求事实引用；
- `UNSUPPORTED`：没有关联证据。

线上引用校验保证编号存在，但离线的 faithfulness/语义支持判断仍需结合
Ragas 或人工标注评测，不能把“有引用编号”直接等同于“事实一定正确”。
