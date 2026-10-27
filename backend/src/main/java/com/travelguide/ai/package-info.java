/**
 * 模块：AI 能力网关（ai）。
 *
 * <p>职责：把「调用哪个模型、怎么拼提示词、失败怎么办」收敛到一个出口，
 * 上层业务只调用 {@code AiGateway#complete(instructions, userText)}，不直接接触 Spring AI。</p>
 *
 * <p>关键类：</p>
 * <ul>
 *   <li>{@code AiGateway} —— 基于 Spring AI {@code OpenAiChatModel} 的对话补全；
 *       构造时按 {@code travel.ai.api-key} 是否为空决定启用状态。</li>
 * </ul>
 *
 * <p>使用方：{@code service.PlanningService}（结构化需求抽取、自然语言改需求）、
 * {@code service.GuideService}（现场导游问答）。</p>
 *
 * <p>降级策略：未配置 Key 或调用抛错时 {@code isEnabled()} 返回 false，
 * 调用方退回本地规则（关键词解析、模板化讲解），所以没有 Key 也能跑通演示流程。</p>
 *
 * <p>边界：本包不写数据库、不做行程校验；模型产出的是文本，
 * 解释成结构化字段的工作留在 service 层。</p>
 */
package com.travelguide.ai;
