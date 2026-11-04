package com.travelguide.config;

import com.travelguide.service.AuthService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 在浏览器 Cookie 会话因应用重启失效后，用本地稳定访客标识恢复游客身份。
 *
 * <p>只恢复仍标记为 guest 的游客账号；已注册用户的身份仍必须通过登录恢复。</p>
 */
@Component
public class GuestIdentityFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Travel-Visitor-Id";

    private final AuthService authService;

    public GuestIdentityFilter(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String path = request.getRequestURI();
        String visitorId = request.getHeader(HEADER);
        if (path != null && path.startsWith("/api/")
                && visitorId != null
                && visitorId.matches("usr_[a-f0-9]{32}")) {
            authService.restoreGuest(request.getSession(true), visitorId);
        }
        filterChain.doFilter(request, response);
    }
}
