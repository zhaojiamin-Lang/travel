/**
 * 应用根模块：旅行导游助手后端（Spring Boot 3 + Spring AI）。
 *
 * <p>启动入口为 {@code com.travelguide.TravelGuideApplication}，按分层拆成 8 个子包：
 * {@code ai}（大模型网关）、{@code config}（公共配置）、{@code domain}（实体）、
 * {@code repo}（数据访问）、{@code service}（业务）、{@code web}（REST 接口）、
 * {@code weather}（天气轮询）、{@code replan}（事件驱动的行程重排）。</p>
 *
 * <p>调用方向固定为 web -&gt; service -&gt; repo/domain；外部依赖（千问、高德）
 * 只允许出现在 ai、service、weather 三个包里，避免业务逻辑与第三方协议耦合。
 * 更完整的模块地图见仓库根目录的 {@code MODULES.md}。</p>
 */
package com.travelguide;
