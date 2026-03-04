package com.taskforge.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.taskforge.repository.TokenDenylistRepository;
import com.taskforge.service.JwtService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtService jwtService;
    @Mock private TokenDenylistRepository tokenDenylistRepository;
    @Mock private FilterChain filterChain;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    @InjectMocks private JwtAuthenticationFilter filter;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------
    // doFilterInternal
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("doFilterInternal")
    class DoFilterInternal {

        @Test
        @DisplayName("should populate SecurityContext with user ID when token is valid and not denylisted")
        void shouldPopulateSecurityContextForValidToken() throws Exception {
            // Arrange
            var userId = UUID.randomUUID();
            var jti = UUID.randomUUID().toString();
            var token = "valid.jwt.token";

            var claims = mock(Claims.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
            when(jwtService.parseAccessToken(token)).thenReturn(claims);
            when(claims.getId()).thenReturn(jti);
            when(claims.getSubject()).thenReturn(userId.toString());
            when(tokenDenylistRepository.existsByTokenJti(jti)).thenReturn(false);

            // Act
            filter.doFilterInternal(request, response, filterChain);

            // Assert
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isInstanceOf(JwtAuthenticationToken.class);
            assertThat(authentication.getPrincipal()).isEqualTo(userId);
            assertThat(authentication.isAuthenticated()).isTrue();
            verify(filterChain).doFilter(request, response);
        }

        @Test
        @DisplayName("should NOT populate SecurityContext when token is on the denylist")
        void shouldNotPopulateSecurityContextForDenylistedToken() throws Exception {
            // Arrange
            var jti = UUID.randomUUID().toString();
            var token = "denylisted.jwt.token";

            var claims = mock(Claims.class);
            when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
            when(jwtService.parseAccessToken(token)).thenReturn(claims);
            when(claims.getId()).thenReturn(jti);
            when(tokenDenylistRepository.existsByTokenJti(jti)).thenReturn(true);

            // Act
            filter.doFilterInternal(request, response, filterChain);

            // Assert
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNull();
            verify(filterChain).doFilter(request, response);
            verify(claims, never()).getSubject();
        }

        @Test
        @DisplayName("should NOT populate SecurityContext when token is expired or invalid")
        void shouldNotPopulateSecurityContextForInvalidToken() throws Exception {
            // Arrange
            var token = "expired.or.invalid.token";
            when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
            when(jwtService.parseAccessToken(token)).thenThrow(new RuntimeException("JWT expired"));

            // Act
            filter.doFilterInternal(request, response, filterChain);

            // Assert
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNull();
            // The filter must still continue the chain even after a parse failure
            verify(filterChain).doFilter(request, response);
            verify(tokenDenylistRepository, never()).existsByTokenJti(any());
        }

        @Test
        @DisplayName("should pass through without authentication when Authorization header is absent")
        void shouldPassThroughWhenNoAuthorizationHeader() throws Exception {
            // Arrange
            when(request.getHeader("Authorization")).thenReturn(null);

            // Act
            filter.doFilterInternal(request, response, filterChain);

            // Assert
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNull();
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtService, tokenDenylistRepository);
        }

        @Test
        @DisplayName("should pass through without authentication when Authorization header does not start with Bearer")
        void shouldPassThroughWhenHeaderIsNotBearer() throws Exception {
            // Arrange
            when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

            // Act
            filter.doFilterInternal(request, response, filterChain);

            // Assert
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertThat(authentication).isNull();
            verify(filterChain).doFilter(request, response);
            verifyNoInteractions(jwtService, tokenDenylistRepository);
        }
    }

    // ---------------------------------------------------------------------------
    // shouldNotFilter
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("shouldNotFilter")
    class ShouldNotFilter {

        @Test
        @DisplayName("should skip filter for the register endpoint")
        void shouldSkipFilterForRegisterEndpoint() throws Exception {
            // Arrange
            when(request.getRequestURI()).thenReturn("/api/v1/auth/register");

            // Act
            var result = filter.shouldNotFilter(request);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should skip filter for the login endpoint")
        void shouldSkipFilterForLoginEndpoint() throws Exception {
            // Arrange
            when(request.getRequestURI()).thenReturn("/api/v1/auth/login");

            // Act
            var result = filter.shouldNotFilter(request);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should skip filter for the token refresh endpoint")
        void shouldSkipFilterForRefreshEndpoint() throws Exception {
            // Arrange
            when(request.getRequestURI()).thenReturn("/api/v1/auth/refresh");

            // Act
            var result = filter.shouldNotFilter(request);

            // Assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("should NOT skip filter for protected endpoints")
        void shouldNotSkipFilterForProtectedEndpoint() throws Exception {
            // Arrange
            when(request.getRequestURI()).thenReturn("/api/v1/tasks");

            // Act
            var result = filter.shouldNotFilter(request);

            // Assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("should NOT skip filter for partial matches on public paths")
        void shouldNotSkipFilterForPartialPathMatch() throws Exception {
            // Arrange — a path that contains a public path as prefix but is not equal to it
            when(request.getRequestURI()).thenReturn("/api/v1/auth/login/extra");

            // Act
            var result = filter.shouldNotFilter(request);

            // Assert
            assertThat(result).isFalse();
        }
    }
}
