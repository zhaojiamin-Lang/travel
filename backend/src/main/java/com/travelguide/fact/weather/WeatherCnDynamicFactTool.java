package com.travelguide.fact.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelguide.fact.DynamicFactTool;
import com.travelguide.fact.FactModels.FactQuery;
import com.travelguide.fact.FactModels.FactRecord;
import com.travelguide.fact.FactModels.SourceAuthority;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WeatherCN 实况与逐日天气事实工具。
 */
public final class WeatherCnDynamicFactTool implements DynamicFactTool {

    public static final String CURRENT = "WEATHER_CURRENT";
    public static final String FORECAST = "WEATHER_FORECAST";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final WeatherCnClient client;

    public WeatherCnDynamicFactTool(WeatherCnClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "weathercn";
    }

    @Override
    public boolean supports(FactQuery query) {
        return CURRENT.equals(query.factType()) || FORECAST.equals(query.factType());
    }

    @Override
    public List<FactRecord> fetch(FactQuery query) {
        String locationKey = locationKey(query);
        if (CURRENT.equals(query.factType())) {
            return current(query, locationKey);
        }
        return forecast(query, locationKey);
    }

    private List<FactRecord> current(FactQuery query, String locationKey) {
        JsonNode root = client.currentConditions(locationKey);
        JsonNode value = root.isArray() ? root.path(0) : root;
        if (value.isMissingNode() || value.isNull()) {
            return List.of();
        }
        Instant observedAt = instant(value.path("LocalObservationDateTime").asText(), query.asOf());
        Map<String, Object> facts = new LinkedHashMap<>();
        putText(facts, "weatherText", value.path("WeatherText").asText(null));
        putNumber(facts, "temperatureC",
                value.path("Temperature").path("Metric").path("Value"));
        putNumber(facts, "realFeelC",
                value.path("RealFeelTemperature").path("Metric").path("Value"));
        putNumber(facts, "relativeHumidity", value.path("RelativeHumidity"));
        putNumber(facts, "windSpeedKph",
                value.path("Wind").path("Speed").path("Metric").path("Value"));
        putText(facts, "windDirection",
                value.path("Wind").path("Direction").path("Localized").asText(null));
        facts.put("hasPrecipitation", value.path("HasPrecipitation").asBoolean(false));
        putText(facts, "precipitationType", value.path("PrecipitationType").asText(null));
        putText(facts, "uvIndexText", value.path("UVIndexText").asText(null));
        facts.put("locationKey", locationKey);
        facts.put("observedAt", observedAt.toString());

        return List.of(new FactRecord(
                "weathercn:current:" + locationKey + ":" + observedAt.getEpochSecond(),
                query.entityId(),
                CURRENT,
                facts,
                "weathercn",
                "中国天气·华风爱科",
                SourceAuthority.AUTHORIZED_THIRD_PARTY,
                observedAt,
                observedAt,
                observedAt.plus(Duration.ofMinutes(15)),
                Map.of(),
                0.95
        ));
    }

    private List<FactRecord> forecast(FactQuery query, String locationKey) {
        if (query.applicableDate() == null) {
            throw new IllegalArgumentException("天气预报表必须提供 applicableDate");
        }
        int days = requestedDays(query);
        JsonNode root = client.dailyForecast(locationKey, days);
        for (JsonNode daily : root.path("DailyForecasts")) {
            String rawDate = daily.path("Date").asText();
            LocalDate date = instant(rawDate, query.asOf()).atZone(SHANGHAI).toLocalDate();
            if (!query.applicableDate().equals(date)) {
                continue;
            }
            Map<String, Object> facts = new LinkedHashMap<>();
            putNumber(facts, "minimumTemperatureC", daily.path("Temperature").path("Minimum").path("Value"));
            putNumber(facts, "maximumTemperatureC", daily.path("Temperature").path("Maximum").path("Value"));
            putNumber(facts, "realFeelMinimumC",
                    daily.path("RealFeelTemperature").path("Minimum").path("Value"));
            putNumber(facts, "realFeelMaximumC",
                    daily.path("RealFeelTemperature").path("Maximum").path("Value"));
            putText(facts, "dayWeather", daily.path("Day").path("IconPhrase").asText(null));
            putText(facts, "nightWeather", daily.path("Night").path("IconPhrase").asText(null));
            putNumber(facts, "dayPrecipitationProbability",
                    daily.path("Day").path("PrecipitationProbability"));
            putNumber(facts, "nightPrecipitationProbability",
                    daily.path("Night").path("PrecipitationProbability"));
            putNumber(facts, "dayWindSpeedKph",
                    daily.path("Day").path("Wind").path("Speed").path("Value"));
            putText(facts, "dayWindDirection",
                    daily.path("Day").path("Wind").path("Direction").path("Localized").asText(null));
            facts.put("locationKey", locationKey);
            facts.put("applicableDate", query.applicableDate().toString());

            Instant observedAt = instant(
                    daily.path("Day").path("LocalSource").path("PubTime").asText(),
                    query.asOf()
            );
            Instant validFrom = query.applicableDate().atStartOfDay(SHANGHAI).toInstant();
            Instant validTo = query.applicableDate().plusDays(1).atStartOfDay(SHANGHAI).toInstant()
                    .minusSeconds(1);
            return List.of(new FactRecord(
                    "weathercn:forecast:" + locationKey + ":" + query.applicableDate(),
                    query.entityId(),
                    FORECAST,
                    facts,
                    "weathercn",
                    "中国天气·华风爱科",
                    SourceAuthority.AUTHORIZED_THIRD_PARTY,
                    observedAt,
                    validFrom,
                    validTo,
                    Map.of("applicableDate", query.applicableDate().toString()),
                    0.90
            ));
        }
        return List.of();
    }

    private String locationKey(FactQuery query) {
        String existing = query.context().get("locationKey");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String latitude = query.context().get("latitude");
        String longitude = query.context().get("longitude");
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("天气查询需要 locationKey 或 latitude/longitude");
        }
        return client.resolveLocationKey(latitude, longitude);
    }

    private int requestedDays(FactQuery query) {
        String configured = query.context().getOrDefault("forecastDays", "5");
        int days;
        try {
            days = Integer.parseInt(configured);
        } catch (NumberFormatException exception) {
            days = 5;
        }
        return days == 1 || days == 5 || days == 10 ? days : 5;
    }

    private Instant instant(String value, Instant fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            return fallback;
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private void putNumber(Map<String, Object> target, String key, JsonNode value) {
        if (value != null && value.isNumber()) {
            target.put(key, value.decimalValue());
        }
    }
}
