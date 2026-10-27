/**
 * 模块：天气轮询（weather）。
 *
 * <p>职责：后台定时拉取高德天气预报，保留「最后一次成功结果」，供前端随时查询，
 * 避免用户打开页面时才去等第三方接口。</p>
 *
 * <ul>
 *   <li>{@code WeatherSchedulerService} —— 按
 *       {@code travel.weather.poll-interval-ms}（默认 10 分钟）轮询，
 *       首次延迟 {@code travel.weather.initial-delay-ms}；结果放进
 *       {@code AtomicReference} 快照。状态含义：UNCONFIGURED（未配 Key）、
 *       FRESH（本次成功）、STALE（本次失败但手里有旧数据，降级可用）、
 *       ERROR（从未成功过）。失败绝不覆盖上一份成功数据。</li>
 *   <li>{@code WeatherController} —— 只暴露 {@code GET /api/weather/latest}，
 *       不触发外部请求，只读快照。</li>
 *   <li>{@code WeatherConfiguration} —— {@code @EnableScheduling}，定时能力的唯一开关。</li>
 *   <li>{@code WeatherModels} —— 快照、逐日预报等传输对象。</li>
 * </ul>
 *
 * <p>为什么用 {@code RestClient} + {@code JsonNode} 手写解析：单一来源、字段固定（8 个），
 * 为此引入一整套反序列化模型类不划算，属于有意识的取舍。</p>
 */
package com.travelguide.weather;
