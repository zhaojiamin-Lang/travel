/**
 * 模块：Supervisor 多 Agent 编排（orchestration）。
 *
 * <p>统一入口为 {@code MultiAgentController}，一次请求最多执行一次路由，
 * 然后按「PreferenceAgent -> PlanningAgent / GuideAgent」的固定顺序分发。
 * Agent 之间的状态通过不可变 {@code AgentInvocation} 传递，执行顺序和终止条件集中在
 * {@code MultiAgentOrchestrator}，不允许 Agent 自由互调。</p>
 *
 * <p>关键边界：</p>
 * <ul>
 *   <li>Router 只判断意图，不生成行程或导游内容。</li>
 *   <li>PlanningAgent 复用现有 PlanningService，预算和时间仍由确定性代码校验。</li>
 *   <li>GuideAgent 复用现有 GuideService，保留定位和证据引用链路。</li>
 *   <li>PreferenceAgent 只保存用户明确表达的长期偏好，不作敏感属性推断。</li>
 * </ul>
 *
 * <p>降级策略：路由模型不可用时使用规则路由；路由置信度不足时只追问一次；
 * Mongo 未配置时偏好使用进程内存储。该包不修改原有业务服务和接口。</p>
 */
package com.travelguide.orchestration;
