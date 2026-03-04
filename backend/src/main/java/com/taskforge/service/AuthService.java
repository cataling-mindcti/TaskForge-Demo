package com.taskforge.service;

import com.taskforge.dto.request.LoginRequest;
import com.taskforge.dto.request.LogoutRequest;
import com.taskforge.dto.request.RefreshRequest;
import com.taskforge.dto.request.RegisterRequest;
import com.taskforge.dto.response.AuthResponse;
import com.taskforge.dto.response.UserResponse;
import com.taskforge.exception.AccountLockedException;
import com.taskforge.exception.EmailAlreadyExistsException;
import com.taskforge.exception.InvalidCredentialsException;
import com.taskforge.exception.InvalidTokenException;
import com.taskforge.mapper.UserMapper;
import com.taskforge.model.LoginAudit;
import com.taskforge.model.RefreshToken;
import com.taskforge.model.TokenDenylistEntry;
import com.taskforge.model.User;
import com.taskforge.repository.LoginAuditRepository;
import com.taskforge.repository.RefreshTokenRepository;
import com.taskforge.repository.TokenDenylistRepository;
import com.taskforge.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final LoginAuditRepository loginAuditRepository;
    private final TokenDenylistRepository tokenDenylistRepository;
    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final BCryptPasswordEncoder passwordEncoder;

    @Value("${app.auth.max-failed-attempts}")
    private int maxFailedAttempts;

    @Value("${app.auth.lockout-duration}")
    private Duration lockoutDuration;

    @Value("${app.jwt.idle-timeout}")
    private Duration idleTimeout;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new EmailAlreadyExistsException(request.email());
        }

        var user = User.builder()
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .fullName(request.fullName())
                .build();

        user = userRepository.save(user);
        log.info("User registered: {}", user.getId());

        var authResponse = generateTokens(user);
        auditLogin(request.email(), true, null, null);
        return authResponse;
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class})
    public AuthResponse login(LoginRequest request, String ipAddress) {
        var user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> {
                    auditLogin(request.email(), false, "User not found", ipAddress);
                    return new InvalidCredentialsException();
                });

        if (user.isLocked()) {
            auditLogin(request.email(), false, "Account locked", ipAddress);
            throw new AccountLockedException();
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            user.recordFailedAttempt(maxFailedAttempts, lockoutDuration);
            userRepository.save(user);
            auditLogin(request.email(), false, "Invalid password", ipAddress);
            throw new InvalidCredentialsException();
        }

        user.resetFailedAttempts();
        userRepository.save(user);

        var authResponse = generateTokens(user);
        auditLogin(request.email(), true, null, ipAddress);
        log.info("User logged in: {}", user.getId());
        return authResponse;
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        var tokenHash = jwtService.hashToken(request.refreshToken());

        var refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(InvalidTokenException::new);

        // Reuse detection: if token was already invalidated, this is a potential theft
        if (refreshToken.isInvalidated()) {
            log.warn("Refresh token reuse detected for user {}. Revoking all tokens.", refreshToken.getUserId());
            refreshTokenRepository.deleteByUserId(refreshToken.getUserId());
            throw new InvalidTokenException();
        }

        if (Instant.now().isAfter(refreshToken.getExpiresAt())) {
            throw new InvalidTokenException();
        }

        if (refreshToken.getLastUsedAt() != null
                && Instant.now().isAfter(refreshToken.getLastUsedAt().plus(idleTimeout))) {
            throw new InvalidTokenException();
        }

        // Rotation: invalidate old token, issue new one
        refreshToken.invalidate();
        refreshTokenRepository.save(refreshToken);

        var user = userRepository.findById(refreshToken.getUserId())
                .orElseThrow(InvalidTokenException::new);

        var accessToken = jwtService.generateAccessToken(user.getId());
        var newRefreshTokenValue = jwtService.generateRefreshTokenValue();

        var newRefreshToken = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(jwtService.hashToken(newRefreshTokenValue))
                .expiresAt(Instant.now().plus(jwtService.getRefreshTokenExpiry()))
                .build();
        newRefreshToken.updateLastUsedAt();
        refreshTokenRepository.save(newRefreshToken);

        log.info("Token refreshed with rotation for user: {}", user.getId());

        return new AuthResponse(accessToken, newRefreshTokenValue, userMapper.toResponse(user));
    }

    @Transactional
    public void logout(LogoutRequest request) {
        var jti = jwtService.extractJti(request.accessToken());
        var expiration = jwtService.extractExpiration(request.accessToken());
        var userId = jwtService.extractUserId(request.accessToken());

        var denylistEntry = TokenDenylistEntry.builder()
                .tokenJti(jti)
                .expiresAt(expiration)
                .build();
        tokenDenylistRepository.save(denylistEntry);

        refreshTokenRepository.deleteByUserId(userId);
        log.info("User logged out: {}", userId);
    }

    @Transactional(readOnly = true)
    public UserResponse getCurrentUser(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(InvalidTokenException::new);
        return userMapper.toResponse(user);
    }

    private AuthResponse generateTokens(User user) {
        var accessToken = jwtService.generateAccessToken(user.getId());
        var refreshTokenValue = jwtService.generateRefreshTokenValue();

        var refreshToken = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(jwtService.hashToken(refreshTokenValue))
                .expiresAt(Instant.now().plus(jwtService.getRefreshTokenExpiry()))
                .build();
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshTokenValue, userMapper.toResponse(user));
    }

    @Scheduled(fixedRate = 3600000) // every hour
    @Transactional
    public void cleanupExpiredTokens() {
        var now = Instant.now();
        int denylistCount = tokenDenylistRepository.deleteExpiredEntries(now);
        int refreshCount = refreshTokenRepository.deleteExpiredTokens(now);
        if (denylistCount > 0 || refreshCount > 0) {
            log.info("Token cleanup: removed {} denylist entries, {} expired refresh tokens", denylistCount, refreshCount);
        }
    }

    private void auditLogin(String email, boolean success, String failureReason, String ipAddress) {
        var audit = LoginAudit.builder()
                .email(email)
                .success(success)
                .failureReason(failureReason)
                .ipAddress(ipAddress)
                .build();
        loginAuditRepository.save(audit);
    }
}
