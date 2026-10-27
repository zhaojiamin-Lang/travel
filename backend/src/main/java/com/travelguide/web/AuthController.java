package com.travelguide.web;

import com.travelguide.service.AuthService;
import com.travelguide.web.ApiDtos.LoginRequest;
import com.travelguide.web.ApiDtos.RegisterRequest;
import com.travelguide.web.ApiDtos.UserView;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public UserView me(HttpSession session) {
        return authService.ensureUser(session);
    }

    @PostMapping("/register")
    public UserView register(
            HttpSession session,
            @Valid @RequestBody RegisterRequest request
    ) {
        return authService.register(session, request);
    }

    @PostMapping("/login")
    public UserView login(
            HttpSession session,
            @Valid @RequestBody LoginRequest request
    ) {
        return authService.login(session, request);
    }

    @PostMapping("/logout")
    public Map<String, Boolean> logout(HttpSession session) {
        authService.logout(session);
        return Map.of("success", true);
    }
}
