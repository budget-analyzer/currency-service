package org.budgetanalyzer.currency.config;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import org.budgetanalyzer.currency.base.JwtTestBuilder;

/**
 * Test security configuration that provides a mock JWT decoder for integration tests.
 *
 * <p>This configuration is automatically imported by {@link TestContainersConfig} which is used by
 * all integration tests via {@code AbstractIntegrationTest}.
 *
 * <p>The mock decoder returns either:
 *
 * <ul>
 *   <li>A custom JWT set via {@code AbstractIntegrationTest.setCustomJwt(Jwt)}
 *   <li>A default test JWT with standard claims (for most tests)
 * </ul>
 *
 * <p>This approach allows tests to bypass actual Auth0 JWT validation while still testing Spring
 * Security authorization logic.
 *
 * @see org.budgetanalyzer.currency.base.AbstractIntegrationTest
 * @see JwtTestBuilder
 */
@TestConfiguration
public class TestSecurityConfig {

  /** ThreadLocal storage for custom JWT tokens shared with AbstractIntegrationTest. */
  public static final ThreadLocal<Jwt> CUSTOM_JWT = new ThreadLocal<>();

  /**
   * Provides a mock JWT decoder that returns test JWTs.
   *
   * <p>The {@code @Primary} annotation ensures this bean takes precedence over any production
   * {@code JwtDecoder} beans. Combined with {@code @ConditionalOnMissingBean} on the production
   * decoder, this ensures only the mock decoder is created in tests.
   *
   * @return mock JWT decoder
   */
  @Bean
  @Primary
  public JwtDecoder jwtDecoder() {
    var mockDecoder = mock(JwtDecoder.class);

    // Return custom JWT if set, otherwise return default JWT
    when(mockDecoder.decode(anyString()))
        .thenAnswer(
            invocation -> {
              Jwt customJwt = CUSTOM_JWT.get();
              return customJwt != null ? customJwt : JwtTestBuilder.defaultJwt();
            });

    return mockDecoder;
  }
}
