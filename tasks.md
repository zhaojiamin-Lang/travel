# 修复任务

- [x] 1. 在 `WebConfig.addCorsMappings()` 中允许 PUT。
- [x] 2. 在 `HomeView.handlePlanningResponse()` 和 `PlanView.refinePlan()` 中跳过分非规划任务的 Checkpoint 状态查询，并清理无效本地 runId。
- [x] 3. 修改 `PlanView.vue` 整页错误条件，操作错误仅显示行内提示。
- [x] 4. 运行后端相关测试和前端生产构建。
- [x] 5. 在真实浏览器验证首站进度、刷新持久化、CLARIFY 调整和合法检查点恢复。

执行前需获得用户对 `spec.md` 的批准。
