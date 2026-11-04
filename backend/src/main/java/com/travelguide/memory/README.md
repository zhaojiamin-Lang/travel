# Mongo 记忆与实体消解

本包定义长期偏好、事件摘要、行程快照、实体指代和审计模型。

集合与索引见 `backend/src/main/resources/mongo/memory-indexes.json`：

- `user_profiles`
- `preference_signals`
- `memory_episodes`
- `entity_references`
- `trip_snapshots`
- `memory_audit`

`MemoryStore` 是 Mongo 适配边界。当前仓库提供 `InMemoryMemoryStore` 用于测试；
`MongoMemoryStore` 使用官方同步驱动实现持久化，并会在启用时创建集合索引。

启动本地 MongoDB：

```powershell
docker compose up -d mongo
```

配置连接。URI 为空时不会创建 Mongo Client，也不影响离线运行：

```text
TRAVEL_MONGODB_URI=mongodb://localhost:27017
TRAVEL_MONGODB_DATABASE=travel_memory
```

`EntityResolver` 按以下顺序解析指代：

1. 当前导游景点；
2. 当前行程下一站；
3. 最近行程快照中的酒店、餐厅和 POI；
4. 文档规范名及别名；
5. 多候选时返回歧义，不自动绑定。
