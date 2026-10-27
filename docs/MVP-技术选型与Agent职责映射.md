# 旅行导游助手：技术选型与 Agent 职责映射

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v0.1 |
| 更新日期 | 2026-10-27 |
| 用途 | 说明 MVP 的实际选型，以及后续高阶能力的演进位置 |

## 1. 选型原则

1. MVP 使用最少的基础设施完成真实闭环。
2. Agent 只用于开放式理解和生成，不用于替代确定性业务规则。
3. 只有出现明确扩展需求时才引入 MongoDB、Redis、OpenSearch 和多 Agent。
4. 数据存储按业务职责拆分，不追求“所有数据都放进向量库”。

## 2. MVP 技术栈

| 层/功能 | 选型 | 用途 |
| --- | --- | --- |
| Web 前端 | Vue 3 + TypeScript + Vite | 响应式规划与导游页面 |
| 路由/状态 | Vue Router + Pinia | 页面路由和会话状态 |
| 地图 | 高德 JS API | 地图、POI 标记和当前位置展示 |
| 定位 | 浏览器 Geolocation API | 前台定位 |
| TTS | Web Speech API | 景点讲解朗读，失败时纯文字 |
| 后端 | Java 21 + Spring Boot 3 | REST 服务和业务事务 |
| AI | Spring AI | 需求抽取、推荐文案和导游回答 |
| 数据访问 | Spring Data JPA | 关系型业务数据 |
| 数据库迁移 | Flyway | 表结构版本 |
| 主数据库 | PostgreSQL 16 | 全部 MVP 业务数据 |
| 测试 | JUnit 5 + Spring Boot Test | 核心规则和接口测试 |
| 部署 | Docker Compose | 本地一键启动，后续再调整生产部署 |

MVP 不包含：

- MongoDB。
- Redis。
- OpenSearch、Elasticsearch 或 Milvus。
- MCP Server。
- 多 Agent 框架。
- 消息队列。

## 3. 功能与数据库映射

### 3.1 MVP 映射

| 功能 | 数据存储 | 原因 |
| --- | --- | --- |
| 匿名会话 | PostgreSQL `app_session` | 需要过期时间与会话关联 |
| 用户原始输入 | PostgreSQL `chat_message` | 需要按会话和顺序查询 |
| 规范化旅行需求 | PostgreSQL `trip_request` | 结构化字段与预算可约束 |
| Slot 修正记录 | PostgreSQL 需求 JSON + 消息表 | 保留当前会话中的修正 |
| POI 基础信息 | PostgreSQL `poi` | 数据量小，关系查询清晰 |
| POI 开放日/时间 | PostgreSQL `poi_opening_rule` | 适合规则校验 |
| 景点讲解资料 | PostgreSQL `poi_knowledge` | 内容较小，直接关联 POI |
| 行程 Plan IR | PostgreSQL `trip_plan.plan_json` 的 `JSONB` | 结构复杂但需要事务和版本 |
| 行程校验问题 | PostgreSQL `plan_validation` | 可查询和展示 |
| 导游会话 | PostgreSQL `guide_session` | 状态需要持久化 |
| 地点触发事件 | PostgreSQL `guide_event` | 只存精简事件，不存轨迹 |
| 地图展示坐标 | PostgreSQL `poi.lat/lng` | MVP 点数量少，无需 PostGIS |
| 前端临时 UI 状态 | LocalStorage | 不保存重要业务事实 |
| POI 静态文件 | 项目资源或数据库种子数据 | MVP 不引入对象存储 |

### 3.2 后续技术演进映射

| 后续功能 | 推荐存储/组件 | 不建议的做法 |
| --- | --- | --- |
| 用户长期偏好与画像 | MongoDB `user_profiles` | 把长期模型推断写进行程主表 |
| 对话实体与指代关系 | MongoDB `entity_references` | 只靠向量相似度代替实体 ID |
| 历史事件与记忆片段 | MongoDB `memory_episodes` | 把完整聊天记录无限拼接进 Prompt |
| POI 列表与热门详情缓存 | Redis | 将 Redis 作为唯一业务事实来源 |
| 临时导游会话与限流 | Redis，设置短 TTL | 把精确位置长期保存 |
| 大规模混合检索 | OpenSearch/Elasticsearch | 为 30 个 POI 直接部署集群 |
| MVP/小规模向量检索 | PostgreSQL + pgvector | 先引入独立向量数据库 |
| 精确空间围栏与轨迹查询 | PostgreSQL + PostGIS | 在数据库外维护不可查询坐标 |
| 百万级轨迹分析 | 时序数据库或分析型数据库 | 将连续轨迹塞进业务表 |
| 评测运行与 Trace | PostgreSQL 起步，后续 ClickHouse/对象存储 | 一开始就自建评测平台 |
| Prompt/模型/索引版本 | PostgreSQL 配置表或 Git 元数据 | 仅保存在环境变量和历史聊天中 |

## 4. MVP 组件职责

MVP 不把这些组件称为 Agent：

| 组件 | 类型 | 职责 |
| --- | --- | --- |
| `PlanningController` | HTTP 接口 | 接收规划和导游请求 |
| `PlanningApplicationService` | 应用服务 | 编排需求抽取、查询、规划和校验 |
| `RequirementExtractor` | LLM 适配服务 | 自然语言 -> Slot JSON |
| `SlotValidator` | 规则服务 | 检查字段完整性和取值范围 |
| `PoiCatalogService` | 领域服务 | POI 查询、附近候选和基础过滤 |
| `RuleBasedPlanner` | 确定性算法 | 将候选 POI 分配到每日时间窗口 |
| `PlanValidator` | 规则服务 | 校验预算、时间、开放日和必去点 |
| `LlmNarrator` | LLM 适配服务 | 把已校验计划转成用户可读说明 |
| `GuideApplicationService` | 应用服务 | 定位匹配、选点和当前 POI 问答编排 |
| `GuideContentService` | 领域服务 | 组装讲解资料、来源和上下文 |

这种结构先保证业务正确，后续才把开放式任务拆成 Agent。

## 5. 未来多 Agent 职责

### 5.1 Agent 总表

| Agent | 负责的需求 | 可调用工具 | 不负责 |
| --- | --- | --- | --- |
| `SupervisorAgent` | 识别规划/导游/记忆/追问意图，决定下一步和终止 | 路由、Trace、确认工具 | 计算预算和时间 |
| `RequirementAgent` | Slot Filling、缺失字段追问、需求冲突澄清 | 需求 Schema 校验、用户记忆召回 | 决定景点路线 |
| `DiscoveryAgent` | 景点、美食、住宿、天气和多源资料检索 | POI 检索、天气、地图、RAG | 直接把未校验结果当最终行程 |
| `ItineraryAgent` | 生成候选行程、解释取舍、组织用户可读方案 | 交通矩阵、排程求解、预算工具 | 独立绕过规则修改硬约束 |
| `CriticAgent` | 自动评审、缺陷报告、触发一次修复 | 时间校验、预算校验、体验评测、LLM Judge | 替代确定性 Validator |
| `GuideAgent` | 到店讲解、长短内容选择、当前景点追问 | 当前 POI、附近 POI、知识检索、TTS | 未经确认修改整份行程 |
| `MemoryAgent` | 偏好写入、历史实体召回、代词消解、记忆纠错 | Mongo 记忆查询、实体解析 | 推断用户敏感属性 |

`RouteSolver`、`BudgetValidator`、`TimeValidator` 和 `LocationMatcher` 是工具或确定性服务，不单独包装成 Agent。

### 5.2 需求到 Agent 流程

| 用户需求 | Agent 链路 | 需要的确定性能力 |
| --- | --- | --- |
| “我想去上海玩两天” | Supervisor -> Requirement -> Discovery -> Itinerary -> Critic | Schema 校验、POI 查询、排程、规则校验 |
| “我还想去外滩” | Supervisor -> Requirement -> Itinerary -> Critic | 必去约束更新、版本生成 |
| “预算再低 1000” | Supervisor -> Requirement -> Itinerary -> Critic | 预算重新求解、不可行冲突解释 |
| “上次那家酒店” | Supervisor -> Memory -> Requirement | 实体 ID 解析、候选歧义确认 |
| “换类似风格的行程” | Supervisor -> Memory -> Discovery -> Itinerary | 偏好召回、相似主题检索、排程 |
| “走到外滩了吗” | Supervisor -> Guide | 定位匹配、候选置信度 |
| “讲长一点” | Guide | 当前 POI 上下文、讲解长度控制 |
| “附近还能吃什么” | Guide -> Discovery | 当前坐标、饮食偏好、证据检索 |
| “为什么不去这个景点” | Itinerary 或 Critic | 计划取舍原因和约束证据 |

### 5.3 推荐 Agent 拓扑

初期不建议让所有 Agent 自由对话。推荐由 Supervisor 控制固定状态图：

```text
START
  -> Supervisor
  -> Requirement
  -> Discovery
  -> Itinerary
  -> Critic
  -> [通过] FinalResponse
  -> [不通过] Itinerary（最多修复一次）

Guide 独立分支：
Guide
  -> LocationContext
  -> GuideAgent
  -> Discovery（仅用户追问附近内容时）
```

后续接入 MCP 后，工具仍然通过权限、Schema、超时和用户确认约束，不能由 Agent 任意调用。

## 6. 从 MVP 演进到多 Agent 的顺序

### 阶段 1：单流程 + 单模型

- 当前 MVP。
- 普通应用服务编排。
- PostgreSQL 单一数据库。
- 规则排程和校验。

### 阶段 2：引入 RequirementAgent

- 将自然语言抽取和追问从固定接口中拆出。
- 增加状态记录和多轮 Slot 修正。
- 仍不引入完整多 Agent 图。

### 阶段 3：引入 DiscoveryAgent 与 RAG

- 先做关键词+向量混合检索。
- 建立 POI/餐饮资料和证据引用。
- 再评测后决定是否需要多路 Query 改写和 Rerank。

### 阶段 4：引入 Supervisor、Itinerary 和 Critic

- 用状态图管理规划流程。
- 引入受控循环和终止条件。
- 保持预算、时间和开放规则为确定性工具。

### 阶段 5：引入 GuideAgent 和 MemoryAgent

- 接入现场导游上下文。
- 上线 MongoDB 长期记忆。
- 实现实体引用和代词消歧。

### 阶段 6：Harness、MCP、缓存与高可用

- 持久化 Checkpoint。
- MCP 工具权限和版本管理。
- Redis 热点缓存。
- 多级降级和自动评测流水线。

该顺序保证每一步都能独立验证，不需要一次性完成全部高阶能力。

## 7. 推荐最终开发环境

MVP：

```text
Vue 3
Spring Boot 3 / Spring AI
PostgreSQL 16
高德 JS API / Web Service API
Docker Compose
```

后续按需增加：

```text
MongoDB     -> 长期记忆
Redis       -> 热点缓存和短期会话
OpenSearch  -> 大规模混合检索
pgvector    -> 小规模向量检索
MCP Server  -> 标准化工具接入
```

不建议在 MVP 同时引入以上所有组件，否则调试时间和部署成本会超过产品价值。
