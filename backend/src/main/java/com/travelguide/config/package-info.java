/**
 * 模块：公共配置（config）。
 *
 * <p>职责：承载与业务无关的横切配置，目前两件事。</p>
 *
 * <ul>
 *   <li>{@code WebConfig} —— CORS：只放行本地前端 {@code localhost:5173} /
 *       {@code 127.0.0.1:5173} 访问 {@code /api/**}，允许
 *       GET/POST/PATCH/DELETE/OPTIONS。</li>
 *   <li>{@code AuthConfig} —— 暴露 {@code BCryptPasswordEncoder}，
 *       给 {@code service.AuthService} 做密码哈希与校验。</li>
 * </ul>
 *
 * <p>定时任务开关不在这里：由 {@code weather.WeatherConfiguration} 上的
 * {@code @EnableScheduling} 打开，放在使用者旁边。</p>
 */
package com.travelguide.config;
