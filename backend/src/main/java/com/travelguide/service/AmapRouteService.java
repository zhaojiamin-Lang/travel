package com.travelguide.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelguide.domain.PoiEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class AmapRouteService {

    private final String apiKey;
    private final RestClient client;

    public AmapRouteService(@Value("${travel.map.amap-service-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.client = RestClient.builder()
                .baseUrl("https://restapi.amap.com")
                .build();
    }

    public RouteEstimate between(PoiEntity from, PoiEntity to) {
        int straightLineMinutes = estimateStraightLineMinutes(from, to);
        if (apiKey == null || apiKey.isBlank()) {
            return new RouteEstimate(straightLineMinutes, "WALK", "步行 / 地铁估算");
        }

        String mode = straightLineMinutes <= 42 ? "walking" : "driving";
        String modeLabel = "walking".equals(mode) ? "高德步行" : "高德驾车";
        try {
            JsonNode body = client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v3/direction/" + mode)
                            .queryParam("origin", from.getLongitude() + "," + from.getLatitude())
                            .queryParam("destination", to.getLongitude() + "," + to.getLatitude())
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (body != null && "1".equals(body.path("status").asText())) {
                int durationSeconds = body.path("route").path("paths").path(0).path("duration").asInt(0);
                if (durationSeconds > 0) {
                    int minutes = Math.max(3, (int) Math.ceil(durationSeconds / 60.0));
                    return new RouteEstimate(minutes, mode.toUpperCase(), modeLabel + "约 " + minutes + " 分钟");
                }
            }
        } catch (RuntimeException ignored) {
            // Fall back without making the whole itinerary generation fail.
        }
        return new RouteEstimate(straightLineMinutes, "ESTIMATE", "路线估算");
    }

    private int estimateStraightLineMinutes(PoiEntity from, PoiEntity to) {
        double distance = distanceMeters(
                from.getLatitude(), from.getLongitude(), to.getLatitude(), to.getLongitude()
        );
        double walkingSpeed = 1.15;
        return Math.max(8, (int) Math.ceil(distance / walkingSpeed / 60 + 4));
    }

    private double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double earth = 6371000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return earth * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public record RouteEstimate(int minutes, String mode, String label) {
    }
}
