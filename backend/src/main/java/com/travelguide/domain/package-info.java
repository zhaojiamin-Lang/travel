/**
 * 模块：持久化实体（domain）。
 *
 * <p>职责：JPA 实体，只描述「表长什么样」，不放业务规则。</p>
 *
 * <ul>
 *   <li>{@code UserEntity} —— 账号（邮箱 + BCrypt 密码哈希 + 昵称）。</li>
 *   <li>{@code TripRequestEntity} —— 一次旅行需求：城市、天数、人数、预算、必去景点、节奏，
 *       以及从自然语言里抽出的结构化槽位。</li>
 *   <li>{@code TripPlanEntity} —— 行程版本：同一 request 下可存多版，内容以 JSON 快照保存，
 *       因此历史版本不可变、可回滚。</li>
 *   <li>{@code GuideSessionEntity} —— 现场导游会话：当前景点、定位、问答记录。</li>
 *   <li>{@code PoiEntity} —— 景点/餐厅种子数据（坐标、开放时间、游玩时长、票价、标签）。</li>
 * </ul>
 *
 * <p>重排相关的 {@code TravelEventEntity}、{@code ReplanProposalEntity} 放在
 * {@code replan} 包内，属于该模块的私有数据。</p>
 */
package com.travelguide.domain;
