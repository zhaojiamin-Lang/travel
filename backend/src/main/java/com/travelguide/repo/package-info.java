/**
 * 模块：数据访问（repo）。
 *
 * <p>职责：Spring Data JPA 接口，只负责「取/存」，不含判断逻辑。</p>
 *
 * <ul>
 *   <li>{@code UserRepository} —— 按邮箱查账号，注册查重。</li>
 *   <li>{@code TripRequestRepository} —— 需求单读取；按 owner 查历史。</li>
 *   <li>{@code TripPlanRepository} —— 按 request 查版本列表、按 id 取单个版本。</li>
 *   <li>{@code GuideSessionRepository} —— 导游会话读写。</li>
 *   <li>{@code PoiRepository} —— 景点库查询（城市/类型/名称）。</li>
 * </ul>
 *
 * <p>凡是会影响行程结果的条件（比如「哪些景点该被选中」）都写在
 * {@code service} 层，避免 SQL 里藏业务规则。</p>
 */
package com.travelguide.repo;
