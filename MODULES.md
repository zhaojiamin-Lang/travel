# 模块功能说明（Module Map）

> 回答一个问题：每个模块/文件负责什么、改动该落在哪、谁依赖谁。
> 代码里对应位置：后端每个包有 `package-info.java`，前端每个文件顶部有模块头注释。

## 分层总览

```text
浏览器（Vue 3 + TypeScript + Pinia）
        │  fetch /api/**
        ▼
web      Controller：接参数、校验、返回 DTO
        │
        ▼
service  业务：需求槽位、选点排程、校验预算、账号、导游、路径时间
        │                        │                     │
        ▼                        ▼                     ▼
repo / domain            ai（千问/OpenAI 兼容）   weather（高德天气，定时轮询）
        │
        ▼
H2（默认，文件库）或 PostgreSQL（profile=postgres）   ◄── replan（事件驱动重排，独立旁路）
```

依赖方向只允许 **web → service → repo/domain**，以及 service → ai/weather。
`replan` 可以调用 service 落版本，但 service 不认识 replan。

## 后端模块

### `backend/ai`

Spring AI 模型网关，负责结构化需求抽取、推荐文案和导游回答。不负责预算、时间和路线校验。
关键类：`AiGateway`（未配置 `travel.ai.api-key` 时关闭，调用方回退本地规则）。

### `backend/config`

公共基础配置，包括跨域、密码加密和定时任务开关。
关键类：`WebConfig`（CORS 只放行 5173）、`AuthConfig`（BCrypt）。

### `backend/domain`

数据库领域实体，包括用户、旅行需求、行程版本（JSON 快照、不可变）、导游会话。
重排相关的事件与提案实体放在 `replan` 包内，属于该模块私有数据。

### `backend/repo`

Spring Data 数据访问接口，负责实体查询和保存，不承载业务判断。

### `backend/service`

核心业务服务，负责：

- 行程生成与确定性校验（`PlanningService`）
- 用户账号与历史行程（`AuthService`）
- POI 查询、种子数据与视图转换（`PoiSeedService`、`PoiMapper`）
- 导游内容与景点问答（`GuideService`）
- 高德真实路线时间计算，无 Key 时按直线距离估算（`AmapRouteService`）
- 前端启动配置（`BootstrapService`）

### `backend/web`

HTTP 接口层，负责 REST Controller、请求响应模型、参数验证和统一异常处理。业务逻辑委托给 `service`。
关键类：`PlanningController`、`AuthController`、`GuideController`、`PublicController`、`ApiDtos`、`ApiExceptionHandler`。

### `backend/weather`

独立的定时天气模块：

- 默认每 10 分钟请求高德天气，首次延迟 5 秒
- 保存最近一次成功预报（`AtomicReference` 快照）
- 请求失败时保留旧数据并标记 `STALE`，从未成功过标记 `ERROR`，未配 Key 标记 `UNCONFIGURED`
- 只暴露 `/api/weather/latest` 读快照，不触发外部请求

### `backend/replan`

独立的实时重规划模块：

- Spring Event 接收闭馆和迟到事件（`ReplanEventFacade` 发布，`ReplanEventListener` 消费）
- 保存事件和待确认提案
- `RemainingRouteReplanner` 纯计算：重排剩余时间轴、调用高德重算路段，已过去的部分不动
- 用户确认后才创建新的行程版本（原版本不被静默修改）

### 后端资源

| 路径 | 功能 |
| --- | --- |
| `resources/application.yml` | 默认 H2 文件库、端口 8080、Session 30 天 |
| `resources/application-postgres.yml` | PostgreSQL profile（配合 `docker-compose.yml`） |
| `resources/data/pois.json` | 上海 POI 种子数据，启动时灌库（库非空则跳过） |
| `resources/db/migration` | PostgreSQL 建表脚本（V1 初始表、V2 重排事件） |

## 前端

| 模块 | 功能 |
| --- | --- |
| `src/main.ts` | 应用入口，挂 Pinia + Router，仅 PROD 注册 Service Worker |
| `src/router.ts` | 路由表：`/`、`/plan/:planId`、`/guide/:planId`、`/account` |
| `src/App.vue` | 路由出口与页面过渡 |
| `src/types.ts` | 前后端契约类型，对应后端 `ApiDtos` |
| `src/views/HomeView.vue` | 首页、自然语言规划入口和 Slot 补全 |
| `src/views/PlanView.vue` | 行程时间轴、路线地图、版本、替换和修改 |
| `src/views/GuideView.vue` | 现场导游、定位、讲解、语音和追问 |
| `src/views/AccountView.vue` | 注册登录、游客身份和历史行程 |
| `src/components` | 品牌、导航和路线地图组件（无高德 Key 时退回 SVG 示意图） |
| `src/services` | API 请求、Pinia 状态与离线行程缓存 |
| `src/styles` | 全局设计变量和基础样式 |
| `public` | PWA Manifest、图标和 Service Worker（只缓存 bootstrap 与 `/api/plans/*`） |

## 接口清单（`/api`）

| 方法 | 路径 | 所属模块 | 说明 |
| --- | --- | --- | --- |
| GET | `/bootstrap` | web | 前端启动配置（地图 Key、AI 可用性、POI 概览） |
| GET | `/pois` | web | 景点列表 |
| POST | `/trips/draft` | web | 自然语言起稿，返回草稿与缺失字段 |
| POST | `/trips/{id}/messages` | web | 对话式改需求 |
| PATCH | `/trips/{id}/requirements` | web | 表单式改槽位 |
| POST / GET | `/trips/{id}/plans` | web | 生成行程 / 列出历史版本 |
| GET | `/plans/{planId}` | web | 单个版本详情 |
| GET | `/plans/{planId}/items/{itemId}/alternatives` | web | 某一点的备选景点 |
| POST | `/plans/{planId}/swap` | web | 单点替换（生成新版本） |
| POST | `/plans/{planId}/restore` | web | 回滚到该版本 |
| GET | `/history` | web | 当前账号的历史行程 |
| GET / POST | `/auth/me`、`/auth/register`、`/auth/login`、`/auth/logout` | web | 账号与会话 |
| POST / GET | `/guide/sessions`、`/guide/sessions/{id}` | web | 创建 / 查询导游会话 |
| POST | `/guide/sessions/{id}/locate` | web | 上报定位，匹配最近景点 |
| POST | `/guide/sessions/{id}/select-poi` | web | 手动选景点 |
| POST | `/guide/sessions/{id}/ask` | web | 追问 |
| GET | `/weather/latest` | weather | 天气快照（只读缓存） |
| POST | `/replan/events/closure`、`/replan/events/delay` | replan | 上报闭馆 / 延迟事件 |
| GET / POST | `/replan/proposals/{id}`、`/replan/proposals/{id}/accept` | replan | 查看 / 接受重排提案 |

## 外部依赖与降级

| 依赖 | 配置项 | 没配时的行为 |
| --- | --- | --- |
| 千问 / OpenAI 兼容模型 | `travel.ai.api-key`、`travel.ai.model`、`travel.ai.base-url` | 需求抽取回退关键词规则，导游问答回退模板答案 |
| 高德 Web 服务（路线、天气） | `travel.map.amap-service-key` | 路径时间按直线距离估算；天气状态 `UNCONFIGURED` |
| 高德 JS API（地图渲染） | `travel.map.amap-key` | 前端使用内置 SVG 路线示意图 |

配置优先级：环境变量 > `backend/.env.properties`（已 gitignore）> `application.yml` 默认值。

## 文档与工程

| 模块 | 功能 |
| --- | --- |
| `docs` | 需求、技术选型、实现进度和后续路线 |
| `.githooks/post-commit` | Git 提交后自动推送当前分支 |
| `docker-compose.yml` | 本地 PostgreSQL 环境 |
| `README.md` | 启动、配置和验证说明 |

## 自动推送

仓库已配置 `origin`，执行以下命令后每次本地提交都会尝试自动推送：

```powershell
git config core.hooksPath .githooks
```

临时跳过自动推送：

```powershell
$env:SKIP_AUTO_PUSH="1"
git commit -m "message"
Remove-Item Env:SKIP_AUTO_PUSH
```
