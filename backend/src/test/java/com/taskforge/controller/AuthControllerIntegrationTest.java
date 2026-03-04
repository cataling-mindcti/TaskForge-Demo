package com.taskforge.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskforge.repository.LoginAuditRepository;
import com.taskforge.repository.RefreshTokenRepository;
import com.taskforge.repository.TokenDenylistRepository;
import com.taskforge.repository.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AuthControllerIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private TokenDenylistRepository tokenDenylistRepository;
    @Autowired private LoginAuditRepository loginAuditRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    private String baseUrl() {
        return "http://localhost:" + port + "/api/v1";
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> postWithAuth(String path, String body, String token) throws Exception {
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    private HttpResponse<String> getWithAuth(String path, String token) throws Exception {
        return http.send(
            HttpRequest.newBuilder()
                .uri(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
    }

    @BeforeEach
    void setUp() {
        tokenDenylistRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        loginAuditRepository.deleteAll();
        userRepository.deleteAll();
    }

    // Helper: register a user and return the parsed response
    private JsonNode registerUser(String email, String password, String fullName) throws Exception {
        var body = """
            {
                "email": "%s",
                "password": "%s",
                "confirmPassword": "%s"
                %s
            }
            """.formatted(email, password, password,
                fullName != null ? ", \"fullName\": \"" + fullName + "\"" : "");
        var res = post("/auth/register", body);
        assertThat(res.statusCode()).isEqualTo(201);
        return objectMapper.readTree(res.body());
    }

    // ──────────────────────────────────────────────────────────────
    // US-101: Self-service Registration
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/register")
    class Register {

        @Test
        @DisplayName("should register a new user and return 201 with tokens")
        void shouldRegisterNewUser() throws Exception {
            var res = post("/auth/register", """
                {
                    "email": "alice@example.com",
                    "password": "Secret1234",
                    "confirmPassword": "Secret1234",
                    "fullName": "Alice Smith"
                }
                """);

            assertThat(res.statusCode()).isEqualTo(201);
            var json = objectMapper.readTree(res.body());
            assertThat(json.get("accessToken").asText()).isNotEmpty();
            assertThat(json.get("refreshToken").asText()).isNotEmpty();
            assertThat(json.get("user").get("email").asText()).isEqualTo("alice@example.com");
            assertThat(json.get("user").get("fullName").asText()).isEqualTo("Alice Smith");
            assertThat(json.get("user").get("id").asText()).isNotEmpty();

            // Verify user persisted in database
            var user = userRepository.findByEmail("alice@example.com");
            assertThat(user).isPresent();
            assertThat(user.get().getFullName()).isEqualTo("Alice Smith");

            // Verify login audit recorded
            var audits = loginAuditRepository.findAll();
            assertThat(audits).anyMatch(a -> a.getEmail().equals("alice@example.com") && a.isSuccess());
        }

        @Test
        @DisplayName("should register without full name (optional field)")
        void shouldRegisterWithoutFullName() throws Exception {
            var res = post("/auth/register", """
                {
                    "email": "bob@example.com",
                    "password": "Secret1234",
                    "confirmPassword": "Secret1234"
                }
                """);

            assertThat(res.statusCode()).isEqualTo(201);
            var json = objectMapper.readTree(res.body());
            assertThat(json.get("user").get("email").asText()).isEqualTo("bob@example.com");
            assertThat(json.get("user").get("fullName").isNull()).isTrue();
        }

        @Test
        @DisplayName("should return 409 when email is already registered")
        void shouldRejectDuplicateEmail() throws Exception {
            var body = """
                {
                    "email": "alice@example.com",
                    "password": "Secret1234",
                    "confirmPassword": "Secret1234"
                }
                """;

            assertThat(post("/auth/register", body).statusCode()).isEqualTo(201);
            var res = post("/auth/register", body);
            assertThat(res.statusCode()).isEqualTo(409);
            assertThat(objectMapper.readTree(res.body()).get("detail").asText())
                .isEqualTo("Unable to register with this email");
        }

        @Test
        @DisplayName("should return 400 when password is too weak")
        void shouldRejectWeakPassword() throws Exception {
            var res = post("/auth/register", """
                {"email": "a@b.com", "password": "weak", "confirmPassword": "weak"}
                """);
            assertThat(res.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("should return 400 when passwords do not match")
        void shouldRejectMismatchedPasswords() throws Exception {
            var res = post("/auth/register", """
                {"email": "a@b.com", "password": "Secret1234", "confirmPassword": "Different1"}
                """);
            assertThat(res.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("should return 400 when email is missing")
        void shouldRejectMissingEmail() throws Exception {
            var res = post("/auth/register", """
                {"password": "Secret1234", "confirmPassword": "Secret1234"}
                """);
            assertThat(res.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("should return 400 when email format is invalid")
        void shouldRejectInvalidEmailFormat() throws Exception {
            var res = post("/auth/register", """
                {"email": "not-an-email", "password": "Secret1234", "confirmPassword": "Secret1234"}
                """);
            assertThat(res.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("should return 400 when full name exceeds 100 characters")
        void shouldRejectLongFullName() throws Exception {
            var longName = "A".repeat(101);
            var res = post("/auth/register", """
                {"email": "a@b.com", "password": "Secret1234", "confirmPassword": "Secret1234", "fullName": "%s"}
                """.formatted(longName));
            assertThat(res.statusCode()).isEqualTo(400);
        }

        @Test
        @DisplayName("should auto-login the user after registration (tokens are valid)")
        void shouldAutoLoginAfterRegistration() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            var token = json.get("accessToken").asText();

            var meRes = getWithAuth("/auth/me", token);
            assertThat(meRes.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(meRes.body()).get("email").asText())
                .isEqualTo("alice@example.com");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // US-201: Email and Password Login
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/login")
    class Login {

        @BeforeEach
        void registerTestUser() throws Exception {
            registerUser("alice@example.com", "Secret1234", "Alice Smith");
        }

        @Test
        @DisplayName("should login with valid credentials and return tokens")
        void shouldLoginWithValidCredentials() throws Exception {
            var res = post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """);

            assertThat(res.statusCode()).isEqualTo(200);
            var json = objectMapper.readTree(res.body());
            assertThat(json.get("accessToken").asText()).isNotEmpty();
            assertThat(json.get("refreshToken").asText()).isNotEmpty();
            assertThat(json.get("user").get("email").asText()).isEqualTo("alice@example.com");
            assertThat(json.get("user").get("fullName").asText()).isEqualTo("Alice Smith");
        }

        @Test
        @DisplayName("should return 401 with generic message on wrong password")
        void shouldRejectWrongPassword() throws Exception {
            var res = post("/auth/login", """
                {"email": "alice@example.com", "password": "WrongPassword1"}
                """);

            assertThat(res.statusCode()).isEqualTo(401);
            assertThat(objectMapper.readTree(res.body()).get("detail").asText())
                .isEqualTo("Invalid email or password");
        }

        @Test
        @DisplayName("should return 401 with generic message on non-existent email")
        void shouldRejectNonExistentEmail() throws Exception {
            var res = post("/auth/login", """
                {"email": "nobody@example.com", "password": "Secret1234"}
                """);

            assertThat(res.statusCode()).isEqualTo(401);
            assertThat(objectMapper.readTree(res.body()).get("detail").asText())
                .isEqualTo("Invalid email or password");
        }

        @Test
        @DisplayName("should audit all login attempts with timestamp")
        void shouldAuditLoginAttempts() throws Exception {
            // Failed attempt
            post("/auth/login", """
                {"email": "alice@example.com", "password": "WrongPass1"}
                """);

            // Successful attempt
            post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """);

            var audits = loginAuditRepository.findAll().stream()
                .filter(a -> a.getEmail().equals("alice@example.com"))
                .toList();
            // Registration audit + failed login + successful login = at least 3
            assertThat(audits.size()).isGreaterThanOrEqualTo(3);
            assertThat(audits).anyMatch(a -> !a.isSuccess());
            assertThat(audits).anyMatch(a -> a.isSuccess());
            assertThat(audits).allMatch(a -> a.getCreatedAt() != null);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Account Lockout (US-201 AC4 + SBQ-004, SBQ-006)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Account Lockout")
    class AccountLockout {

        @BeforeEach
        void registerTestUser() throws Exception {
            registerUser("alice@example.com", "Secret1234", null);
        }

        @Test
        @DisplayName("should lock account after 5 consecutive failed login attempts")
        void shouldLockAccountAfter5FailedAttempts() throws Exception {
            for (int i = 0; i < 5; i++) {
                var res = post("/auth/login", """
                    {"email": "alice@example.com", "password": "WrongPass1"}
                    """);
                assertThat(res.statusCode()).isEqualTo(401);
            }

            // 6th attempt should be locked (HTTP 423)
            var res = post("/auth/login", """
                {"email": "alice@example.com", "password": "WrongPass1"}
                """);
            assertThat(res.statusCode()).isEqualTo(423);
            assertThat(objectMapper.readTree(res.body()).get("detail").asText())
                .contains("Too many failed attempts");
        }

        @Test
        @DisplayName("should block even correct password when account is locked")
        void shouldBlockCorrectPasswordWhenLocked() throws Exception {
            for (int i = 0; i < 5; i++) {
                post("/auth/login", """
                    {"email": "alice@example.com", "password": "WrongPass1"}
                    """);
            }

            var res = post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """);
            assertThat(res.statusCode()).isEqualTo(423);
        }

        @Test
        @DisplayName("should reset failed attempts on successful login")
        void shouldResetFailedAttemptsOnSuccess() throws Exception {
            // 4 failed attempts (not enough to lock)
            for (int i = 0; i < 4; i++) {
                post("/auth/login", """
                    {"email": "alice@example.com", "password": "WrongPass1"}
                    """);
            }

            // Successful login resets counter
            assertThat(post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """).statusCode()).isEqualTo(200);

            // 4 more failed attempts should NOT lock (counter was reset)
            for (int i = 0; i < 4; i++) {
                post("/auth/login", """
                    {"email": "alice@example.com", "password": "WrongPass1"}
                    """);
            }

            // Should still be unlocked
            assertThat(post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """).statusCode()).isEqualTo(200);
        }

        @Test
        @DisplayName("should apply lockout globally (per-account, not per-device)")
        void shouldLockGlobally() throws Exception {
            for (int i = 0; i < 5; i++) {
                post("/auth/login", """
                    {"email": "alice@example.com", "password": "WrongPass1"}
                    """);
            }

            var user = userRepository.findByEmail("alice@example.com").orElseThrow();
            assertThat(user.isLocked()).isTrue();
            assertThat(user.getLockedUntil()).isNotNull();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Token Refresh (DP-001, AQ-005)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/refresh")
    class Refresh {

        private String refreshToken;
        private String accessToken;

        @BeforeEach
        void registerAndGetTokens() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            refreshToken = json.get("refreshToken").asText();
            accessToken = json.get("accessToken").asText();
        }

        @Test
        @DisplayName("should issue new access token and rotate refresh token")
        void shouldRefreshTokens() throws Exception {
            var res = post("/auth/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));

            assertThat(res.statusCode()).isEqualTo(200);
            var json = objectMapper.readTree(res.body());
            assertThat(json.get("accessToken").asText()).isNotEmpty();
            assertThat(json.get("refreshToken").asText()).isNotEqualTo(refreshToken);
            assertThat(json.get("user").get("email").asText()).isEqualTo("alice@example.com");
        }

        @Test
        @DisplayName("should reject reuse of old refresh token after rotation (theft detection)")
        void shouldDetectTokenReuse() throws Exception {
            assertThat(post("/auth/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken)).statusCode()).isEqualTo(200);

            // Reuse old token — should be rejected
            assertThat(post("/auth/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken)).statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("should reject invalid refresh token")
        void shouldRejectInvalidRefreshToken() throws Exception {
            assertThat(post("/auth/refresh", """
                {"refreshToken": "completely-invalid-token-value"}
                """).statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("new access token from refresh should work for authenticated endpoints")
        void shouldIssueWorkingAccessToken() throws Exception {
            var res = post("/auth/refresh", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
            var newToken = objectMapper.readTree(res.body()).get("accessToken").asText();

            var meRes = getWithAuth("/auth/me", newToken);
            assertThat(meRes.statusCode()).isEqualTo(200);
            assertThat(objectMapper.readTree(meRes.body()).get("email").asText())
                .isEqualTo("alice@example.com");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Logout (US-203) + Token Denylist
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("POST /api/v1/auth/logout")
    class Logout {

        private String accessToken;
        private String refreshToken;

        @BeforeEach
        void registerAndGetTokens() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            accessToken = json.get("accessToken").asText();
            refreshToken = json.get("refreshToken").asText();
        }

        @Test
        @DisplayName("should return 204 No Content on successful logout")
        void shouldLogoutSuccessfully() throws Exception {
            var res = postWithAuth("/auth/logout",
                "{\"accessToken\": \"%s\"}".formatted(accessToken), accessToken);
            assertThat(res.statusCode()).isEqualTo(204);
        }

        @Test
        @DisplayName("should add access token to denylist after logout")
        void shouldDenylistAccessToken() throws Exception {
            postWithAuth("/auth/logout",
                "{\"accessToken\": \"%s\"}".formatted(accessToken), accessToken);

            assertThat(getWithAuth("/auth/me", accessToken).statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("should delete all refresh tokens for user on logout")
        void shouldDeleteRefreshTokens() throws Exception {
            postWithAuth("/auth/logout",
                "{\"accessToken\": \"%s\"}".formatted(accessToken), accessToken);

            assertThat(post("/auth/refresh",
                "{\"refreshToken\": \"%s\"}".formatted(refreshToken)).statusCode()).isEqualTo(401);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // GET /api/v1/auth/me
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GET /api/v1/auth/me")
    class Me {

        @Test
        @DisplayName("should return current user info when authenticated")
        void shouldReturnCurrentUser() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", "Alice Smith");
            var token = json.get("accessToken").asText();

            var res = getWithAuth("/auth/me", token);
            assertThat(res.statusCode()).isEqualTo(200);
            var user = objectMapper.readTree(res.body());
            assertThat(user.get("email").asText()).isEqualTo("alice@example.com");
            assertThat(user.get("fullName").asText()).isEqualTo("Alice Smith");
            assertThat(user.get("id").asText()).isNotEmpty();
        }

        @Test
        @DisplayName("should return 401 when not authenticated")
        void shouldRejectUnauthenticatedRequest() throws Exception {
            assertThat(get("/auth/me").statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("should return 401 with invalid token")
        void shouldRejectInvalidToken() throws Exception {
            assertThat(getWithAuth("/auth/me", "invalid-jwt-token").statusCode()).isEqualTo(401);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Security Headers (SBQ-008, AQ-004)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Security Headers")
    class SecurityHeaders {

        @Test
        @DisplayName("should include Cache-Control: no-store on authenticated responses")
        void shouldSetCacheControlHeaders() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            var token = json.get("accessToken").asText();

            var res = getWithAuth("/auth/me", token);
            assertThat(res.statusCode()).isEqualTo(200);
            assertThat(res.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        }

        @Test
        @DisplayName("should include Content-Security-Policy: script-src 'self'")
        void shouldSetCSPHeader() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            var res = getWithAuth("/auth/me", json.get("accessToken").asText());
            assertThat(res.headers().firstValue("Content-Security-Policy").orElse(""))
                .isEqualTo("script-src 'self'");
        }

        @Test
        @DisplayName("should include X-Frame-Options: DENY")
        void shouldSetFrameOptionsHeader() throws Exception {
            var res = post("/auth/register", """
                {"email": "a@b.com", "password": "Secret1234", "confirmPassword": "Secret1234"}
                """);
            assertThat(res.headers().firstValue("X-Frame-Options").orElse("")).isEqualTo("DENY");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Route Protection (CR-001)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Route Protection")
    class RouteProtection {

        @Test
        @DisplayName("public auth endpoints should be accessible without token")
        void publicEndpointsShouldBeAccessible() throws Exception {
            assertThat(post("/auth/register", """
                {"email": "test@example.com", "password": "Secret1234", "confirmPassword": "Secret1234"}
                """).statusCode()).isEqualTo(201);

            assertThat(post("/auth/login", """
                {"email": "test@example.com", "password": "Secret1234"}
                """).statusCode()).isEqualTo(200);

            // Refresh with invalid token should be 401, not 403
            assertThat(post("/auth/refresh", """
                {"refreshToken": "invalid"}
                """).statusCode()).isEqualTo(401);
        }

        @Test
        @DisplayName("protected endpoints should return 401 without token")
        void protectedEndpointsShouldRequireAuth() throws Exception {
            assertThat(get("/auth/me").statusCode()).isEqualTo(401);
            assertThat(get("/tasks").statusCode()).isEqualTo(401);
            assertThat(get("/projects").statusCode()).isEqualTo(401);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Password Security (DP-003: bcrypt)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Password Security")
    class PasswordSecurity {

        @Test
        @DisplayName("should store password as bcrypt hash, never in plain text")
        void shouldHashPassword() throws Exception {
            registerUser("alice@example.com", "Secret1234", null);

            var user = userRepository.findByEmail("alice@example.com").orElseThrow();
            assertThat(user.getPasswordHash()).startsWith("$2a$");
            assertThat(user.getPasswordHash()).isNotEqualTo("Secret1234");
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Concurrent Sessions (SBQ-007)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Concurrent Sessions")
    class ConcurrentSessions {

        @Test
        @DisplayName("should allow multiple concurrent sessions for same user")
        void shouldAllowMultipleSessions() throws Exception {
            registerUser("alice@example.com", "Secret1234", null);

            var login1 = objectMapper.readTree(post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """).body());
            var login2 = objectMapper.readTree(post("/auth/login", """
                {"email": "alice@example.com", "password": "Secret1234"}
                """).body());

            assertThat(getWithAuth("/auth/me", login1.get("accessToken").asText()).statusCode())
                .isEqualTo(200);
            assertThat(getWithAuth("/auth/me", login2.get("accessToken").asText()).statusCode())
                .isEqualTo(200);
        }
    }

    // ──────────────────────────────────────────────────────────────
    // User ID Format (DP-004: UUID)
    // ──────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("User ID Format")
    class UserIdFormat {

        @Test
        @DisplayName("should use UUID for user ID")
        void shouldUseUuidForUserId() throws Exception {
            var json = registerUser("alice@example.com", "Secret1234", null);
            var userId = json.get("user").get("id").asText();
            assertThat(userId).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        }
    }
}
