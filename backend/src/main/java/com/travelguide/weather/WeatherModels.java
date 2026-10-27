package com.travelguide.weather;

import java.time.Instant;
import java.util.List;

public final class WeatherModels {

    private WeatherModels() {
    }

    public record WeatherCast(
            String date,
            String weekday,
            String dayWeather,
            String nightWeather,
            String dayTemperature,
            String nightTemperature,
            String dayWind,
            String nightWind
    ) {
    }

    public record WeatherSnapshot(
            String city,
            String adcode,
            String status,
            String source,
            Instant lastAttemptAt,
            Instant lastSuccessAt,
            List<WeatherCast> forecasts,
            String error
    ) {
    }
}
