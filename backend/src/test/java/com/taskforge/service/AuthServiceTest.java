package com.taskforge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

import com.taskforge.dto.request.LoginRequest;
import com.taskforge.dto.request.LogoutRequest;
import com.taskforge.dto.request.RefreshRequest;
import com.taskforge.dto.request.RegisterRequest;
import com.taskforge.dto.response.UserResponse;
import com.taskforge.exception.AccountLockedException;
import com.taskforge.exception.EmailAlreadyExistsException;
import com.taskforge.exception.InvalidCredentialsException;
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

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

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
    // register
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("should return auth response with tokens and user when email is not taken")
        void shouldRegisterSuccessfully() {
            // Arrange
            var request = new RegisterRequest("alice@example.com", "P@ssw0rd!", "P@ssw0rd!", "Alice");
            var savedUser = mock(User.class);
            var userId = UUID.randomUUID();
            var expectedUserResponse = new UserResponse(userId, "alice@example.com", "Alice");

            when(userRepository.existsByEmail("alice@example.com")).thenReturn(false);
            when(userRepository.save(any(User.class))).thenReturn(savedUser);
            when(savedUser.getId()).thenReturn(userId);
            when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
            when(jwtService.generateRefreshTokenValue()).thenReturn("refresh-value");
            when(jwtService.hashToken("refresh-value")).thenReturn("hashed");
            when(jwtService.getRefreshTokenExpiry()).thenReturn(Duration.ofDays(7));
            when(userMapper.toResponse(savedUser)).thenReturn(expectedUserResponse);
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            var result = authService.register(request);

            // Assert
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-value");
            assertThat(result.user()).isEqualTo(expectedUserResponse);
        }

        @Test
        @DisplayName("should throw EmailAlreadyExistsException when email is already registered")
        void shouldThrowWhenEmailAlreadyExists() {
            // Arrange
            var request = new RegisterRequest("alice@example.com", "P@ssw0rd!", "P@ssw0rd!", "Alice");
            when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(EmailAlreadyExistsException.class);

            verify(userRepository, never()).save(any());
        }
    }

    // ---------------------------------------------------------------------------
    // login
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("login")
    class Login {

        @Test
        @DisplayName("should return auth response and reset failed attempts on valid credentials")
        void shouldLoginSuccessfully() {
            // Arrange
            var request = new LoginRequest("bob@example.com", "correct-password");
            var user = mock(User.class);
            var userId = UUID.randomUUID();
            var expectedUserResponse = new UserResponse(userId, "bob@example.com", "Bob");

            when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));
            when(user.isLocked()).thenReturn(false);
            when(user.getPasswordHash()).thenReturn("hashed-password");
            when(passwordEncoder.matches("correct-password", "hashed-password")).thenReturn(true);
            when(user.getId()).thenReturn(userId);
            when(userRepository.save(user)).thenReturn(user);
            when(jwtService.generateAccessToken(userId)).thenReturn("access-token");
            when(jwtService.generateRefreshTokenValue()).thenReturn("refresh-value");
            when(jwtService.hashToken("refresh-value")).thenReturn("hashed");
            when(jwtService.getRefreshTokenExpiry()).thenReturn(Duration.ofDays(7));
            when(userMapper.toResponse(user)).thenReturn(expectedUserResponse);
            when(refreshTokenRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            var result = authService.login(request, "127.0.0.1");

            // Assert
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.user()).isEqualTo(expectedUserResponse);
            verify(user).resetFailedAttempts();
            verify(loginAuditRepository).save(any());
        }

        @Test
        @DisplayName("should throw InvalidCredentialsException and record failed attempt on wrong password")
        void shouldThrowOnWrongPassword() {
            // Arrange
            var request = new LoginRequest("bob@example.com", "wrong-password");
            var user = mock(User.class);

            when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));
            when(user.isLocked()).thenReturn(false);
            when(user.getPasswordHash()).thenReturn("hashed-password");
            when(passwordEncoder.matches("wrong-password", "hashed-password")).thenReturn(false);
            when(userRepository.save(user)).thenReturn(user);

            // Act & Assert
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1"))
                    .isInstanceOf(InvalidCredentialsException.class);

            verify(user).recordFailedAttempt(5, Duration.ofMinutes(15));
        }

        @Test
        @DisplayName("should throw AccountLockedException when the account is locked")
        void shouldThrowWhenAccountIsLocked() {
            // Arrange
            var request = new LoginRequest("bob@example.com", "any-password");
            var user = mock(User.class);

            when(userRepository.findByEmail("bob@example.com")).thenReturn(Optional.of(user));
            when(user.isLocked()).thenReturn(true);

            // Act & Assert
            assertThatThrownBy(() -> authService.login(request, "127.0.0.1"))
                    .isInstanceOf(AccountLockedException.class);

            verify(passwordEncoder, never()).matches(any(), any());
        }
    }

    // ---------------------------------------------------------------------------
    // refresh
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("refresh")
    class Refresh {

        @Test
        @DisplayName("should return new access token and rotated refresh token when token is valid and not idle")
        void shouldRefreshSuccessfully() {
            // Arrange
            var rawToken = "raw-refresh-token";
            var rawNewToken = "rotated-refresh-token";
            var tokenHash = "hashed-refresh-token";
            var newTokenHash = "hashed-rotated-token";
            var userId = UUID.randomUUID();
            var user = mock(User.class);
            var refreshToken = mock(RefreshToken.class);
            var expectedUserResponse = new UserResponse(userId, "carol@example.com", "Carol");

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(refreshToken));
            when(refreshToken.isInvalidated()).thenReturn(false);
            when(refreshToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(1)));
            when(refreshToken.getLastUsedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(5)));
            when(refreshToken.getUserId()).thenReturn(userId);
            when(refreshTokenRepository.save(refreshToken)).thenReturn(refreshToken);
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(user.getId()).thenReturn(userId);
            when(jwtService.generateAccessToken(userId)).thenReturn("new-access-token");
            when(jwtService.generateRefreshTokenValue()).thenReturn(rawNewToken);
            when(jwtService.hashToken(rawNewToken)).thenReturn(newTokenHash);
            when(jwtService.getRefreshTokenExpiry()).thenReturn(Duration.ofDays(7));
            when(refreshTokenRepository.save(argThat(rt -> rt != refreshToken))).thenAnswer(inv -> inv.getArgument(0));
            when(userMapper.toResponse(user)).thenReturn(expectedUserResponse);

            // Act
            var result = authService.refresh(new RefreshRequest(rawToken));

            // Assert
            assertThat(result.accessToken()).isEqualTo("new-access-token");
            assertThat(result.refreshToken()).isEqualTo(rawNewToken);
            assertThat(result.user()).isEqualTo(expectedUserResponse);
            // Old token must be invalidated and saved; new token is a fresh builder object
            verify(refreshToken).invalidate();
            verify(refreshTokenRepository, times(2)).save(any(RefreshToken.class));
        }

        @Test
        @DisplayName("should throw InvalidTokenException when refresh token has expired")
        void shouldThrowWhenTokenIsExpired() {
            // Arrange
            var rawToken = "expired-refresh-token";
            var tokenHash = "hashed-expired-token";
            var refreshToken = mock(RefreshToken.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(refreshToken));
            when(refreshToken.getExpiresAt()).thenReturn(Instant.now().minus(Duration.ofHours(1)));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);
        }

        @Test
        @DisplayName("should throw InvalidTokenException when refresh token has exceeded idle timeout")
        void shouldThrowWhenTokenIsIdle() {
            // Arrange
            var rawToken = "idle-refresh-token";
            var tokenHash = "hashed-idle-token";
            var refreshToken = mock(RefreshToken.class);

            when(jwtService.hashToken(rawToken)).thenReturn(tokenHash);
            when(refreshTokenRepository.findByTokenHash(tokenHash)).thenReturn(Optional.of(refreshToken));
            when(refreshToken.getExpiresAt()).thenReturn(Instant.now().plus(Duration.ofDays(1)));
            // lastUsedAt is 31 minutes ago — past the 30-minute idle timeout
            when(refreshToken.getLastUsedAt()).thenReturn(Instant.now().minus(Duration.ofMinutes(31)));

            // Act & Assert
            assertThatThrownBy(() -> authService.refresh(new RefreshRequest(rawToken)))
                    .isInstanceOf(InvalidTokenException.class);
        }
    }

    // ---------------------------------------------------------------------------
    // logout
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("logout")
    class Logout {

        @Test
        @DisplayName("should deny the access token and delete all refresh tokens for the user")
        void shouldLogoutSuccessfully() {
            // Arrange
            var accessToken = "valid-access-token";
            var jti = UUID.randomUUID().toString();
            var expiration = Instant.now().plus(Duration.ofMinutes(10));
            var userId = UUID.randomUUID();

            when(jwtService.extractJti(accessToken)).thenReturn(jti);
            when(jwtService.extractExpiration(accessToken)).thenReturn(expiration);
            when(jwtService.extractUserId(accessToken)).thenReturn(userId);

            // Act
            authService.logout(new LogoutRequest(accessToken));

            // Assert
            verify(tokenDenylistRepository).save(any());
            verify(refreshTokenRepository).deleteByUserId(userId);
        }
    }

    // ---------------------------------------------------------------------------
    // getCurrentUser
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("getCurrentUser")
    class GetCurrentUser {

        @Test
        @DisplayName("should return user response when user exists")
        void shouldReturnUserWhenFound() {
            // Arrange
            var userId = UUID.randomUUID();
            var user = mock(User.class);
            var expectedUserResponse = new UserResponse(userId, "dave@example.com", "Dave");

            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userMapper.toResponse(user)).thenReturn(expectedUserResponse);

            // Act
            var result = authService.getCurrentUser(userId);

            // Assert
            assertThat(result).isEqualTo(expectedUserResponse);
        }

        @Test
        @DisplayName("should throw InvalidTokenException when no user matches the given id")
        void shouldThrowWhenUserNotFound() {
            // Arrange
            var userId = UUID.randomUUID();
            when(userRepository.findById(userId)).thenReturn(Optional.empty());

            // Act & Assert
            assertThatThrownBy(() -> authService.getCurrentUser(userId))
                    .isInstanceOf(InvalidTokenException.class);
        }
    }
}
