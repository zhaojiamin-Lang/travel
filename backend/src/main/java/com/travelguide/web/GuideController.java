package com.travelguide.web;

import com.travelguide.service.AuthService;
import com.travelguide.service.GuideService;
import com.travelguide.web.ApiDtos.AskRequest;
import com.travelguide.web.ApiDtos.AskResponse;
import com.travelguide.web.ApiDtos.GuideCreateRequest;
import com.travelguide.web.ApiDtos.GuideSessionView;
import com.travelguide.web.ApiDtos.LocationRequest;
import com.travelguide.web.ApiDtos.LocationResponse;
import com.travelguide.web.ApiDtos.SelectPoiRequest;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpSession;
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
    private final AuthService authService;

    public GuideController(GuideService guideService, AuthService authService) {
        this.guideService = guideService;
        this.authService = authService;
    }

    @PostMapping
    public GuideSessionView create(
            @Valid @RequestBody GuideCreateRequest request,
            HttpSession session
    ) {
        return guideService.create(request, authService.ensureUser(session).id());
    }

    @GetMapping("/{sessionId}")
    public GuideSessionView get(
            @PathVariable String sessionId,
            HttpSession session
    ) {
        return guideService.get(
                sessionId,
                authService.ensureUser(session).id()
        );
    }

    @PostMapping("/{sessionId}/locate")
    public LocationResponse locate(
            @PathVariable String sessionId,
            @RequestBody LocationRequest request,
            HttpSession session
    ) {
        return guideService.locate(
                sessionId,
                request,
                authService.ensureUser(session).id()
        );
    }

    @PostMapping("/{sessionId}/select-poi")
    public GuideSessionView select(
            @PathVariable String sessionId,
            @Valid @RequestBody SelectPoiRequest request,
            HttpSession session
    ) {
        return guideService.select(
                sessionId,
                request.poiId(),
                authService.ensureUser(session).id()
        );
    }

    @PostMapping("/{sessionId}/ask")
    public AskResponse ask(
            @PathVariable String sessionId,
            @Valid @RequestBody AskRequest request,
            HttpSession httpSession
    ) {
        String userId = authService.ensureUser(httpSession).id();
        return guideService.ask(sessionId, request.question(), userId);
    }
}
