package com.taskforge.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskforge.security.JwtAuthenticationToken;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JpaConfigTest {

    private final JpaConfig jpaConfig = new JpaConfig();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------------
    // auditorAware
    // ---------------------------------------------------------------------------

    @Nested
    @DisplayName("auditorAware")
    class AuditorAware {

        @Test
        @DisplayName("should return the user UUID as a string when a JwtAuthenticationToken is present in the SecurityContext")
        void shouldReturnUserIdWhenJwtAuthenticationTokenIsPresent() {
            // Arrange
            var userId = UUID.randomUUID();
            var authentication = new JwtAuthenticationToken(userId);
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Act
            var auditor = jpaConfig.auditorAware();
            var result = auditor.getCurrentAuditor();

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo(userId.toString());
        }

        @Test
        @DisplayName("should return 'system' when no authentication is present in the SecurityContext")
        void shouldReturnSystemWhenNoAuthenticationIsPresent() {
            // Arrange — SecurityContext is empty (cleared by @AfterEach)

            // Act
            var auditor = jpaConfig.auditorAware();
            var result = auditor.getCurrentAuditor();

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("system");
        }

        @Test
        @DisplayName("should return 'system' when a non-JWT authentication type is present in the SecurityContext")
        void shouldReturnSystemWhenNonJwtAuthenticationIsPresent() {
            // Arrange — set an authentication that is NOT a JwtAuthenticationToken
            var otherAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "someUser", "somePassword");
            SecurityContextHolder.getContext().setAuthentication(otherAuth);

            // Act
            var auditor = jpaConfig.auditorAware();
            var result = auditor.getCurrentAuditor();

            // Assert
            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("system");
        }
    }
}
