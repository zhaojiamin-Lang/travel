package com.travelguide.weather;

import com.travelguide.weather.WeatherModels.WeatherSnapshot;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
public class WeatherController {

    private final WeatherSchedulerService weatherSchedulerService;

    public WeatherController(WeatherSchedulerService weatherSchedulerService) {
        this.weatherSchedulerService = weatherSchedulerService;
    }

    @GetMapping("/latest")
    public WeatherSnapshot latest() {
        return weatherSchedulerService.latest();
    }
}
