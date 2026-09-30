package com.digitalasset.quickstart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Profile("devnet")
@EnableWebSecurity
@EnableMethodSecurity
@Configuration
public class DevNetSecurityConfig {

  @Value("${clearportx.auth.jwt-secret}")
  private String walletJwtSecret;

  @Value("${clearportx.auth.jwt-issuer:clearportx-backend}")
  private String walletJwtIssuer;

  @Bean
  CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration c = new CorsConfiguration();
    c.setAllowedOrigins(List.of(
      "http://localhost:3000",
      "http://localhost:3001",
      "http://localhost:3002",
      "http://localhost:4001",
      "https://app.clearportx.com",
      "https://clearportxapp.vercel.app",
      "https://devnet.clearportx.com",
      "https://clearportx.com",
      "https://helvetswap.app",
      "https://helvetswap.com"
    ));
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
    // DevNet uses local participant without OAuth2 client
    return Auth.SHARED_SECRET;
  }

  @Bean
  TokenProvider tokenProvider() {
    // DevNet local participant doesn't require authentication
    return () -> "";
  }
}
