package com.travelguide.service;

import com.travelguide.ai.AiGateway;
import com.travelguide.repo.PoiRepository;
import com.travelguide.web.ApiDtos.BootstrapResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
public class BootstrapService {

    private final PoiRepository poiRepository;
    private final PoiMapper poiMapper;
    private final AiGateway aiGateway;
    private final String mapKey;
    private final String defaultCity;

    public BootstrapService(
            PoiRepository poiRepository,
            PoiMapper poiMapper,
            AiGateway aiGateway,
            @Value("${travel.map.amap-key:}") String mapKey,
            @Value("${travel.default-city:上海}") String defaultCity
    ) {
        this.poiRepository = poiRepository;
        this.poiMapper = poiMapper;
        this.aiGateway = aiGateway;
        this.mapKey = mapKey;
        this.defaultCity = defaultCity;
    }

    public BootstrapResponse get() {
        List<com.travelguide.web.ApiDtos.PoiView> featured = poiRepository.findAllByOrderByAreaAscNameAsc().stream()
                .limit(4)
                .map(poiMapper::toView)
                .toList();
        int month = LocalDate.now().getMonthValue();
        String season = month >= 3 && month <= 5 ? "春日 · 梧桐新绿"
                : month >= 6 && month <= 8 ? "夏日 · 江风夜游"
                : month >= 9 && month <= 11 ? "秋日 · 适合慢慢走"
                : "冬日 · 城市室内漫游";
        return new BootstrapResponse(
                defaultCity,
                season,
                (int) poiRepository.count(),
                featured,
                featured.stream()
                        .limit(3)
                        .map(poi -> "去" + defaultCity + "看" + poi.name() + "，安排一天轻松行程，预算 1000 元。")
                        .toList(),
                mapKey,
                aiGateway.isEnabled() ? "Spring AI 已连接" : "本地规则演示"
        );
    }
}
