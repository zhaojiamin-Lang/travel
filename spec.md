# 检查点恢复与执行进度修复规格

## 已确认事实

1. 真实页面中 `GET /api/progress/{planId}` 返回 200，点击“开始当前第一站”后 `PUT /api/progress/{planId}` 返回 403，响应为 `Invalid CORS request`。
2. `WebConfig.addCorsMappings()` 允许 GET、POST、PATCH、DELETE、OPTIONS，但漏掉 PUT。
3. `MultiAgentOrchestrator.executeInternal()` 在 CLARIFY 分支直接返回，不调用 `PlanningAgent`，因此不会创建 Harness Checkpoint；GUIDE/PREFERENCE 分支同样不是可恢复规划任务。
4. `HomeView.handlePlanningResponse()` 和 `PlanView.refinePlan()` 对任何无 Plan 的响应都查询 `/api/orchestration/runs/{runId}`，错误地假设所有 runId 都有 Checkpoint。
5. `PlanView.vue` 已有行内错误横幅，但模板使用 `v-else-if="error || !plan"`，任何操作错误都会替换整个行程页。

## 修改范围

### 1. 允许执行进度 PUT 请求

文件：`backend/src/main/java/com/travelguide/config/WebConfig.java`

- 在 `addCorsMappings()` 的 `allowedMethods` 中加入 `PUT`。
- 不放宽 Origin，不修改其他安全设置。

### 2. 只对真正的规划任务查询或恢复 Checkpoint

文件：`frontend/src/views/HomeView.vue`

- 在 `handlePlanningResponse()` 中，Plan 成功分支之后、查询状态之前判断 `response.agentPath` 是否包含 `PLANNING`。
- 若不是规划任务：删除本机该 runId 记录，清空 pending 状态，展示 `primaryResult.reply`，不请求 Checkpoint 状态，也不进入恢复页。

文件：`frontend/src/views/PlanView.vue`

- 在 `refinePlan()` 中采用相同判断。
- 非规划任务：删除本机 runId 记录，展示后端澄清/回复内容，保留当前行程，不查询状态、不跳转恢复页。

### 3. 操作失败不再替换整个行程页

文件：`frontend/src/views/PlanView.vue`

- 将整页错误分支从 `error || !plan` 改为仅 `!plan`。
- 加载失败且没有可用 Plan 时仍显示“这份行程暂时走丢了”。
- Plan 已加载后的进度、调整、优化等错误只使用现有行内 `feedback-banner--error`。

## 明确不做

- 不修改 Harness 状态机、Checkpoint 数据结构和后端恢复语义。
- 不为 CLARIFY 创建空 Checkpoint。
- 不清理无关 LocalStorage 数据，不覆盖工作区其他改动。

## 验证

1. 后端运行 `PlanningHarnessFlowTest,ExecutionProgressFlowTest`，保持通过。
2. 前端执行生产构建，TypeScript/Vue 编译通过。
3. 真实页面新建或打开行程，点击“开始当前第一站”：PUT 返回 200，状态变为 IN_PROGRESS，当前站与进度文本更新；刷新后进度保留。
4. 用会触发 CLARIFY 的调整文本验证：不再请求不存在 Checkpoint，不出现“规划检查点不存在”，当前行程仍可见。
5. 完整规划任务仍能查询状态；合法 SUSPENDED 任务仍可从检查点恢复。

## 审批

以上修改需用户确认后执行。
