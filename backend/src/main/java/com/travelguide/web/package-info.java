/**
 * 模块：REST 接口层（web）。
 *
 * <p>职责：只做 HTTP 到业务调用的翻译——接参数、做校验、返 DTO，不写业务判断。</p>
 *
 * <ul>
 *   <li>{@code PlanningController} —— {@code /api} 下的行程主链路：
 *       {@code POST /trips/draft}（自然语言起稿）、
 *       {@code POST /trips/{id}/messages}（对话改需求）、
 *       {@code PATCH /trips/{id}/requirements}（表单改槽位）、
 *       {@code POST|GET /trips/{id}/plans}（生成/列版本）、
 *       {@code GET /plans/{id}}、{@code GET /plans/{id}/items/{itemId}/alternatives}、
 *       {@code POST /plans/{id}/swap}（单点替换）、
 *       {@code POST /plans/{id}/restore}（回滚）、{@code GET /history}。</li>
 *   <li>{@code AuthController} —— {@code /api/auth}：me / register / login / logout，
 *       身份存在 HttpSession。</li>
 *   <li>{@code GuideController} —— {@code /api/guide/sessions}：建会话、查询、
 *       locate（上报定位）、select-poi（手选景点）、ask（追问）。</li>
 *   <li>{@code PublicController} —— {@code /api/bootstrap}（前端启动配置）、
 *       {@code /api/pois}（景点列表）。</li>
 *   <li>{@code ApiDtos} —— 请求/响应 record 集合，带 Bean Validation 注解。</li>
 *   <li>{@code ApiExceptionHandler} —— {@code @RestControllerAdvice}，把
 *       {@code ResponseStatusException}、参数校验失败、未知异常统一成
 *       {@code {timestamp, status, message}}。</li>
 * </ul>
 */
package com.travelguide.web;
