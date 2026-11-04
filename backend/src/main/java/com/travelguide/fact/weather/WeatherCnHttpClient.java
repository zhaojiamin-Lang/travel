package com.travelguide.fact.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.harness.resilience.ResilienceDefaults;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * WeatherCN OpenAPI HTTP 客户端。
 *
 * <p>API Key 只从调用方配置读取，并通过 X-Gw-API-Key 请求头发送，
 * 不拼进 URL，也不输出到异常信息。</p>
 */
public final class WeatherCnHttpClient implements WeatherCnClient {

    private static final String DEFAULT_BASE_URL = "https://openapi.weathercn.com";

    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final CircuitBreaker circuitBreaker;

    public WeatherCnHttpClient(
            String apiKey,
            ObjectMapper objectMapper
    ) {
        this(DEFAULT_BASE_URL, apiKey, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    public WeatherCnHttpClient(
            String baseUrl,
            String apiKey,
            ObjectMapper objectMapper
    ) {
        this(baseUrl, apiKey, objectMapper, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build());
    }

    public WeatherCnHttpClient(
            String baseUrl,
            String apiKey,
            ObjectMapper objectMapper,
            HttpClient httpClient
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("WeatherCN API Key 未配置");
        }
        this.baseUrl = trimTrailingSlash(baseUrl == null || baseUrl.isBlank()
                ? DEFAULT_BASE_URL
                : baseUrl);
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.circuitBreaker = ResilienceDefaults.circuitBreaker("weathercn");
    }

    @Override
    public String resolveLocationKey(String latitude, String longitude) {
        String coordinates = URLEncoder.encode(latitude + "," + longitude, StandardCharsets.UTF_8);
        JsonNode response = get("/locations/v1/cities/geoposition/search.json?q="
                + coordinates + "&language=zh-cn");
        String key = response.path("Key").asText();
        if (key.isBlank()) {
            throw new IllegalStateException("WeatherCN 未返回 Location Key");
        }
        return key;
    }

    @Override
    public JsonNode currentConditions(String locationKey) {
        return get("/currentconditions/v1/" + pathSegment(locationKey)
                + ".json?language=zh-cn&details=true");
    }

    @Override
    public JsonNode dailyForecast(String locationKey, int days) {
        if (days != 1 && days != 5 && days != 10) {
            throw new IllegalArgumentException("标准逐日预报只支持 1、5 或 10 天");
        }
        return get("/forecasts/v1/daily/" + days + "day/" + pathSegment(locationKey)
                + ".json?language=zh-cn&details=true&metric=true");
    }

    private JsonNode get(String path) {
        try {
            Callable<JsonNode> call = CircuitBreaker.decorateCallable(
                    circuitBreaker,
                    () -> request(path)
            );
            return call.call();
        } catch (CallNotPermittedException exception) {
            throw new IllegalStateException("WeatherCN 已熔断", exception);
        } catch (Exception exception) {
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("WeatherCN 请求失败", exception);
        }
    }

    private JsonNode request(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .header("X-Gw-API-Key", apiKey)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                // 不把请求头或完整响应写进异常，避免 API Key 和业务数据进入日志。
                throw new IllegalStateException("WeatherCN 请求失败，HTTP " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException exception) {
            throw new IllegalStateException("WeatherCN 网络请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WeatherCN 请求被中断", exception);
        }
    }

    private String pathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
