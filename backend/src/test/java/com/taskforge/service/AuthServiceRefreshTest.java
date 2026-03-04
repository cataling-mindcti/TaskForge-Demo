package com.taskforge.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.taskforge.dto.request.RefreshRequest;
import com.taskforge.dto.response.UserResponse;
import com.taskforge.exception.InvalidTokenException;
import com.taskforge.mapper.UserMapper;
import com.taskforge.model.RefreshToken;
import com.taskforge.model.User;
import com.taskforge.repository.LoginAuditRepository;
import com.taskforge.repository.RefreshTokenRepository;
import com.taskforge.repository.TokenDenylistRepository;
import com.taskforge.repository.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Focused tests for refresh-token rotation and the cleanupExpiredTokens scheduled job.
 * The core happy-path and simple error cases for refresh() are already covered in
 * AuthServiceTest. This class adds the reuse-detection and cleanup scenarios.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceRefreshTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private LoginAuditRepository loginAuditRepository;
    @Mock private TokenDenylistRepository tokenDenylistRepository;
    @Mock private JwtService jwtService;
    @Mock private UserMapper userMapper;
    @Mock private BCryptPasswordEncoder passwordEncoder;

    @InjectMocks private AuthService authService;

    @BeforeEach
    void setUp() throws Exception {
        setField(authService, "maxFailedAttempts", 5);
        setField(authService, "lockoutDuration", Duration.ofMinutes(15));
        setField(authService, "idleTimeout", Duration.ofMinutes(30));
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // ---------------------------------------------------------------------------
    // refresh — rotation and security scenarios
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("refresh — token rotation")
    class RefreshRotation {

        @Test
        @DisplayName("should invalidate old token and issue new refresh token on successful refresh")
        void shouldInvalidateOldTokenAndIssueNewOne() throws Exception {
            // Arrange
            var rawOldToken = "old-refresh-token";
            var rawNewToken = "new-refresh-token";
            var tokenHash = "hashed-old-token";
            var newTokenHash = "hashed-new-token";
            var userId = UUID.randomUUID();
            var user = mock(User.class);
            var oldRefreshToken = mock(RefreshToken.class);
            var userResponse = new UserResponse(userId, "alice@example.com", "Alice");

            when(jwtService.hashToken(rawOldToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(oldRefreshToken));
            when(oldRefreshToken.isInvalidated()).thenReturn(false);
            when(oldRefreshToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(1)));
            when(oldRefreshToken.getLastUsedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(5)));
            when(oldRefreshToken.getUserId()).thenReturn(userId);
            when(refreshTokenRepository.save(oldRefreshToken)).thenReturn(oldRefreshToken);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(user.getId()).thenReturn(userId);
            when(jwtService.generateAccessToken(userId)).thenReturn("new-access-token");
            when(jwtService.generateRefreshTokenValue()).thenReturn(rawNewToken);
            when(jwtService.hashToken(rawNewToken)).thenReturn(newTokenHash);
            when(jwtService.getRefreshTokenExpiry()).thenReturn(Duration.ofDays(7));
            when(refreshTokenRepository.save(argThat(rt -> rt != oldRefreshToken))).thenAnswer(inv -> inv.getArgument(0));
            when(userMapper.toResponse(user)).thenReturn(userResponse);

            // Act
            var result = authService.refresh(new RefreshRequest(rawOldToken));

            // Assert — old token was invalidated
            verify(oldRefreshToken).invalidate();
            verify(refreshTokenRepository).save(oldRefreshToken);

            // Assert — new token was persisted and returned
            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
            assertThat(result.accessToken()).isEqualTo("new-access-token");
            assertThat(result.refreshToken()).isEqualTo(rawNewToken);
            assertThat(result.user()).isEqualTo(userResponse);
        }

        @Test
        @DisplayName("should revoke ALL user tokens and throw when an already-invalidated token is reused")
        void shouldRevokeAllTokensAndThrowOnTokenReuse() {
            // Arrange
            var rawToken = "already-used-refresh-token";
            var tokenHash = "hashed-already-used-token";
            var userId = UUID.randomUUID();
            var stolenToken = mock(RefreshToken.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(stolenToken));
            // The token was already invalidated — this signals potential theft
            when(stolenToken.isInvalidated()).thenReturn(true);
            when(stolenToken.getUserId()).thenReturn(userId);

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);

            // All refresh tokens for the user must be wiped
            verify(refreshTokenRepository).deleteByUserId(userId);
            // No new tokens should be issued
            verify(jwtService, never()).generateAccessToken(any());
            verify(refreshTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("should throw InvalidTokenException when refresh token is not found in the database")
        void shouldThrowWhenTokenNotFound() {
            // Arrange
            var rawToken = "unknown-refresh-token";
            var tokenHash = "hashed-unknown-token";

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);

            verify(jwtService, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("should throw InvalidTokenException when refresh token has passed its expiry timestamp")
        void shouldThrowWhenRefreshTokenIsExpired() {
            // Arrange
            var rawToken = "expired-refresh-token";
            var tokenHash = "hashed-expired-token";
            var expiredToken = mock(RefreshToken.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(expiredToken));
            when(expiredToken.isInvalidated()).thenReturn(false);
            // Token expired one hour ago
            when(expiredToken.getExpiresAt()).thenReturn(Instant.now().minus(Duration.ofHours(1)));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);

            verify(jwtService, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("should throw InvalidTokenException when idle timeout has been exceeded")
        void shouldThrowWhenIdleTimeoutExceeded() {
            // Arrange
            var rawToken = "idle-refresh-token";
            var tokenHash = "hashed-idle-token";
            var idleToken = mock(RefreshToken.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(idleToken));
            when(idleToken.isInvalidated()).thenReturn(false);
            when(idleToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
            // Last used 31 minutes ago — exceeds the 30-minute idle timeout
            when(idleToken.getLastUsedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(31)));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);

            verify(jwtService, never()).generateAccessToken(any());
        }

        @Test
        @DisplayName("should NOT throw when last-used-at is null (token has never been used before)")
        void shouldNotThrowWhenLastUsedAtIsNull() {
            // Arrange — a freshly created token has null lastUsedAt; idle check must be skipped
            var rawToken = "fresh-refresh-token";
            var tokenHash = "hashed-fresh-token";
            var newToken = mock(RefreshToken.class);
            var userId = UUID.randomUUID();
            var user = mock(User.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(newToken));
            when(newToken.isInvalidated()).thenReturn(false);
            when(newToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(7)));
            when(newToken.getLastUsedAt()).thenReturn(null);
            when(newToken.getUserId()).thenReturn(userId);
            when(refreshTokenRepository.save(newToken)).thenReturn(newToken);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(user.getId()).thenReturn(userId);
            when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
            when(jwtService.generateRefreshTokenValue()).thenReturn("brand-new-refresh");
            when(jwtService.hashToken("brand-new-refresh")).thenReturn("hashed-brand-new");
            when(jwtService.getRefreshTokenExpiry()).thenReturn(Duration.ofDays(7));
            when(refreshTokenRepository.save(argThat(rt -> rt != newToken))).thenAnswer(inv -> inv.getArgument(0));
            when(userMapper.toResponse(user)).thenReturn(new UserResponse(userId, "bob@example.com", "Bob"));

            // Act & Assert — must not throw
            var result = authService.refresh(new RefreshRequest(rawToken));
            assertThat(result.accessToken()).isEqualTo("access-token");
        }
    }

    // ---------------------------------------------------------------------------
    // cleanupExpiredTokens
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("cleanupExpiredTokens")
    class CleanupExpiredTokens {

        @Test
        @DisplayName("should call deleteExpiredEntries and deleteExpiredTokens with the current time")
        void shouldDelegateToRepositoriesForCleanup() {
            // Arrange
            when(tokenDenylistRepository.deleteExpiredEntries(any(Instant.class))).thenReturn(3);
            when(refreshTokenRepository.deleteExpiredTokens(any(Instant.class))).thenReturn(5);

            // Act
            authService.cleanupExpiredTokens();

            // Assert
            verify(tokenDenylistRepository).deleteExpiredEntries(any(Instant.class));
            verify(refreshTokenRepository).deleteExpiredTokens(any(Instant.class));
        }

        @Test
        @DisplayName("should complete without error when there are no expired tokens to remove")
        void shouldCompleteSuccessfullyWhenNothingToDelete() {
            // Arrange
            when(tokenDenylistRepository.deleteExpiredEntries(any(Instant.class))).thenReturn(0);
            when(refreshTokenRepository.deleteExpiredTokens(any(Instant.class))).thenReturn(0);

            // Act — must not throw
            authService.cleanupExpiredTokens();

            // Assert
            verify(tokenDenylistRepository).deleteExpiredEntries(any(Instant.class));
            verify(refreshTokenRepository).deleteExpiredTokens(any(Instant.class));
        }
    }
}
