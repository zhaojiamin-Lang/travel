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
