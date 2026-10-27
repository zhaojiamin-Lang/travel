package com.travelguide.web;

import com.travelguide.repo.PoiRepository;
import com.travelguide.service.BootstrapService;
import com.travelguide.service.PoiMapper;
import com.travelguide.web.ApiDtos.BootstrapResponse;
import com.travelguide.web.ApiDtos.PoiView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class PublicController {

    private final BootstrapService bootstrapService;
    private final PoiRepository poiRepository;
    private final PoiMapper poiMapper;

    public PublicController(BootstrapService bootstrapService, PoiRepository poiRepository, PoiMapper poiMapper) {
        this.bootstrapService = bootstrapService;
        this.poiRepository = poiRepository;
        this.poiMapper = poiMapper;
    }

    @GetMapping("/bootstrap")
    public BootstrapResponse bootstrap() {
        return bootstrapService.get();
    }

    @GetMapping("/pois")
    public List<PoiView> pois() {
        return poiRepository.findAllByOrderByAreaAscNameAsc().stream().map(poiMapper::toView).toList();
    }
}
