package com.travelguide.service;

import com.travelguide.ai.AiGateway;
import com.travelguide.repo.PoiRepository;
import com.travelguide.web.ApiDtos.BootstrapResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

@Service
public class BootstrapService {

    private final PoiRepository poiRepository;
    private final PoiMapper poiMapper;
    private final AiGateway aiGateway;
    private final String mapKey;

    public BootstrapService(
            PoiRepository poiRepository,
            PoiMapper poiMapper,
            AiGateway aiGateway,
            @Value("${travel.map.amap-key:}") String mapKey
    ) {
        this.poiRepository = poiRepository;
        this.poiMapper = poiMapper;
        this.aiGateway = aiGateway;
        this.mapKey = mapKey;
    }

    public BootstrapResponse get() {
        Set<String> featuredIds = Set.of("bund", "yuyuan", "wukang-road", "shanghai-museum");
        List<com.travelguide.web.ApiDtos.PoiView> featured = poiRepository.findAll().stream()
                .filter(poi -> featuredIds.contains(poi.getId()))
                .map(poiMapper::toView)
                .toList();
        int month = LocalDate.now().getMonthValue();
        String season = month >= 3 && month <= 5 ? "春日 · 梧桐新绿"
                : month >= 6 && month <= 8 ? "夏日 · 江风夜游"
                : month >= 9 && month <= 11 ? "秋日 · 适合慢慢走"
                : "冬日 · 城市室内漫游";
        return new BootstrapResponse(
                "上海",
                season,
                (int) poiRepository.count(),
                featured,
                List.of(
                        "下周去上海玩两天，预算 3000 元，想去外滩和上海博物馆，不吃辣，不想走太累。",
                        "周末在上海慢游一天，喜欢建筑和街区，预算 800 元。",
                        "带家人去上海三天，每天安排轻松一点，预算 5000 元。"
                ),
                mapKey,
                aiGateway.isEnabled() ? "Spring AI 已连接" : "本地规则演示"
        );
    }
}
