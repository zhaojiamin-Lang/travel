package com.travelguide.fact.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.fact.FactModels.FactQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WeatherCnDynamicFactToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void mapsCurrentWeatherIntoDynamicFact() throws Exception {
        JsonNode response = objectMapper.readTree("""
                [{
                  "LocalObservationDateTime": "2026-10-29T12:00:00+08:00",
                  "WeatherText": "多云",
                  "HasPrecipitation": false,
                  "Temperature": {"Metric": {"Value": 21.5}},
                  "RealFeelTemperature": {"Metric": {"Value": 20.8}},
                  "RelativeHumidity": 63,
                  "UVIndexText": "弱",
                  "Wind": {
                    "Direction": {"Localized": "东南"},
                    "Speed": {"Metric": {"Value": 12.4}}
                  }
                }]
                """);
        var tool = new WeatherCnDynamicFactTool(new StubClient(response, null));
        var query = new FactQuery(
                "poi:bund",
                WeatherCnDynamicFactTool.CURRENT,
                Instant.parse("2026-10-29T04:05:00Z"),
                LocalDate.of(2026, 10, 29),
                Map.of("locationKey", "101020100")
        );

        var facts = tool.fetch(query);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).value())
                .containsEntry("weatherText", "多云")
                .containsEntry("relativeHumidity", new java.math.BigDecimal("63"));
        assertThat(facts.get(0).authority().name()).isEqualTo("AUTHORIZED_THIRD_PARTY");
        assertThat(facts.get(0).validTo()).isAfter(facts.get(0).observedAt());
    }

    @Test
    void selectsRequestedForecastDate() throws Exception {
        JsonNode response = objectMapper.readTree("""
                {
                  "DailyForecasts": [
                    {
                      "Date": "2026-10-28T08:00:00+08:00",
                      "Temperature": {
                        "Minimum": {"Value": 17},
                        "Maximum": {"Value": 23}
                      },
                      "Day": {"IconPhrase": "晴", "PrecipitationProbability": 10},
                      "Night": {"IconPhrase": "多云", "PrecipitationProbability": 20}
                    },
                    {
                      "Date": "2026-10-29T08:00:00+08:00",
                      "Temperature": {
                        "Minimum": {"Value": 18},
                        "Maximum": {"Value": 24}
                      },
                      "Day": {"IconPhrase": "小雨", "PrecipitationProbability": 70},
                      "Night": {"IconPhrase": "阴", "PrecipitationProbability": 30}
                    }
                  ]
                }
                """);
        var tool = new WeatherCnDynamicFactTool(new StubClient(null, response));
        var query = new FactQuery(
                "poi:bund",
                WeatherCnDynamicFactTool.FORECAST,
                Instant.parse("2026-10-29T04:05:00Z"),
                LocalDate.of(2026, 10, 29),
                Map.of("locationKey", "101020100", "forecastDays", "5")
        );

        var facts = tool.fetch(query);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).value())
                .containsEntry("dayWeather", "小雨")
                .containsEntry("maximumTemperatureC", new java.math.BigDecimal("24"));
        assertThat(facts.get(0).conditions()).containsEntry("applicableDate", "2026-10-29");
    }

    private record StubClient(JsonNode current, JsonNode forecast) implements WeatherCnClient {
        @Override
        public String resolveLocationKey(String latitude, String longitude) {
            return "101020100";
        }

        @Override
        public JsonNode currentConditions(String locationKey) {
            return current;
        }

        @Override
        public JsonNode dailyForecast(String locationKey, int days) {
            return forecast;
        }
    }
}
