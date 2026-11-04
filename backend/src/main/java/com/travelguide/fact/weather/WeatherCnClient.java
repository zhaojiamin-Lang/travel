package com.travelguide.fact.weather;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * WeatherCN 接口适配层，便于测试时替换真实网络调用。
 */
public interface WeatherCnClient {

    String resolveLocationKey(String latitude, String longitude);

    JsonNode currentConditions(String locationKey);

    JsonNode dailyForecast(String locationKey, int days);
}
