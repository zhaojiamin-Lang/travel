/**
 * 模块：事件驱动的行程重排（replan）。
 *
 * <p>职责：游玩途中发生「景点闭馆」「上一站延迟」，只重算剩余路线并给出一份提案，
 * 用户接受后才落地成新的不可变版本，保证原计划不被静默篡改。</p>
 *
 * <ul>
 *   <li>{@code ReplanController} —— {@code POST /api/replan/events/closure}、
 *       {@code /events/delay}、{@code GET /proposals/{id}}、
 *       {@code POST /proposals/{id}/accept}。</li>
 *   <li>{@code ReplanEventFacade} —— 对外门面：记录事件、发布 Spring Event、
 *       查提案、接受提案（接受时才调用 {@code PlanningService} 落新版本）。</li>
 *   <li>{@code ReplanEventListener} —— 监听事件，调重排器算出提案并持久化。</li>
 *   <li>{@code RemainingRouteReplanner} —— 纯计算：给定当前计划和闭馆/延迟，
 *       重排剩余项的时间轴、用高德重算相邻路段、输出变更清单。</li>
 *   <li>{@code TravelEventEntity} / {@code ReplanProposalEntity} 及对应 Repository
 *       —— 事件与提案的持久化，提案含原版 id、新计划 JSON、变更说明。</li>
 *   <li>{@code ReplanModels} —— 请求/视图对象。</li>
 * </ul>
 *
 * <p>这样拆分让「计算」和「落库」解耦：计算可以单独跑测试，事件失败也不影响已存在的行程。</p>
 */
package com.travelguide.replan;
