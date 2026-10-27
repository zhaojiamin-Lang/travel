# 漫游 HERE · 旅行导游助手 Web MVP

一个可运行的旅行规划与现场导游 MVP，包含：

- 自然语言需求提取与关键字段补全。
- 上海精选 POI 检索和规则化行程生成。
- 时间、预算、必去景点和开放信息校验。
- 响应式行程时间轴与路线地图。
- 浏览器前台定位、景点讲解和上下文问答。
- Spring Boot + Spring AI 可选模型接入。
- H2 零配置演示模式与 PostgreSQL 正式配置。

## 项目结构

```text
backend/   Spring Boot API
frontend/  Vue 3 + TypeScript
docs/      产品需求与技术方案
```

## 快速启动

### 1. 启动后端

默认使用 H2 文件数据库，无需额外安装数据库：

```powershell
cd backend
mvn spring-boot:run
```

后端地址：`http://localhost:8080`

如需启用 Spring AI，先设置模型 Key：

```powershell
$env:TRAVEL_AI_API_KEY="your-api-key"
$env:TRAVEL_AI_MODEL="gpt-4o-mini"
mvn spring-boot:run
```

未配置 Key 时，需求抽取、排程、预算校验、附近景点匹配和基础导游仍可通过本地规则运行。

### 2. 启动前端

```powershell
cd frontend
npm install
npm run dev
```

页面地址：`http://localhost:5173`

## PostgreSQL 模式

```powershell
docker compose up -d postgres
cd backend
mvn spring-boot:run "-Dspring-boot.run.profiles=postgres"
```

## 高德地图

不配置地图 Key 时，页面会展示可交互的路线示意图。配置 Web 端 Key 后，前端会尝试装载高德 JS API：

```powershell
$env:AMAP_WEB_KEY="your-amap-web-key"
mvn spring-boot:run
```

浏览器定位需要 `localhost` 或 HTTPS 环境。MVP 只在导游页面保持前台时更新位置，不承诺后台自动触发。

## Demo 路径

1. 在首页输入示例旅行需求。
2. 补全日期、预算、人数和节奏。
3. 查看两日行程时间轴、预算和来源。
4. 点击“打开现场导游”。
5. 手动选择景点、使用浏览器定位，或点击“演示定位”。
6. 播放讲解并向 AI 导游追问。

## 构建与检查

```powershell
cd backend
mvn test

cd ..\frontend
npm run build
```

## 后续路线

已实现能力与未实现能力的完整审计见：

[未实现功能与后续技术路线](./docs/未实现功能与后续技术路线.md)
