package com.travelguide.service;

import com.travelguide.domain.PoiEntity;
import com.travelguide.web.ApiDtos.PoiView;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

@Component
public class PoiMapper {

    public PoiView toView(PoiEntity poi) {
        return new PoiView(
                poi.getId(),
                poi.getName(),
                poi.getType(),
                poi.getArea(),
                value(poi.getLatitude()),
                value(poi.getLongitude()),
                poi.getDurationMinutes() == null ? 60 : poi.getDurationMinutes(),
                poi.getTicketPrice() == null ? BigDecimal.ZERO : poi.getTicketPrice(),
                poi.getOpenTime(),
                poi.getCloseTime(),
                tags(poi.getTags()),
                poi.getAccent(),
                poi.getSummary(),
                poi.getHistory(),
                poi.getSourceName(),
                poi.getSourceUrl(),
                poi.getDataConfidence()
        );
    }

    private List<String> tags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
    }

    private double value(Double value) {
        return value == null ? 0 : value;
    }
}
