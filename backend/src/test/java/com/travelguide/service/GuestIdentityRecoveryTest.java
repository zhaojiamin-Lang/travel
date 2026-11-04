package com.travelguide.service;

import com.travelguide.config.GuestIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "travel.ai.api-key=",
        "travel.map.amap-service-key="
})
class GuestIdentityRecoveryTest {

    @Autowired
    private AuthService authService;

    @Test
    void restoresTheSameGuestAfterTheCookieSessionIsLost() throws Exception {
        String visitorId = "usr_" + UUID.randomUUID().toString().replace("-", "");
        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/api/history");
        firstRequest.addHeader(GuestIdentityFilter.HEADER, visitorId);
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();

        new GuestIdentityFilter(authService).doFilter(
                firstRequest,
                firstResponse,
                (request, response) -> {
                }
        );

        var first = authService.current(firstRequest.getSession(false));

        MockHttpServletRequest restartedRequest = new MockHttpServletRequest("GET", "/api/history");
        restartedRequest.addHeader(GuestIdentityFilter.HEADER, visitorId);
        MockHttpServletResponse restartedResponse = new MockHttpServletResponse();
        restartedRequest.setSession(new MockHttpSession());

        new GuestIdentityFilter(authService).doFilter(
                restartedRequest,
                restartedResponse,
                (request, response) -> {
                }
        );

        var restored = authService.current(restartedRequest.getSession(false));
        assertThat(first).isNotNull();
        assertThat(first.guest()).isTrue();
        assertThat(restored).isNotNull();
        assertThat(restored.id()).isEqualTo(visitorId);
        assertThat(restored.id()).isEqualTo(first.id());
    }
}
