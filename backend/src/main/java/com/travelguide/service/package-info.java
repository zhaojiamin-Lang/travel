/**
 * 模块：核心业务服务（service）。
 *
 * <p>职责：整个应用的业务重心，行程怎么生成、怎么校验、怎么改，都在这里。</p>
 *
 * <ul>
 *   <li>{@code PlanningService} —— 主链路：自然语言 -&gt; 需求槽位（缺字段要让用户补）
 *       -&gt; 生成多天行程（含用餐、交通、预算、校验问题）-&gt; 存成不可变版本；
 *       另含自然语言改需求、单点替换、备选列表、版本回滚、历史查询。</li>
 *   <li>{@code AuthService} —— 注册/登录/登出/游客身份，基于 HttpSession 的
 *       {@code travelUserId}，密码用 BCrypt。</li>
 *   <li>{@code GuideService} —— 现场导游：建会话、定位匹配最近景点、
 *       手动选点、生成讲解内容、回答追问（AI 不可用时走确定性答案）。</li>
 *   <li>{@code BootstrapService} —— 一次性返回前端启动所需配置
 *       （地图 Key 是否配置、AI 是否可用、POI 概览等）。</li>
 *   <li>{@code PoiSeedService} —— 启动时把 {@code resources/data} 下的 POI
 *       种子数据灌进库，已有数据则跳过。</li>
 *   <li>{@code PoiMapper} —— 实体到视图对象的转换，避免实体直接出网。</li>
 *   <li>{@code AmapRouteService} —— 高德路径规划：两点通行时间；
 *       没配 Key 或请求失败时按直线距离估算，并标注来源供前端说明。</li>
 * </ul>
 *
 * <p>依赖方向：调用 {@code ai}、{@code repo}、{@code domain}、{@code weather}；
 * 被 {@code web} 和 {@code replan} 调用。本层不处理 HTTP 细节。</p>
 */
package com.travelguide.service;
