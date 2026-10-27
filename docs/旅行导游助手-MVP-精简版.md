# 旅行导游助手：精简版 MVP 需求说明

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v0.2 |
| 文档状态 | 开发基线 |
| 更新日期 | 2026-10-27 |
| 客户端 | 响应式 Web |
| 首发城市 | 上海 |
| 后端 | Spring Boot 3 + Spring AI |
| 前端 | Vue 3 + TypeScript |
| 主数据库 | PostgreSQL |

## 1. 本次 MVP 的边界

MVP 只验证两个核心场景：

1. 用户用自然语言提出旅行需求，系统补全关键信息后生成一份可执行的结构化行程。
2. 用户在 Web 导游页面手动选择景点，或在前台授权定位后识别附近景点，并获取讲解和追问。

MVP 的目标是完成真实、可演示、可测试的产品闭环，不是提前实现完整简历中的高阶工程能力。

### 1.1 暂不实现

以下内容明确从本次 MVP 中移除，后续由你逐步实现：

- Supervisor-Worker 多 Agent 协作。
- Agentic RAG、多路召回、Rerank 和迭代 Query 改写。
- 千万级私有知识库。
- MCP 工具协议。
- 自研 Agent Harness、上下文压缩和断点恢复。
- MongoDB 跨会话长期记忆和语义指代。
- Redis 热点缓存和三级降级。
- 多阶段自动 Critic 评测流水线和 CI 评测平台。
- 原生 Android/iOS 客户端。
- 后台持续定位和自动离线导航。
- 自动订票、支付和真实票务。

这些能力的后续落位见：

[MVP 技术选型与 Agent 职责映射](./MVP-技术选型与Agent职责映射.md)

## 2. MVP 演示故事

用户输入：

> 下周去上海玩两天，预算 3000 元，我想去外滩和上海博物馆，不吃辣，不想走太累。

系统完成：

1. 提取上海、两天、3000 元、外滩、上海博物馆、不吃辣和轻松节奏。
2. 缺失具体日期时，展示一个日期选择器。
3. 在预置的上海景点库中查找候选地点。
4. 按照开放时间、交通时间和用户节奏生成两天行程。
5. 使用确定性规则检查必去景点、预算和时间冲突。
6. 展示带时间轴、餐饮、交通、费用和来源的行程。
7. 用户打开导游页面并选择外滩。
8. 页面展示外滩讲解并支持浏览器语音朗读。
9. 用户可继续询问“附近还能看什么”。

## 3. P0 功能需求

### 3.1 需求输入与 Slot Filling

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| MVP-REQ-001 | 提供自然语言输入框 | 用户可以输入一段完整旅行需求 |
| MVP-REQ-002 | 抽取旅行要素 | 输出结构化字段并通过服务端 Schema 校验 |
| MVP-REQ-003 | 展示已识别需求 | 用户可以看到系统理解的城市、天数、日期、预算和偏好 |
| MVP-REQ-004 | 补充缺失字段 | 只追问会阻断规划的关键字段 |
| MVP-REQ-005 | 支持表单修正 | 自然语言识别错误可以通过表单直接修改 |
| MVP-REQ-006 | 支持必去和排除地点 | 用户明确要求的地点进入规划约束 |

MVP 支持识别的字段：

- 城市：仅支持上海。
- 天数：1–3 天。
- 日期：可以为空，缺失时通过表单补充。
- 人数：1–2 人。
- 预算：总预算或每日预算。
- 必去/排除景点。
- 饮食偏好：至少支持“不吃辣”。
- 节奏：轻松或普通。
- 主题偏好：可选，如人文、城市景观。

### 3.2 POI 查询与推荐

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| MVP-POI-001 | 提供预置上海 POI 列表 | 至少包含 30 个可规划地点 |
| MVP-POI-002 | 展示景点基础信息 | 包含名称、地址、建议时长、开放信息和来源 |
| MVP-POI-003 | 支持关键词和标签筛选 | 可按区域、类型和主题过滤 |
| MVP-POI-004 | 支持必去和排除约束 | 必去地点不能静默丢失 |
| MVP-POI-005 | 支持餐饮候选 | 至少提供一日三餐建议来源 |

MVP 不建设“知识库平台”，只把经过人工整理的景点和餐饮资料作为种子数据导入数据库。查询可以使用 SQL 关键词、标签和区域过滤，不需要 Embedding 和向量数据库。

### 3.3 行程生成

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| MVP-PLAN-001 | 生成结构化行程 | 返回通过 JSON Schema 校验的 Plan DTO |
| MVP-PLAN-002 | 支持 1–3 天行程 | 每天包含时间段、景点、交通、餐饮和休息 |
| MVP-PLAN-003 | 支持必去景点 | 必去景点优先进入计划 |
| MVP-PLAN-004 | 支持轻松节奏 | 轻松模式限制每日主要景点数量 |
| MVP-PLAN-005 | 计算基础预算 | 展示景点门票、餐饮和交通的估算总额 |
| MVP-PLAN-006 | 校验开放日 | 不在闭馆日安排景点 |
| MVP-PLAN-007 | 校验时间冲突 | 交通时间不足时不得输出“校验通过” |
| MVP-PLAN-008 | 校验预算上限 | 超预算必须提示，不得静默通过 |
| MVP-PLAN-009 | 展示证据来源 | 开放、票价和介绍信息至少关联数据库中的来源 |
| MVP-PLAN-010 | 明确未知信息 | 无可靠开放或价格数据时显示“待确认” |

MVP 使用“规则 + 简化贪心排程”，暂不引入 OR-Tools。算法步骤：

1. 过滤关闭或不适合的 POI。
2. 将必去点放入候选集。
3. 按区域和偏好增加候选点。
4. 用直线距离/预置交通时间估算排序。
5. 在每日时间窗口内分配景点和用餐。
6. 运行规则校验器。
7. 如有错误，调整一次；仍失败则展示冲突。

### 3.4 行程页面

页面至少展示：

- 出行日期、天数、人数和预算。
- 每天的行程时间轴。
- 景点名称、停留时间、费用和来源。
- 景点之间的交通方式与预计时间。
- 用餐安排。
- 预算汇总。
- 必去景点和超预算/待确认提示。
- “进入导游”入口。

### 3.5 Web 导游与定位

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| MVP-GUIDE-001 | 支持高德地图展示 | 地图可显示行程 POI 或当前位置 |
| MVP-GUIDE-002 | 用户主动授权浏览器定位 | 未点击开启前不请求定位 |
| MVP-GUIDE-003 | 识别附近计划内景点 | 返回候选、距离和定位精度 |
| MVP-GUIDE-004 | 支持手动选择景点 | 不依赖定位功能 |
| MVP-GUIDE-005 | 支持 90 秒景点讲解 | 讲解基于数据库中的整理资料 |
| MVP-GUIDE-006 | 支持语音朗读 | 优先使用浏览器 TTS，失败时保留文字 |
| MVP-GUIDE-007 | 支持当前景点追问 | 回答必须关联当前 POI 和当前行程 |
| MVP-GUIDE-008 | 支持“不是这里” | 用户可以取消当前候选并重新选择 |
| MVP-GUIDE-009 | 防止重复提示 | 当前会话对同一景点只自动提示一次 |

MVP 的定位约束：

- 仅在 HTTPS 或 localhost 下工作。
- 仅在导游页面保持前台时更新位置。
- 页面进入后台或关闭后不保证自动讲解。
- 浏览器拒绝定位时仍可完成整个手动导游流程。

### 3.6 当前会话上下文

MVP 保存当前规划会话和当前导游会话：

- 当前旅行需求。
- 当前行程版本。
- 当前选中的景点。
- 最近一轮对话。

这不是“长期记忆系统”：

- 不实现跨会话回忆。
- 不实现“上次那家酒店”。
- 不实现用户长期画像。
- 不实现记忆置信度和冲突治理。

### 3.7 基础异常处理

| 编号 | 需求 | 验收标准 |
| --- | --- | --- |
| MVP-ERR-001 | LLM 超时显示明确错误 | 用户知道可以重试，不会看到无限加载 |
| MVP-ERR-002 | Schema 校验失败可重试一次 | 不把未校验的 LLM 输出直接落库 |
| MVP-ERR-003 | 定位失败显示手动入口 | 导游流程不被阻断 |
| MVP-ERR-004 | 无来源动态信息不写成确定事实 | 显示“暂无可靠信息” |

MVP 不实现 Redis 缓存、三级降级和自动熔断平台；只保留 Spring 请求超时、异常日志和友好错误页面。

## 4. 技术选型

### 4.1 前端

| 功能 | 技术 |
| --- | --- |
| Web 页面 | Vue 3 + TypeScript |
| 构建 | Vite |
| 页面路由 | Vue Router |
| 状态管理 | Pinia |
| 组件与样式 | 轻量 UI 组件库，具体视觉实现可后续确定 |
| 地图 | 高德 JS API |
| 定位 | 浏览器 `navigator.geolocation` |
| 语音 | 浏览器 Web Speech API |
| 本地临时状态 | LocalStorage，仅保存非敏感 UI 状态 |

不使用 Android、Flutter、React Native 或原生定位 SDK。

### 4.2 后端

| 功能 | 技术 |
| --- | --- |
| 运行时 | Java 21 |
| Web 框架 | Spring Boot 3 |
| AI 调用 | Spring AI |
| 参数校验 | Jakarta Validation |
| 数据库访问 | Spring Data JPA |
| 数据迁移 | Flyway |
| API | REST + JSON |
| 测试 | JUnit 5 |
| 日志 | SLF4J / Logback |

### 4.3 主数据库

MVP 只使用一个业务数据库：**PostgreSQL 16**。

选择 PostgreSQL 的原因：

- 行程、版本、会话和 POI 之间存在明确关联，适合关系型建模。
- Plan IR 可以使用 `JSONB` 保存，同时保留事务和约束。
- 后续需要向量检索时可以增加 pgvector，不必更换主业务库。
- 后续位置规模增大时可以增加 PostGIS。
- 相比一开始同时部署 MongoDB、Redis 和 OpenSearch，更符合小型 MVP 的运维成本。

MVP 不需要 MongoDB、Redis、OpenSearch、Kafka 或向量数据库。

### 4.4 总体架构

```text
Vue 3 Web
   |
   | REST / JSON
   v
Spring Boot 模块化单体
   |
   +-- PlanningApplicationService
   +-- RequirementExtractor
   +-- PoiCatalogService
   +-- RuleBasedPlanner
   +-- PlanValidator
   +-- GuideApplicationService
   +-- LlmGateway
   |
   +-- PostgreSQL
   +-- 高德 Web Service API
```

MVP 不实现多 Agent，也不接 MCP。`PlanningApplicationService` 是普通应用服务，不是伪装成 Agent 的调度器。

## 5. 数据库表设计

| 表 | 用途 | 关键字段 |
| --- | --- | --- |
| `app_session` | 匿名浏览器会话 | `id`, `session_token`, `created_at`, `expires_at` |
| `trip_request` | 规范化旅行需求 | `id`, `session_id`, `city`, `days`, `budget`, `preferences_json` |
| `poi` | 景点/餐饮/交通点 | `id`, `name`, `type`, `lat`, `lng`, `area`, `duration_minutes` |
| `poi_opening_rule` | 开放规则 | `poi_id`, `weekday`, `open_time`, `close_time`, `closed` |
| `poi_knowledge` | 整理后的景点资料 | `poi_id`, `content`, `source_url`, `source_name`, `updated_at` |
| `trip_plan` | 行程版本 | `id`, `trip_request_id`, `version`, `status`, `plan_json` |
| `plan_validation` | 校验结果 | `plan_id`, `passed`, `issues_json` |
| `chat_message` | 规划/导游消息 | `session_id`, `role`, `content`, `created_at` |
| `guide_session` | 当前导游状态 | `id`, `session_id`, `plan_id`, `current_poi_id`, `status` |
| `guide_event` | 到达和讲解事件 | `guide_session_id`, `poi_id`, `event_type`, `created_at` |

`guide_event` 不保存连续 GPS 轨迹，只保存发生匹配或用户确认时的事件。

## 6. API 范围

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `POST` | `/api/sessions` | 创建匿名会话 |
| `POST` | `/api/trips/draft` | 提交自然语言需求并获取 Slot |
| `PATCH` | `/api/trips/{requestId}/requirements` | 修正需求字段 |
| `POST` | `/api/trips/{requestId}/plans` | 生成行程 |
| `GET` | `/api/plans/{planId}` | 获取行程 |
| `GET` | `/api/pois?area=&type=` | 查询 POI |
| `POST` | `/api/guide/sessions` | 创建导游会话 |
| `POST` | `/api/guide/sessions/{id}/locate` | 提交坐标并返回附近 POI |
| `POST` | `/api/guide/sessions/{id}/select-poi` | 手动选择 POI |
| `POST` | `/api/guide/sessions/{id}/ask` | 围绕当前 POI 追问 |
| `GET` | `/api/guide/sessions/{id}` | 获取当前导游状态 |

## 7. LLM 使用边界

MVP 只有三个 LLM 调用点：

1. 将自然语言转换成需求 Slot JSON。
2. 根据已经校验通过的行程生成推荐文案。
3. 根据当前 POI 资料和会话生成导游回答。

以下逻辑禁止交给 LLM 单独决定：

- 预算是否越界。
- 景点是否闭馆。
- 时间是否重叠。
- 必去景点是否存在。
- POI 坐标。
- 定位是否命中。

如果未配置模型 Key，允许使用预置表单和静态景点资料完成部分演示，但不能声称已完成完整的自然语言 AI 流程。

## 8. MVP 数据规模

- 1 个城市：上海。
- 30 个左右可规划 POI。
- 每个 POI 1–3 条人工整理资料。
- 5–10 个餐饮或休息点。
- 1–3 天行程。
- 单人或两人。
- 中文。

MVP 不宣传“千万级知识库”，也不需要真实 RAG。

## 9. 最小测试与验收

本阶段只建立基础测试，不建设自动 Critic 评测流水线。

### 9.1 必须覆盖的自动化测试

- 正常的 2 天上海规划。
- 缺失日期。
- 必去景点不在候选范围。
- 预算超限。
- 闭馆时间安排。
- 故意构造的时间重叠。
- 轻松模式的每日景点数量限制。
- 当前景点问答。
- 无当前位置时的手动选点。
- 定位权限拒绝。

### 9.2 端到端验收

1. 输入示例需求后，系统正确识别主要旅行要素。
2. 缺失字段可以通过表单补充。
3. 系统生成 1–3 天结构化行程。
4. 必去景点不丢失，预算和时间冲突可被检测。
5. 行程页面完整展示景点、餐饮、交通、预算和来源。
6. 用户可以在导游页面手动选择景点。
7. 浏览器授权且页面前台时，可以识别附近计划内景点。
8. 景点讲解支持文字和浏览器语音。
9. 当前景点追问不会脱离所选景点上下文。
10. 模型、定位或 Schema 校验失败时，页面有明确可操作提示。

## 10. 开发顺序

### Step 1：数据与契约

- 创建 Spring Boot 和 Vue 3 工程。
- 定义 `TripRequest`、`TripPlan`、`Poi`、`PlanIssue` DTO。
- 建立 PostgreSQL 和 Flyway。
- 导入 30 个上海 POI。

### Step 2：规划闭环

- 先用结构化表单完成需求输入。
- 实现 POI 查询和规则排程。
- 实现基础 Validator。
- 完成行程展示。
- 再接入 LLM 自然语言抽取和推荐文案。

### Step 3：导游闭环

- 高德地图和浏览器定位。
- 手动选择景点。
- 展示整理资料和浏览器语音。
- 接入当前景点问答。

### Step 4：完善与演示

- 增加失败提示。
- 完成自动化测试。
- 整理 Demo 数据和启动说明。
- 记录真实体验后再决定下一阶段做多 Agent、RAG、MCP 或记忆。

## 11. 默认决策

如果没有新的产品约束，按以下默认值开发：

- 城市：上海。
- 前端：Vue 3 + TypeScript。
- 后端：Java 21 + Spring Boot 3 + Spring AI。
- 数据库：PostgreSQL 16。
- 地图：高德 JS API + Web Service API。
- 登录：匿名会话，不做正式账号。
- 规划：1–3 天，规则排程。
- 导游：前台定位 + 手动选择兜底。
- 高阶 Agent、RAG、MCP、Mongo、Redis 和自动评测全部后置。
