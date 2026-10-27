package com.travelguide.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelguide.domain.PoiEntity;
import com.travelguide.repo.PoiRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;

@Service
public class PoiSeedService implements ApplicationRunner {

    private final PoiRepository poiRepository;
    private final ObjectMapper objectMapper;

    public PoiSeedService(PoiRepository poiRepository, ObjectMapper objectMapper) {
        this.poiRepository = poiRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (poiRepository.count() > 0) {
            return;
        }
        try (InputStream input = new ClassPathResource("data/pois.json").getInputStream()) {
            List<PoiSeed> seeds = objectMapper.readValue(input, new TypeReference<>() {
            });
            poiRepository.saveAll(seeds.stream().map(this::toEntity).toList());
        }
    }

    private PoiEntity toEntity(PoiSeed seed) {
        PoiEntity poi = new PoiEntity();
        poi.setId(seed.id());
        poi.setName(seed.name());
        poi.setAliasName(seed.aliasName());
        poi.setType(seed.type());
        poi.setArea(seed.area());
        poi.setLatitude(seed.latitude());
        poi.setLongitude(seed.longitude());
        poi.setDurationMinutes(seed.durationMinutes());
        poi.setTicketPrice(seed.ticketPrice() == null ? BigDecimal.ZERO : seed.ticketPrice());
        poi.setOpenTime(seed.openTime());
        poi.setCloseTime(seed.closeTime());
        poi.setTags(String.join(",", seed.tags()));
        poi.setAccent(seed.accent());
        poi.setSummary(seed.summary());
        poi.setHistory(seed.history());
        poi.setGuideText(seed.guideText());
        poi.setSourceName(seed.sourceName());
        poi.setSourceUrl(seed.sourceUrl());
        poi.setDataConfidence(seed.dataConfidence());
        return poi;
    }

    public record PoiSeed(
            String id,
            String name,
            String aliasName,
            String type,
            String area,
            Double latitude,
            Double longitude,
            Integer durationMinutes,
            BigDecimal ticketPrice,
            String openTime,
            String closeTime,
            List<String> tags,
            String accent,
            String summary,
            String history,
            String guideText,
            String sourceName,
            String sourceUrl,
            String dataConfidence
    ) {
    }
}
