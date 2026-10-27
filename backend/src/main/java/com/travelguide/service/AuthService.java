package com.travelguide.service;

import com.travelguide.domain.UserEntity;
import com.travelguide.repo.TripRequestRepository;
import com.travelguide.repo.UserRepository;
import com.travelguide.web.ApiDtos.LoginRequest;
import com.travelguide.web.ApiDtos.RegisterRequest;
import com.travelguide.web.ApiDtos.UserView;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private static final String USER_SESSION_KEY = "travelUserId";
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final UserRepository userRepository;
    private final TripRequestRepository requestRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            TripRequestRepository requestRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.requestRepository = requestRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView ensureUser(HttpSession session) {
        String existingId = (String) session.getAttribute(USER_SESSION_KEY);
        if (existingId != null) {
            UserEntity existing = userRepository.findById(existingId).orElse(null);
            if (existing != null) {
                return toView(existing);
            }
        }

        UserEntity guest = new UserEntity();
        guest.setId("usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        guest.setDisplayName("旅行者");
        guest.setGuest(true);
        guest.setCreatedAt(LocalDateTime.now(SHANGHAI));
        userRepository.save(guest);
        session.setAttribute(USER_SESSION_KEY, guest.getId());
        return toView(guest);
    }

    @Transactional
    public UserView register(HttpSession session, RegisterRequest request) {
        String email = normalizeEmail(request.email());
        if (userRepository.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已经注册");
        }

        UserEntity current = currentEntity(session);
        UserEntity user;
        if (current != null && Boolean.TRUE.equals(current.getGuest()) && current.getEmail() == null) {
            user = current;
        } else {
            user = new UserEntity();
            user.setId("usr_" + UUID.randomUUID().toString().replace("-", "").substring(0, 14));
            user.setCreatedAt(LocalDateTime.now(SHANGHAI));
        }
        user.setEmail(email);
        user.setDisplayName(request.displayName().trim());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setGuest(false);
        userRepository.save(user);
        session.setAttribute(USER_SESSION_KEY, user.getId());
        return toView(user);
    }

    @Transactional
    public UserView login(HttpSession session, LoginRequest request) {
        UserEntity user = userRepository.findByEmail(normalizeEmail(request.email()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (user.getPasswordHash() == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        session.setAttribute(USER_SESSION_KEY, user.getId());
        return toView(user);
    }

    public void logout(HttpSession session) {
        session.invalidate();
    }

    @Transactional(readOnly = true)
    public UserView current(HttpSession session) {
        UserEntity entity = currentEntity(session);
        return entity == null ? null : toView(entity);
    }

    public String currentUserId(HttpSession session) {
        return (String) session.getAttribute(USER_SESSION_KEY);
    }

    private UserEntity currentEntity(HttpSession session) {
        String userId = currentUserId(session);
        return userId == null ? null : userRepository.findById(userId).orElse(null);
    }

    private UserView toView(UserEntity user) {
        return new UserView(user.getId(), user.getEmail(), user.getDisplayName(), Boolean.TRUE.equals(user.getGuest()));
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
