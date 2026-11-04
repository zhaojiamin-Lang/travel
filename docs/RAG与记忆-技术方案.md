# 旅行导游助手：RAG 与记忆技术方案

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.0 |
| 日期 | 2026-10-28 |
| 适用代码 | Spring Boot 3.3.5 / Java 17 / Vue 3 |
| 目标 | 在不破坏现有确定性规划与校验的前提下，增加可引用、可评测、可恢复的 RAG 与长期记忆 |

## 1. 结论先行

### 1.1 推荐架构

```text
用户问题 + 当前行程/景点 + As-Of 时间
        │
        ├─ Redis：精确缓存、短期会话、限流
        ├─ MongoDB：长期偏好、事件、实体指代
        ▼
QueryPlan：原文 / 独立问题 / 实体 / 时间 / 城市 / 事实类型
        │
        ├─ PostgreSQL：行程、约束、事实快照、引用账本
        ├─ 动态工具：天气、实时开放、票价、路线
        ▼
OpenSearch 混合召回
  BM25 + Dense ANN + 实体/元数据过滤
        ▼
RRF 融合 → 去重 → Cross-Encoder 重排 → 时效/权限过滤
        ▼
Evidence Pack（Top 8 证据 + 结构化行进上下文）
        ▼
受证据约束的导游生成
        ▼
答案 + Claim/引用映射 + 冲突/不确定性 + Trace
```

### 1.2 首版选型

| 能力 | 选型 | 说明 |
| --- | --- | --- |
| 业务真相 | PostgreSQL 16 | 行程、需求、版本、Validator 结果仍以这里为准 |
| 混合检索 | OpenSearch 2.x 单节点起步 | 同一索引同时提供中文 BM25、向量 ANN 和字段过滤 |
| 中文分析 | 固定版本的 IK 分词插件 | 标题、别名、正文采用不同字段权重 |
| 向量检索 | OpenSearch `knn_vector` HNSW | 维度跟 Embedding 模型固定，不混用不同模型向量 |
| 文档解析 | Apache Tika + PDFBox + Jsoup | 覆盖 PDF、DOCX、HTML；保留页码、标题和表格结构 |
| Embedding | BGE-M3 自托管，或当前供应商的文本向量模型 | 必须先做中文旅行语料对比测试，然后锁定模型和维度 |
| Rerank | BGE-reranker-v2-m3 自托管，或供应商 Cross-Encoder Rerank | 输入 Top 30，输出 Top 8；不默认用生成模型重排 |
| 长期记忆 | MongoDB 7.x | 可审计偏好、事件摘要、行程快照引用、实体指代 |
| 缓存 | Redis 7.x + 本地 Caffeine | Redis 不是任何业务事实的唯一来源 |
| 后台任务 | Spring `@Async` + PostgreSQL Checkpoint | 首版不引入 Temporal；RAG 查询本身保持无状态 |
| 可观测 | Micrometer + OpenTelemetry | 每次请求记录检索、重排、工具、模型和缓存版本 |

选择 OpenSearch 而不是只选 PostgreSQL + pgvector，原因是本项目同时需要中文词法召回、向量召回、字段过滤和按索引版本回滚。一个专用检索索引比“在业务库中拼装多种检索能力”更容易保证行为一致和可评测。

如果部署约束只允许 PostgreSQL，可把 `HybridRetriever` 实现替换为 PostgreSQL + pgvector + `zhparser/pg_bigm`，但召回质量必须重新跑同一套数据集，不能假设两者等价。

当前后端固定使用 Spring AI `1.0.0-M5`。RAG 的检索和证据组装不应依赖具体 VectorStore 实现；Embedding、Rerank 和 Chat 都放在独立 Gateway 后面。升级 Spring AI 或更换供应商时，只做适配层兼容测试和全量向量重建，不让业务层绑定供应商 SDK。

### 1.3 最重要的边界

1. RAG 用于历史、文化、玩法、交通说明等相对静态知识；实时开放状态、天气、余票、票价必须走动态事实工具。
2. MongoDB 中的“偏好记忆”不能作为世界事实证据。只有行程数据和 Evidence Ledger 可以作为事实引用。
3. 用户显式约束优先级高于模型推断；Validator 仍是预算、时间和开放规则的最终裁判。
4. 证据引用必须指向不可变文档版本和稳定定位，不能只保存一个会变化的网页 URL。
5. 没有足够证据时宁可明确“资料未覆盖”，也不要补写看似合理但没有来源的事实。

## 2. 与现有代码的接入位置

现有 [GuideService.java](../backend/src/main/java/com/travelguide/service/GuideService.java) 把当前 POI 模板直接拼成文本交给 `AiGateway`，[AskResponse](../backend/src/main/java/com/travelguide/web/ApiDtos.java) 目前只有回答和推荐问题，没有 Claim、证据、冲突和检索元数据。

建议保留 `GuideService` 负责会话、定位、选择 POI，把“问题理解 → 检索 → 事实仲裁 → 生成 → 引用校验”抽到新的 `GuideRagService`：

```java
public AskResponse ask(String sessionId, String question) {
    GuideContext guide = loadGuideContext(sessionId);
    RagAnswer answer = guideRagService.answer(guide, question);
    return answer.toApiResponse();
}
```

建议后端新增以下包：

```text
com.travelguide.rag
  ingestion/   SourceConnector, DocumentParser, Cleaner, ChunkPlanner, IndexPublisher
  query/       QueryPlanner, EntityResolver, QueryRewritePolicy
  retrieval/   HybridRetriever, RrfFuser, ChunkDeduplicator, Reranker, EvidenceAssembler
  fact/        DynamicFactTool, FactArbitrator, FactSnapshotService
  generation/  GroundedAnswerService, ClaimExtractor, CitationValidator
  model/       EmbeddingGateway, RerankGateway

com.travelguide.memory
  MemoryService, MemoryExtractor, MemoryRecallService, EntityReferenceService

com.travelguide.evaluation
  EvaluationRunner, MetricsCalculator, GoldenDatasetLoader
```

不要让 `PlanningService` 依赖 RAG。RAG 与 Memory 只向规划和导游提供上下文，预算、时间和开放时间判定仍由 `PlanningService` / Validator 执行。

## 3. 知识采集、清洗、索引和引用

### 3.1 数据源分层

| 类型 | 示例 | 采集策略 | 更新策略 |
| --- | --- | --- | --- |
| 官方静态资料 | 景区官网历史、场馆介绍 | 授权抓取或人工整理 | 周级或月级 |
| 官方动态事实 | 临时闭馆、开放时间、票价、预约 | API 优先，官方页面解析兜底 | 分钟到小时级 |
| 地图与交通 | 高德 POI、路线矩阵 | 只存必要字段并遵守服务条款 | 路线按需，POI 周级 |
| 天气 | 高德天气或其他合规 API | 工具实时调用 | Redis 10–15 分钟 |
| 授权内容 | OTA、文旅资料、合作方文档 | 按授权范围入库 | 按上游版本 |
| 用户材料 | 用户上传的行程、截图、PDF | 明确授权，隔离租户 | 用户删除时同步清除 |

每个来源都需要 `source_id`、许可/授权范围、抓取频率、联系人或接口负责人。UGC 只能作为弱证据，不能覆盖官方事实。

### 3.2 清洗流水线

```text
采集
→ 内容类型识别
→ 安全/许可检查
→ 正文提取
→ 去导航/广告/重复推荐位
→ 标题层级恢复
→ 表格结构化
→ 日期、货币、开放时间和别名规范化
→ 近重复检测
→ 质量门禁
→ 分片
→ Embedding
→ 索引发布
```

关键规则：

- HTML 保留 `h1–h4`、正文、表格和列表，删除导航、页脚、推荐卡片、Cookie 提示。
- PDF 使用 Tika + PDFBox，保留页码；扫描件先做 OCR，并把 OCR 置信度写入元数据。
- 表格不能拍平成自然语言，保存表头、行号、单位和适用日期。
- “9:00–17:00”“17:00 闭馆”“周一闭馆”统一为结构化开放规则，并保留原文证据。
- 货币统一到分，但保留原币种和原文；日期区分适用日、发布日、抓取日和有效期。
- 先用规范化 URL 和内容 SHA-256 去重；解析失败的文档进入隔离区，不进入线上索引。

### 3.3 切割方式和首版参数

采用“结构优先 + 父子分片 + 原子事实”的方法，不用固定字符盲切，也不默认使用 LLM 语义切割。

| 内容类型 | 检索子块建议 | 父级上下文 | 重叠 | 规则 |
| --- | --- | --- | --- | --- |
| 景点介绍/历史叙事 | 350–500 中文字符，目标 400 | 600–1000 字符章节摘要 | 50–80 字符 | 以自然段和句子边界结束，不跨 H2/H3 |
| FAQ | 120–280 字符 | 整组 FAQ，最多 1000 字符 | 0 | 一问一答一个块 |
| 票价/开放时间/预约 | 一条规则一个块，80–240 字符 | 对应事实组 | 0 | 不把不同日期、票种、人群条件切散后混拼 |
| 表格 | 每 4–8 行一个块，硬上限 500 字符 | 表头 + 表标题 | 0 | 每块重复表头，并保留行号 |
| 交通/路线说明 | 一个起终点关系一个块 | 该交通章节 | 0 | 出发地、目的地、方式、时长、日期不能拆开 |
| 长篇政策文档 | 一个条款或一个完整但书一个块 | 章节标题 + 条款摘要 | 0–50 字符 | “除非/仅限/节假日除外”等条件必须和主句在一起 |

这组参数是首版起点，必须在固定评测集上做 300、400、500 字符及不同重叠的对比。分片目标不是越长越好，而是“单独看能回答一个问题，放回父级后不丢条件”。

索引中的检索文本建议由三部分组成：

```text
景点名 / 别名 / 城市 / 区域
章节路径
正文分片
```

引用展示仍使用原文分片，不把 Embedding 增强文本当作引用原文。

### 3.4 数据模型和引用账本

PostgreSQL 新增：

```text
rag_document
  doc_id, source_id, canonical_url, title, language, published_at,
  fetched_at, valid_from, valid_to, content_sha256, version, status

rag_chunk
  chunk_id, doc_id, parent_chunk_id, section_path, locator_json,
  text, normalized_text, content_sha256, token_count, chunk_version

evidence_record
  evidence_id, doc_id, chunk_id, quote, quote_sha256,
  source_tier, observed_at, valid_from, valid_to,
  index_alias, retrieval_run_id
```

`locator_json` 至少包含页码、标题路径、表格行号或原文起止偏移。引用由以下字段组成：

```json
{
  "evidenceId": "ev_01J...",
  "docId": "doc_shanghai_museum",
  "docVersion": 3,
  "title": "上海博物馆参观须知",
  "url": "https://...",
  "section": "开放时间 > 节假日安排",
  "locator": {"page": 2, "tableRow": 4},
  "quote": "...",
  "observedAt": "2026-10-28T10:00:00+08:00",
  "validFrom": "2026-10-01",
  "validTo": "2026-10-31",
  "sourceTier": "OFFICIAL"
}
```

文档更新采用“新版本 + 蓝绿索引别名切换”：

```text
rag_chunks_20261028_001（构建中）
              │ 质量门禁通过
              ▼
rag_chunks_current ──别名切换──> 新索引
```

回答中保存当次索引别名和证据快照 ID，保证后续引用的内容可追溯，即使线上文档已经更新。

### 3.5 OpenSearch 索引设计

同一个 chunk 索引至少包含：

```text
chunk_id/keyword
doc_id/keyword
version/integer
title/text(ik_max_word) + keyword 子字段
aliases/text(ik_smart) + keyword 子字段
content/text(ik_max_word)
parent_summary/text(ik_smart)
city/keyword
area/keyword
entity_ids/keyword[]
fact_type/keyword[]
source_tier/keyword
language/keyword
valid_from/date
valid_to/date
observed_at/date
access_scope/keyword
embedding/knn_vector
content_hash/keyword
```

BM25 字段权重首版可从以下值开始，再通过评测调整：

```text
title^4.0, aliases^3.0, parent_summary^1.5, content^1.0
```

向量模型必须记录 `model_id`、维度、归一化方式、构建时间和索引别名。更换 Embedding 模型时全量重建，禁止新旧向量混写。

## 4. 混合召回、重排和 Query 改写

### 4.1 QueryPlan

每次请求先生成可追踪的 `QueryPlan`，不直接拿原问题调用向量库：

```json
{
  "originalQuery": "那里现在还能进去吗？",
  "standaloneQuery": "外滩现在是否开放进入？",
  "intent": "LIVE_STATUS",
  "entities": [
    {"mention": "那里", "entityId": "poi:bund", "confidence": 0.96}
  ],
  "city": "上海",
  "asOf": "2026-10-28T19:30:00+08:00",
  "tripDate": "2026-10-29",
  "factTypes": ["OPEN_STATUS"],
  "needsTools": ["get_live_attraction_status"],
  "rewriteSource": "MEMORY_COREFERENCE"
}
```

问题中的“当前”与旅行日期要分开：

- “现在开放吗”查询当前实时状态。
- “明天下午能进吗”查询对应日期的开放规则，必要时调用未来日期的票务/公告工具。
- “以前是不是周一闭馆”查询历史资料，不能用当前实时状态覆盖历史表述。

### 4.2 Query 改写策略

按成本从低到高执行，不为所有请求调用模型：

1. 规则归一化：繁简、标点、全半角、别名、明显的时间和城市表达。
2. 上下文消解：把“这里、它、那家酒店、上次那个景点”替换为稳定实体 ID。
3. 条件式 LLM 改写：仅在指代不明、多实体、复合问题或规则无结果时调用，输出严格 JSON Schema。
4. 问题分解：仅对“比较历史票价和现在票价”等复合问题拆成子问题，最多 3 个。
5. 多 Query 变体：仅在 Recall@10 评测证明有收益时启用，默认生成 2 个以内，并缓存查询计划。

不建议默认使用 HyDE。HyDE 会引入一次生成调用，也可能把错误事实带进检索；只有固定评测显示召回收益显著大于时延和幻觉风险时才考虑。

### 4.3 混合召回包含哪些通道

一次检索并行运行以下三路：

| 通道 | 候选数 | 作用 | 过滤条件 |
| --- | --- | --- | --- |
| 实体/结构化召回 | 20–50 | 精确匹配 POI、别名、区域、城市、事实类型和关系 | `entity_id`、`city`、`area`、`fact_type`、权限 |
| BM25 词法召回 | 50 | 精确词、专名、开放时间、票种、政策条款 | `language`、`valid_at`、`access_scope` |
| Dense ANN 向量召回 | 50 | 同义表达、自然语言意图和语义相似段落 | 与上面相同的业务过滤 |

“附近还能看什么”这类问题增加结构化关系过滤：当前 POI → 同区域/邻近 POI → 路线候选。不要把地理关系只交给向量相似度。

### 4.4 融合、去重和重排

首版使用 Reciprocal Rank Fusion：

```text
RRFScore(d) = Σ(channelWeight / (60 + rank_channel(d)))
```

默认实体通道权重 1.2，BM25 和 Dense 各 1.0。权重必须通过评测调参，不能凭感觉长期固定。

融合后：

1. 按 `chunk_id`、`content_hash` 和高度重叠的原文区间去重。
2. 同一父章节最多保留 2 个相邻子块，避免 Top 10 被同一篇长文占满。
3. 取融合 Top 30 进入 Cross-Encoder Rerank。
4. Rerank 后再做有效期、权限和来源等级过滤，避免“先过滤掉唯一正确证据”。
5. 最终返回 Top 8；同一来源最多 3 块，但唯一高权威来源可以例外。

Rerank 输入建议包含：

```text
standaloneQuery + 已解析实体 + 章节路径 + chunk 原文
```

上下文上限首版控制在 2500–3500 中文字符。超出时优先删除低分正文，保留来源、时间和关键数字，不要简单截断句子。

### 4.5 证据不足和降级

最终证据包需要带有检索置信度：

```text
ACCEPTED：有至少一条高相关且时效有效的证据
NEED_TOOL：问题属于动态事实，应调用工具
AMBIGUOUS：有多个实体/来源候选，需要用户确认
INSUFFICIENT：证据低于校准阈值，转为明确“不确定”回答
```

阈值为自适应：静态知识、动态事实、精确数字和政策条款使用不同阈值。低置信度时不要用模型常识补齐。

以下情况不得静默降级为无引用回答：

- OpenSearch 超时；
- Rerank 超时；
- 工具返回 `STALE` 或 `CONFLICT`；
- 当前索引版本与评测基线不一致。

回答需要向用户显示“实时/缓存/过期/冲突”的状态。

## 5. 动态事实工具和时效仲裁

### 5.1 工具接口

所有动态工具返回统一 `FactRecord`，而不是任意自然语言：

```json
{
  "factId": "fact_open_bund_20261028",
  "entityId": "poi:bund",
  "factType": "OPEN_STATUS",
  "value": {"status": "OPEN"},
  "conditions": {"tripDate": "2026-10-28", "timezone": "Asia/Shanghai"},
  "sourceId": "amap-live",
  "sourceTier": "T2_AUTHORIZED_LIVE",
  "observedAt": "2026-10-28T19:25:00+08:00",
  "validFrom": "2026-10-28T19:00:00+08:00",
  "validTo": "2026-10-28T20:00:00+08:00",
  "confidence": 0.95,
  "status": "VALID"
}
```

首版工具：

| 工具 | 主要输入 | 返回事实 |
| --- | --- | --- |
| `get_live_attraction_status` | `poi_id`, `as_of` | 开放/闭馆/待确认 |
| `get_weather_forecast` | 坐标、日期 | 天气、温度、降雨 |
| `get_ticket_quote` | `poi_id`, 日期、人数、票种 | 票价、余票、来源 |
| `get_route_matrix` | 起终点、交通方式、时间窗 | 时长、距离、拥堵 |
| `get_booking_status` | 用户授权后的订单 ID | 已订/未订/取消 |

工具不能直接返回“可以放心去”的结论，只返回结构化事实和来源。最后由仲裁器与生成器组装。

### 5.2 时效仲裁顺序

世界事实按以下顺序比较：

1. `as_of`/旅行日期是否落在有效期内。
2. 是否覆盖更具体的条件，例如节假日、临时公告、特定票种。
3. 来源权威性：官方实时 > 官方静态 > 授权实时数据 > 授权第三方 > 公共编辑资料 > UGC。
4. 同一来源的新旧程度。
5. 是否有多源相互印证。
6. 仍然无法区分时，标记 `CONFLICT`，保留冲突双方，不硬选一个。

用户个人约束单独仲裁：

```text
当前消息中的显式修改 > 当前行程需求 > 已确认的长期偏好 > 模型推断偏好
```

例如用户这次说“预算 2500”，不能因为历史记忆写着“预算充足”而提高预算；模型推断“喜欢高处观景”也不能覆盖用户明确说“恐高”。

### 5.3 冲突处理

同一个 `(entity_id, fact_type, applicable_date)` 有多个有效事实时：

- 一个来源更权威且发布时间更新，可按规则选出 primary，但保留其他证据。
- 一个事实是节假日特例，另一个是常规时间，二者不是冲突，应按适用日期选择特例。
- 两个官方来源有效期相同且数值不同，标记 `CONFLICT`，回答中说清差异和更新时间。
- 动态事实过期且无法刷新时标记 `STALE`，不能把旧状态写成当前状态。
- 影响行程硬约束时产生 `PlanIssue`，由 Validator 决定是否阻断。

### 5.4 Redis 缓存

动态事实缓存只降低外部调用，不替用户承担时效判断：

| 数据 | 建议 TTL | 过期降级 |
| --- | --- | --- |
| 天气 | 10–15 分钟 | 可短时显示 `STALE`，必须标明 |
| 实时开放/闭馆 | 5–15 分钟 | 超过最大陈旧时间则“待确认” |
| 路线时间 | 2–6 小时 | 可显示估算 |
| 票价/预约规则 | 6–24 小时 | 出发日期远时可用，临近时缩短 TTL |
| 静态景点讲解 | 1–7 天 | 配合文档版本和索引版本 |
| 用户短期会话 | 30–120 分钟 | 从持久化会话重建 |

缓存 Key 至少包含：

```text
entity_id + fact_type + trip_date + as_of_bucket +
source_id + schema_version + data_version
```

## 6. MongoDB 长期记忆、实体映射和指代消解

### 6.1 存储职责

| 数据 | 存储 | 原因 |
| --- | --- | --- |
| 当前行程与版本 | PostgreSQL | 事务、唯一版本、Validator 结果 |
| Evidence Ledger | PostgreSQL | 需要审计、版本固定和外键约束 |
| 长期偏好与推断信号 | MongoDB | 字段变化多、按用户聚合方便 |
| 会话指代和候选实体 | MongoDB + Redis | 需要临时 TTL，也需要审计最近映射 |
| 完整业务计划 | PostgreSQL，Mongo 只存引用/摘要 | 避免两份行程真相漂移 |
| 缓存和无幂等临时状态 | Redis | 可丢、可重建 |

### 6.2 集合设计

```text
user_profiles
  userId, locale, timezone, explicitPreferences[],
  consentVersion, status, createdAt, updatedAt

preference_signals
  signalId, userId, type, key, normalizedValue, polarity,
  confidence, evidenceEpisodeIds[], sourceMentions[],
  firstObservedAt, lastObservedAt, expiresAt, status

memory_episodes
  episodeId, userId, tripId, summary, eventAt,
  entityIds[], sourceMessageIds[], visibility, status

entity_references
  referenceId, userId, sessionId, turnId, surface,
  normalizedSurface, resolvedEntityId, candidateEntityIds[],
  confidence, resolutionMethod, contextHash, createdAt, expiresAt

trip_snapshots
  snapshotId, userId, tripId, planId, planVersion,
  summary, entityIds[], corePlanRef, createdAt

memory_audit
  auditId, userId, memoryId, action, actor,
  beforeHash, afterHash, reason, createdAt
```

建议索引：

```javascript
db.user_profiles.createIndex({ userId: 1 }, { unique: true })
db.preference_signals.createIndex({
  userId: 1, type: 1, key: 1, status: 1
})
db.preference_signals.createIndex(
  { expiresAt: 1 },
  { expireAfterSeconds: 0 }
)
db.entity_references.createIndex({
  userId: 1, sessionId: 1, createdAt: -1
})
db.entity_references.createIndex(
  { expiresAt: 1 },
  { expireAfterSeconds: 0 }
)
db.memory_episodes.createIndex({
  userId: 1, eventAt: -1
})
```

首版不依赖 Atlas 专有的 `$vectorSearch`。长期记忆先按用户、类型、状态和时间做结构化候选过滤，再把最多 50 条候选在应用层重新打分；如果未来迁移 Atlas，可增加向量索引，但不能改变“记忆不是事实证据”的边界。

### 6.3 什么时候写入记忆

满足以下任一条件才进入长期记忆：

- 用户明确说“记住”“以后都按这个来”；
- 同一偏好在不同会话中重复出现；
- 用户明确确认系统总结的偏好；
- 偏好会跨行程长期有效，例如“我不吃辣”“出行节奏偏慢”。

当前游记、临时预算、这次行程的必去景点保留在行程需求或短期会话中，不自动升级为长期偏好。模型推断的敏感属性默认不写。

写入分数可由以下因素决定：

```text
memoryScore =
  explicitSignal × confidence × sourceReliability × recencyFactor
```

用户显式纠正覆盖旧记忆，但采用状态迁移和审计记录，不物理覆盖历史：

```text
ACTIVE → SUPERSEDED
```

### 6.4 实体规范化和映射

稳定实体 ID 使用带命名空间的值：

```text
poi:bund
poi:shanghai-museum
hotel:amap:B001...
restaurant:amap:B002...
plan:plan_abc123
trip:req_abc123
```

解析顺序：

1. 精确匹配当前页面或当前行程中的实体。
2. 精确匹配规范名、别名、历史名称。
3. 使用最近一次 `trip_snapshots` 和 `entity_references` 做用户级候选排序。
4. 地区、类型、时间、距离等业务过滤。
5. 仍无精确候选时，才做向量候选召回。
6. 低于自动接受阈值或存在多个相近候选时，要求用户消歧。

建议首版阈值：

- `confidence >= 0.85`：可作为唯一解析结果。
- `0.60 <= confidence < 0.85`：询问“你指的是 A 还是 B？”
- `confidence < 0.60`：保持未解析，不能根据相似度直接绑定实体。

阈值必须用真实指代样本校准。

### 6.5 指代消解输出

解析器返回独立结构，不能把解析结果直接混进用户原文：

```json
{
  "originalText": "上次那家酒店附近有什么？",
  "standaloneText": "外滩英迪格酒店附近有什么景点？",
  "resolvedEntities": [
    {
      "mention": "那家酒店",
      "entityId": "hotel:amap:B001",
      "displayName": "示例酒店",
      "confidence": 0.91,
      "source": "TRIP_SNAPSHOT"
    }
  ],
  "unresolvedMentions": [],
  "rewriteConfidence": 0.89
}
```

典型规则：

- “这里/这个景点”优先解析为 `GuideSession.currentPoiId`。
- “下一站”解析为当前行程中的后继 POI，而不是全局最相似 POI。
- “上次那家酒店”先找最近行程快照里的酒店实体，再结合类型和城市过滤。
- 多个候选时使用 `AMBIGUOUS`，不要把最高分候选悄悄写回长期记忆。

## 7. 受证据约束的生成和引用

### 7.1 生成输入

生成器只接收：

```text
用户问题
结构化当前行程事实
动态工具事实及其状态
Top 8 RAG 证据
已解析实体和未解决歧义
回答风格/长度要求
```

Prompt 约束：

- 每个可核验的事实句附带 `[E1]` 形式的证据编号；
- 数字、日期、开放时间、票价、路线和方向必须来自证据或工具；
- 证据冲突时明确列出，不自行调和；
- 没有证据时明确说“当前资料未覆盖”；
- 用户偏好只影响语气和选择，不自动变成世界事实。

### 7.2 返回结构

建议把 `AskResponse` 扩展为：

```json
{
  "answer": "外滩是全天开放的公共滨江空间。[E1]",
  "claims": [
    {
      "claimId": "c1",
      "text": "外滩是全天开放的公共滨江空间。",
      "citationIds": ["E1"],
      "support": "SUPPORTED"
    }
  ],
  "citations": [
    {
      "evidenceId": "E1",
      "title": "外滩游览说明",
      "url": "https://...",
      "section": "开放时间",
      "observedAt": "2026-10-28T10:00:00+08:00",
      "freshness": "VALID"
    }
  ],
  "uncertainties": [],
  "conflicts": [],
  "retrieval": {
    "queryPlanId": "qp_...",
    "indexAlias": "rag_chunks_current_v7",
    "channels": ["ENTITY", "BM25", "DENSE"],
    "rerankUsed": true,
    "latencyMs": 642
  },
  "suggestions": ["附近还能看什么？"]
}
```

线上第一层校验只做确定性检查：证据 ID 是否存在、引用是否属于证据包、数字是否出现在证据或结构化事实中。更昂贵的 NLI/LLM 支持判断主要放在离线评测和高风险数字事实的二次检查中，避免每个请求增加过多时延。

## 8. 可观测性和自动化评测

### 8.1 Trace 字段

每次请求生成一个 `trace_id`，记录：

```text
request_id, user_id_hash, session_id, plan_id
original_query, query_plan, rewrite_used
resolved_entity_ids, unresolved_mentions
memory_ids, memory_scores
embedding_model, reranker_model, chat_model
retrieval_channel_ranks, fused_ranks, rerank_scores
index_alias, document_versions
tool_names, tool_status, fact_ids
evidence_ids, citation_ids
cache_hit_by_stage, llm_calls_by_stage, tokens, cost
latency_by_stage, error_type, degradation_reason
```

敏感原文、精确坐标和用户材料需要脱敏后保存，并设置保留期。

### 8.2 固定数据集

首版建议建立 200 条 RAG 问题，按场景分层：

- 历史/文化介绍；
- 开放时间、票价、预约；
- 交通与附近推荐；
- “这里/上次那家”等指代；
- 时间、来源冲突；
- 无答案/证据不足；
- 包含动态事实的复合问题。

拆分为开发集、测试集和 Holdout 集。每条至少记录：

```json
{
  "caseId": "rag_0001",
  "question": "...",
  "context": {"planId": "...", "currentPoiId": "...", "asOf": "..."},
  "goldEvidenceIds": ["..."],
  "goldEntities": ["poi:..."],
  "expectedTool": ["get_live_attraction_status"],
  "expectedConflict": false,
  "referenceAnswer": "..."
}
```

预算、合规和时延测试也要使用固定版本、固定行程集，避免每次运行数据变化导致不可比较。

### 8.3 指标定义与建议门禁

以下是首版验收门槛，是目标值，不代表当前项目已有结果。上线后以固定数据集实测值为准。

| 指标 | 测试方法 | 计算方式 | 建议门禁 |
| --- | --- | --- | --- |
| RAG Recall@10 | 每条问题人工标注正确证据文档/证据块，检查前 10 条是否召回 | 至少命中一条 gold evidence 的问题数 ÷ 总问题数；多证据问题另报 `EvidenceRecall@10 = 命中的 gold evidence 数 ÷ gold evidence 总数` | `RAG Recall@10 >= 0.90`，相对基线不得下降超过 2 个百分点 |
| Unsupported Claim Rate | 把导游回答拆成单个可核验事实，由人工双标注；模型只做辅助并记录分歧 | 无证据支持事实数 ÷ 全部事实数 | `<= 0.05`，高风险的开放时间/票价/数字单列且目标 `<= 0.02` |
| 时间冲突检出率 | 构造 30 个有冲突和 30 个无冲突的行程/事实对，与人工标签比较 | `TP ÷ (TP + FN)`，同时报 FP、FPR 和 precision | Recall `>= 0.90`，FPR `<= 0.05` |
| 预算越界违规召回率 | 构造实际花费超过预算和未超过预算的行程，检查 Validator 是否报告 `BUDGET_EXCEEDED`；同时测试边界和舍入 | `TP ÷ (TP + FN)` | Recall `>= 0.98`，precision `>= 0.95` |
| 整体合规率 | 对固定行程集逐项检查硬约束和体验规则；硬约束由确定性断言，体验规则由人工双评，LLM Judge 只做辅助 | 合规行程数 ÷ 测评行程数；同时分别报硬约束合规率和体验合规率 | 整体 `>= 0.95`，硬约束单独要求 `>= 0.99` |
| Redis 降低 LLM 调用 | 同一批用户请求分别关闭/开启缓存与优化功能，逐请求统计 `AiGateway` 实际 Chat 调用次数 | `(基线调用数 - 优化后调用数) ÷ 基线调用数`；另报 embedding/rerank 调用和 Token | 首版 `>= 0.30`，质量指标不退化超过 2 个百分点 |
| 秒级恢复 | 在 QueryPlan、检索、重排、工具调用后分别注入 `kill -9`，重启后记录恢复时间 | P50、P95、最大值；开始时间为进程终止，结束时间为任务重新达到 `RUNNING` 或完成 | P95 `<= 30s`，最大 `<= 60s`；同时报启动耗时和任务恢复耗时 |
| 响应时延 | 相同设备、相同模型、固定数据版本和缓存策略，预热后重复请求至少 100 次 | P50、P95、P99、最大值；分别报端到端、检索、重排、工具和生成耗时 | 完整回答 P95 `<= 5s`，RAG 检索+重排 P95 `<= 700ms` |

另外建议增加：

- `CitationPrecision`：引用证据真正支持对应 Claim 的比例；
- `AbstentionAccuracy`：无答案时正确拒答的比例；
- `CoreferenceAccuracy`：指代解析到正确实体 ID 的比例；
- `ValidatorFalsePositiveRate`：不越界却被报告违规的比例；
- `ToolStaleRate`：工具事实过期但仍被使用/展示的比例。

所有指标都要保存运行清单：

```text
git_commit + dataset_version + embedding_model + reranker_model +
chat_model + prompt_version + index_alias + threshold_set +
随机种子 + 设备/资源规格 + 时间窗口
```

没有这份运行清单的数字不能和下一次评测横向比较。

### 8.4 Redis 降低 LLM 调用的正确方式

可缓存的层次：

1. Embedding 结果缓存：同一规范化文本和模型版本。
2. QueryPlan 缓存：同一问题 + 已解析实体 + 会话摘要版本。
3. 检索结果缓存：同一 QueryPlan + 索引别名 + 业务过滤条件。
4. 静态导游回答缓存：同一问题、实体、模型/Prompt/索引版本，且不涉及动态事实。
5. 动态事实回答只缓存到事实 TTL 以内，经过 TTL 后不得复用旧答案。

缓存键必须包含：

```text
normalized_query + resolved_entity_ids + locale +
as_of_bucket + prompt_version + chat_model +
embedding_model + reranker_model + index_alias +
fact_snapshot_version + access_scope
```

用户个性化问题不能只按问题文本缓存，否则不同用户会看到彼此的偏好或行程信息。用户维度缓存必须带授权范围并做隔离。

### 8.5 秒级恢复设计

RAG 查询本身无状态，真正的恢复点是“编排任务”和正在进行的异步生成。PostgreSQL 增加：

```text
guide_task
  task_id, trace_id, stage, status, attempt,
  input_ref, checkpoint_json, lease_until,
  idempotency_key, created_at, updated_at
```

阶段建议：

```text
QUERY_PLANNED → MEMORY_RECALLED → RETRIEVED → RERANKED
→ FACTS_RESOLVED → GENERATED → CITATION_VALIDATED → COMPLETED
```

进程恢复时扫描过期租约的 `RUNNING` 任务，从最后成功阶段继续。工具调用使用业务幂等键，已经产生的 Claim/Citation 快照不能因索引更新而改变。Redis 只保存可重建的短期状态，PostgreSQL Checkpoint 才是恢复依据。

如果导游接口保持同步请求，就不应承诺“客户端连接断开后原请求自动恢复”；这种情况下应把长任务改为异步任务并提供任务查询接口。恢复指标只针对真正持久化的任务定义，避免用服务启动时间冒充任务恢复时间。

## 9. 分阶段落地顺序

### 阶段 A：证据基础

- 建立 `rag_document`、`rag_chunk`、`evidence_record` 和 Evidence API；
- 给现有 POI 资料建立稳定文档 ID、来源版本和字段级 locator；
- 建立固定评测集和 Trace 字段，先记录当前无 RAG 基线；
- 保持现有规划/导游接口可用。

### 阶段 B：RAG 检索闭环

- 接入 OpenSearch、中文分词和 Embedding；
- 完成结构清洗、父子分片、版本化索引；
- 实现 QueryPlan、三路召回、RRF、Rerank 和证据包；
- `GuideService.ask()` 接入引用生成；
- 首轮目标：Recall@10、CitationPrecision、Unsupported Claim Rate 可见。

### 阶段 C：动态事实与时效

- 定义 `FactRecord`、工具 Registry 和来源等级；
- 接入天气、开放状态、票价、路线；
- 实现 As-Of、有效期、冲突仲裁和 `STALE/CONFLICT` 状态；
- 将动态事实快照传给规划 Validator；
- 完成时间冲突和预算违规评测。

### 阶段 D：MongoDB 记忆

- 建立五类集合和索引；
- 只写入显式、稳定、跨行程有价值的信息；
- 实现实体别名、候选打分和指代歧义确认；
- Memory recall 只提供上下文，不直接证明世界事实；
- 增加用户查看、删除和纠错接口。

### 阶段 E：缓存、恢复和 CI 门禁

- Caffeine + Redis 分层缓存，先做 Embedding/QueryPlan/检索缓存；
- 建立 Postgres Checkpoint 和任务恢复；
- 用固定样本回归验证指标；
- 将硬约束、Recall@10、无依据引用率、恢复率和 P95 作为发布门禁；
- 只有指标稳定通过后，才考虑多 Agent、MCP 和更大规模检索。

## 10. 首版需要明确的验收标准

1. 每条导游事实能追溯到 `doc_id + doc_version + locator + quote_sha256`。
2. 当前开放状态、天气、票价等动态问题不会仅依赖向量召回。
3. “这里/上次那家”只有高置信度时自动解析，否则出现明确消歧问题。
4. 冲突事实不会被静默选一个写入长期记忆。
5. 关闭 OpenSearch、Rerank 或 Mongo 时有明确降级状态，不伪装成实时、完整或已核验结果。
6. 固定数据集上能重复计算所有指标，且保存模型、索引、Prompt 和数据版本。
7. 关闭优化与开启优化使用同一请求集和相同硬件测 LLM 调用降低量，不能拿不同问题集做对比。
8. 恢复测试从真实 `kill -9` 或等效故障注入开始，记录 P50、P95 和最大恢复时间。
