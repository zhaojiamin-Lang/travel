# 模块功能说明

## 后端

| 模块 | 功能 |
| --- | --- |
| `backend/ai` | Spring AI 模型网关，负责结构化需求抽取和导游回答 |
| `backend/config` | CORS、密码加密、定时任务等公共配置 |
| `backend/domain` | 用户、旅行需求、行程版本、导游会话、天气事件等实体 |
| `backend/repo` | 数据库访问接口 |
| `backend/service` | 行程生成、校验、账号、历史、导游、POI 和高德路线 |
| `backend/web` | REST Controller、请求响应对象和异常处理 |
| `backend/weather` | 每 10 分钟拉取高德天气，保存最新成功结果 |
| `backend/replan` | Spring Event 处理闭馆/迟到，重算剩余路线并生成提案 |
| `backend/resources/data` | 上海 POI 种子数据 |
| `backend/resources/db/migration` | PostgreSQL Flyway 数据库迁移 |

## 前端

| 模块 | 功能 |
| --- | --- |
| `frontend/views/HomeView.vue` | 首页、自然语言规划入口和 Slot 补全 |
| `frontend/views/PlanView.vue` | 行程时间轴、路线地图、版本、替换和修改 |
| `frontend/views/GuideView.vue` | 现场导游、定位、讲解、语音和追问 |
| `frontend/views/AccountView.vue` | 注册登录、游客身份和历史行程 |
| `frontend/components` | 品牌、导航和路线地图组件 |
| `frontend/services` | API 请求、Pinia 状态与离线行程缓存 |
| `frontend/styles` | 全局设计变量和基础样式 |
| `frontend/public` | PWA Manifest、图标和 Service Worker |

## 文档与工程

| 模块 | 功能 |
| --- | --- |
| `docs` | 需求、技术选型、实现进度和后续路线 |
| `.githooks/post-commit` | Git 提交后自动推送当前分支 |
| `docker-compose.yml` | 本地 PostgreSQL 环境 |
| `README.md` | 启动、配置和验证说明 |

## 自动推送

仓库配置 `origin` 并执行以下命令后，每次本地提交都会尝试自动推送：

```powershell
git config core.hooksPath .githooks
```

临时跳过自动推送：

```powershell
$env:SKIP_AUTO_PUSH="1"
git commit -m "message"
Remove-Item Env:SKIP_AUTO_PUSH
```
