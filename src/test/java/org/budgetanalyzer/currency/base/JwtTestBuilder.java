package org.budgetanalyzer.currency.base;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Fluent builder for creating test JWT tokens with custom claims.
 *
 * <p>Provides a readable, type-safe API for constructing JWTs in tests with different users,
 * scopes, roles, and custom claims. Used by {@link AbstractIntegrationTest} and {@link
 * AbstractControllerTest} to support authorization testing.
 *
 * <p><b>Usage Examples:</b>
 *
 * <pre>{@code
 * // Default test user with basic scopes
 * Jwt jwt = JwtTestBuilder.defaultJwt();
 *
 * // Custom user with specific scopes
 * Jwt jwt = JwtTestBuilder.user("john.doe")
 *     .withScopes("read:rates", "write:rates")
 *     .build();
 *
 * // Admin user with all permissions
 * Jwt jwt = JwtTestBuilder.admin()
 *     .withScopes("read:rates", "write:rates", "admin:currencies")
 *     .build();
 *
 * // Custom claims for advanced scenarios
 * Jwt jwt = JwtTestBuilder.user("test-user")
 *     .withClaim("organization_id", "org-123")
 *     .withClaim("email", "test@example.com")
 *     .build();
 * }</pre>
 *
 * <p><b>Thread Safety:</b> This builder is NOT thread-safe. Create a new instance per test.
 *
 * @see AbstractIntegrationTest.JwtTestConfig
 * @see AbstractControllerTest
 */
public class JwtTestBuilder {

  private static final String DEFAULT_SUBJECT = "test-user";
  private static final String DEFAULT_ISSUER = "https://test-issuer.example.com/";
  private static final String DEFAULT_AUDIENCE = "https://test-api.example.com";
  private static final String DEFAULT_SCOPE = "openid profile email";

  private String subject;
  private String scope;
  private String issuer;
  private String audience;
  private Instant issuedAt;
  private Instant expiresAt;
  private final Map<String, Object> customClaims;

  private JwtTestBuilder() {
    this.subject = DEFAULT_SUBJECT;
    this.scope = DEFAULT_SCOPE;
    this.issuer = DEFAULT_ISSUER;
    this.audience = DEFAULT_AUDIENCE;
    this.issuedAt = Instant.now();
    this.expiresAt = Instant.now().plusSeconds(3600); // 1 hour
    this.customClaims = new HashMap<>();
  }

  /**
   * Creates a default test JWT with standard test claims.
   *
   * <p>Default claims:
   *
   * <ul>
   *   <li>Subject: "test-user"
   *   <li>Scopes: "openid profile email"
   *   <li>Issuer: "https://test-issuer.example.com/"
   *   <li>Audience: "https://test-api.example.com"
   *   <li>Issued: now
   *   <li>Expires: 1 hour from now
   * </ul>
   *
   * @return default test JWT
   */
  public static Jwt defaultJwt() {
    return new JwtTestBuilder().build();
  }

  /**
   * Creates a builder for a custom user.
   *
   * @param subject the user subject (username or user ID)
   * @return builder for method chaining
   */
  public static JwtTestBuilder user(String subject) {
    return new JwtTestBuilder().withSubject(subject);
  }

  /**
   * Creates a builder for an admin user with default admin subject.
   *
   * @return builder for method chaining
   */
  public static JwtTestBuilder admin() {
    return new JwtTestBuilder().withSubject("admin-user");
  }

  /**
   * Sets the subject (user identifier) claim.
   *
   * @param subject the user subject
   * @return this builder for method chaining
   */
  public JwtTestBuilder withSubject(String subject) {
    this.subject = subject;
    return this;
  }

  /**
   * Sets the scope claim as a space-separated string.
   *
   * <p>Scopes are converted to Spring Security authorities with "SCOPE_" prefix.
   *
   * @param scopes individual scope values (e.g., "read:rates", "write:rates")
   * @return this builder for method chaining
   */
  public JwtTestBuilder withScopes(String... scopes) {
    this.scope = Arrays.stream(scopes).collect(Collectors.joining(" "));
    return this;
  }

  /**
   * Sets the issuer (iss) claim.
   *
   * @param issuer the token issuer URI
   * @return this builder for method chaining
   */
  public JwtTestBuilder withIssuer(String issuer) {
    this.issuer = issuer;
    return this;
  }

  /**
   * Sets the audience (aud) claim.
   *
   * @param audience the intended audience
   * @return this builder for method chaining
   */
  public JwtTestBuilder withAudience(String audience) {
    this.audience = audience;
    return this;
  }

  /**
   * Sets the issued-at (iat) claim.
   *
   * @param issuedAt the token issue time
   * @return this builder for method chaining
   */
  public JwtTestBuilder withIssuedAt(Instant issuedAt) {
    this.issuedAt = issuedAt;
    return this;
  }

  /**
   * Sets the expiration (exp) claim.
   *
   * @param expiresAt the token expiration time
   * @return this builder for method chaining
   */
  public JwtTestBuilder withExpiresAt(Instant expiresAt) {
    this.expiresAt = expiresAt;
    return this;
  }

  /**
   * Adds a custom claim to the JWT.
   *
   * <p>Useful for testing scenarios that require additional claims beyond the standard ones (sub,
   * scope, iss, aud, iat, exp).
   *
   * @param name the claim name
   * @param value the claim value
   * @return this builder for method chaining
   */
  public JwtTestBuilder withClaim(String name, Object value) {
    this.customClaims.put(name, value);
    return this;
  }

  /**
   * Builds the JWT with all configured claims.
   *
   * @return the constructed JWT
   */
  public Jwt build() {
    Jwt.Builder builder =
        Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .header("typ", "JWT")
            .claim("sub", subject)
            .claim("scope", scope)
            .claim("aud", audience)
            .claim("iss", issuer)
            .issuedAt(issuedAt)
            .expiresAt(expiresAt);

    // Add any custom claims
    customClaims.forEach(builder::claim);

    return builder.build();
  }
}
