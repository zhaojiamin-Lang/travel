package com.travelguide.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelguide.weather.WeatherModels.WeatherCast;
import com.travelguide.weather.WeatherModels.WeatherSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 天气轮询与快照缓存。
 *
 * <p>定时拉取高德天气并保留最后一份成功结果（{@code AtomicReference}），
 * 前端任何时候读到的都是「最近一次成功」的数据，不会因为一次失败就空掉。
 * 快照带状态（UNCONFIGURED / FRESH / STALE / ERROR）和抓取时间，
 * 前端据此决定是否提示「数据可能过期」。</p>
 */
@Service
public class WeatherSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(WeatherSchedulerService.class);
    private static final String SOURCE = "高德天气";

    private final RestClient client;
    private final String apiKey;
    private final String cityCode;
    private final AtomicReference<WeatherSnapshot> latest = new AtomicReference<>();

    public WeatherSchedulerService(
            @Value("${travel.map.amap-service-key:}") String apiKey,
            @Value("${travel.weather.city-code:310000}") String cityCode
    ) {
        this.apiKey = apiKey;
        this.cityCode = cityCode;
        this.client = RestClient.builder()
                .baseUrl("https://restapi.amap.com")
                .build();
        this.latest.set(new WeatherSnapshot(
                "上海",
                cityCode,
                "UNCONFIGURED",
                SOURCE,
                null,
                null,
                List.of(),
                null
        ));
    }

    /**
     * 定时刷新天气快照（缓存前置：让前端任何时候来读都有数据，不必等第三方接口）。
     *
     * <p>快照状态：</p>
     * <ul>
     *   <li>{@code UNCONFIGURED} —— 没配高德 Key，构造时预设的初始快照；</li>
     *   <li>{@code FRESH} —— 本次拉取成功，数据是新的；</li>
     *   <li>{@code STALE} —— 本次失败，但手里还有上一次成功的数据，降级可用；</li>
     *   <li>{@code ERROR} —— 从未成功过，forecasts 为空。</li>
     * </ul>
     * <p>{@code synchronized} 保证定时线程与请求线程不会读到写了一半的快照；
     * 失败分支只更新状态与错误信息，绝不覆盖上一份成功数据。</p>
     */
    @Scheduled(
            fixedDelayString = "${travel.weather.poll-interval-ms:600000}",
            initialDelayString = "${travel.weather.initial-delay-ms:5000}"
    )
    public synchronized void refreshForecast() {
        Instant now = Instant.now();
        if (apiKey == null || apiKey.isBlank()) {
            WeatherSnapshot previous = latest.get();
            latest.set(new WeatherSnapshot(
                    previous.city(),
                    cityCode,
                    "UNCONFIGURED",
                    SOURCE,
                    now,
                    previous.lastSuccessAt(),
                    previous.forecasts(),
                    "未配置高德 Web 服务 Key"
            ));
            return;
        }

        try {
            JsonNode body = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v3/weather/weatherInfo")
                            .queryParam("city", cityCode)
                            .queryParam("extensions", "all")
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);

            if (body == null || !"1".equals(body.path("status").asText())) {
                throw new IllegalStateException("高德天气返回状态异常");
            }

            // 用 RestClient + JsonNode 逐字段取值：数据源单一、字段固定（日期、昼夜天气、
            // 温度、风力共 8 个），为它建一套反序列化模型类不划算，是刻意的取舍。
            JsonNode forecast = body.path("forecasts").path(0);
            List<WeatherCast> casts = new ArrayList<>();
            forecast.path("casts").forEach(item -> casts.add(new WeatherCast(
                    item.path("date").asText(),
                    item.path("week").asText(),
                    item.path("dayweather").asText(),
                    item.path("nightweather").asText(),
                    item.path("daytemp").asText(),
                    item.path("nighttemp").asText(),
                    item.path("daywind").asText(),
                    item.path("nightwind").asText()
            )));

            String city = forecast.path("city").asText("上海");
            String adcode = forecast.path("adcode").asText(cityCode);
            latest.set(new WeatherSnapshot(
                    city,
                    adcode,
                    "FRESH",
                    SOURCE,
                    now,
                    now,
                    List.copyOf(casts),
                    null
            ));
            log.info("Weather refreshed: city={}, days={}", city, casts.size());
        } catch (RuntimeException exception) {
            WeatherSnapshot previous = latest.get();
            latest.set(new WeatherSnapshot(
                    previous.city(),
                    cityCode,
                    previous.forecasts().isEmpty() ? "ERROR" : "STALE",
                    SOURCE,
                    now,
                    previous.lastSuccessAt(),
                    previous.forecasts(),
                    exception.getMessage()
            ));
            log.warn("Weather refresh failed; keeping the previous forecast: {}", exception.getMessage());
        }
    }

    public WeatherSnapshot latest() {
        return latest.get();
    }
}
