package com.digitalasset.quickstart.security;

// WARNING: Activating "prod" profile disables ALL @Profile("devnet") beans (35+),
// including DevNetAuthService, RegistryRoutingConfig, SwapPollerService, etc.
// Do NOT switch SPRING_PROFILES_ACTIVE to "prod" until prod-profile equivalents
// exist for all critical services. See TASK queue for details.

import com.digitalasset.quickstart.security.oauth2.AuthClientRegistrationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Profile("prod")
@EnableWebSecurity
@EnableMethodSecurity
@Configuration
public class MainNetSecurityConfig {

  @Value("${clearportx.auth.jwt-secret}")
  private String walletJwtSecret;

  @Value("${clearportx.auth.jwt-issuer:clearportx-backend}")
  private String walletJwtIssuer;

  // CORS allow-list. Exact origins via CORS_ALLOWED_ORIGINS, wildcards via CORS_ALLOWED_ORIGIN_PATTERNS.
  // Defaults cover the canonical prod domains plus Vercel (helvetswap-mainnet project + PR previews).
  @Value("${cors.allowed-origins:https://helvetswap.app,https://www.helvetswap.app,https://helvetswap.com}")
  private String corsAllowedOrigins;

  @Value("${cors.allowed-origin-patterns:https://helvetswap-mainnet*.vercel.app}")
  private String corsAllowedOriginPatterns;

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of(corsAllowedOrigins.split(",")));
    c.setAllowedOriginPatterns(List.of(corsAllowedOriginPatterns.split(",")));
    c.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"));
    c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Idempotency-Key", "X-Request-ID", "X-Party"));
    c.setExposedHeaders(List.of("Retry-After", "X-Request-ID", "Authorization", "X-Total-Count", "X-Rate-Limit-Remaining"));
    c.setAllowCredentials(true);
    c.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
    s.registerCorsConfiguration("/**", c);
    return s;
  }

  @Bean
  SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http
      .cors(Customizer.withDefaults())
      .csrf(csrf -> csrf.disable())
      .authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/challenge").permitAll()
        .requestMatchers("/api/auth/verify").permitAll()
        .requestMatchers("/api/pools").permitAll()
        .requestMatchers("/api/system/**").permitAll()
        .requestMatchers("/actuator/health").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/holding-pools/public").permitAll()
        .requestMatchers(HttpMethod.GET, "/api/swap/inspect").permitAll()
        .requestMatchers("/api/integrator/**").permitAll()
        .anyRequest().authenticated()
      )
      .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(walletJwtDecoder())));

    return http.build();
  }

  @Bean
  JwtDecoder walletJwtDecoder() {
    byte[] keyBytes = walletJwtSecret.getBytes(StandardCharsets.UTF_8);
    SecretKeySpec secretKey = new SecretKeySpec(keyBytes, "HmacSHA256");
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
            .macAlgorithm(MacAlgorithm.HS256)
            .build();
    OAuth2TokenValidator<Jwt> validator =
            walletJwtIssuer == null || walletJwtIssuer.isBlank()
                    ? JwtValidators.createDefault()
                    : JwtValidators.createDefaultWithIssuer(walletJwtIssuer);
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validator));
    return decoder;
  }

  @Bean
  Auth auth() {
    return Auth.SHARED_SECRET;
  }

  @Bean
  TokenProvider tokenProvider() {
    return () -> "";
  }

  @Bean
  @ConditionalOnMissingBean(AuthenticatedUserProvider.class)
  AuthenticatedUserProvider authenticatedUserProvider() {
    return () -> Optional.empty();
  }

  @Bean
  @ConditionalOnMissingBean(UserDetailsManager.class)
  UserDetailsManager userDetailsManager() {
    return new InMemoryUserDetailsManager(
        User.withUsername("prod-admin").password("{noop}disabled").roles("ADMIN").build());
  }

  @Bean
  @ConditionalOnMissingBean(AuthClientRegistrationRepository.class)
  AuthClientRegistrationRepository authClientRegistrationRepository() {
    return new AuthClientRegistrationRepository() {
      @Override public String registerClient(Client client) { return "prod-stub"; }
      @Override public void removeClientRegistration(String t, String c) {}
      @Override public void removeClientRegistrations(String t) {}
      @Override public Collection<Client> getClientRegistrations() { return Collections.emptyList(); }
      @Override public String getLoginLink(String id) { return ""; }
    };
  }
}
