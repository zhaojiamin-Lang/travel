package com.travelguide.web;

import com.travelguide.service.GuideService;
import com.travelguide.web.ApiDtos.AskRequest;
import com.travelguide.web.ApiDtos.AskResponse;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.LocationRequest;
import com.travelguide.web.ApiDtos.LocationResponse;
import com.travelguide.web.ApiDtos.SelectPoiRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/guide/sessions")
public class GuideController {

    private final GuideService guideService;

    public GuideController(GuideService guideService) {
        this.guideService = guideService;
    }

    @PostMapping
    public GuideSessionView create(@Valid @RequestBody GuideCreateRequest request) {
        return guideService.create(request);
    }

    @GetMapping("/{sessionId}")
    public GuideSessionView get(@PathVariable String sessionId) {
        return guideService.get(sessionId);
    }

    @PostMapping("/{sessionId}/locate")
    public LocationResponse locate(
            @PathVariable String sessionId,
            @RequestBody LocationRequest request
    ) {
        return guideService.locate(sessionId, request);
    }

    @PostMapping("/{sessionId}/select-poi")
    public GuideSessionView select(
            @PathVariable String sessionId,
            @Valid @RequestBody SelectPoiRequest request
    ) {
        return guideService.select(sessionId, request.poiId());
    }

    @PostMapping("/{sessionId}/ask")
    public AskResponse ask(
            @PathVariable String sessionId,
            @Valid @RequestBody AskRequest request
    ) {
        return guideService.ask(sessionId, request.question());
    }
}
