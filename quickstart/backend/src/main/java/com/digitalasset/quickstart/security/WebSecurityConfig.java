// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Web security configuration for Canton Network devnet deployment.
 *
 * Features:
 * 1. Basic Auth protection for actuator endpoints (/actuator/**)
 * 2. CORS configuration for frontend integration
 * 3. OAuth2 JWT authentication for API endpoints (existing)
 *
 * Only activated when security.actuator.username and password are configured.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@ConditionalOnProperty(name = "security.actuator.username")
public class WebSecurityConfig {

    @Value("${security.actuator.username}")
    private String actuatorUsername;

    @Value("${security.actuator.password}")
    private String actuatorPassword;

    @Value("${cors.allowed-origins:http://localhost:3000,http://localhost:3001,http://localhost:4001}")
    private String allowedOrigins;

    @Value("${cors.allowed-methods:GET,POST,PUT,DELETE,OPTIONS}")
    private String allowedMethods;

    @Value("${cors.allowed-headers:Authorization,Content-Type,X-Idempotency-Key,X-Request-ID}")
    private String allowedHeaders;

    @Value("${cors.exposed-headers:Retry-After,X-Request-ID}")
    private String exposedHeaders;

    @Value("${cors.allow-credentials:true}")
    private boolean allowCredentials;

    @Value("${cors.max-age:3600}")
    private long maxAge;

    @Value("${clearportx.auth.jwt-secret}")
    private String walletJwtSecret;

    @Value("${clearportx.auth.jwt-issuer:clearportx-backend}")
    private String walletJwtIssuer;

    /**
     * Security filter chain for actuator endpoints.
     * Higher precedence (Order 1) to apply Basic Auth before OAuth2.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/actuator/**")
            .authorizeHttpRequests(authz -> authz
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable());  // Actuator endpoints don't need CSRF

        return http.build();
    }

    /**
     * Security filter chain for API endpoints.
     * Lower precedence (Order 2) - uses OAuth2 JWT from existing config.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder walletJwtDecoder) throws Exception {
        http
            .securityMatcher("/api/**")
            .cors(Customizer.withDefaults())  // Enable CORS for API endpoints
            .authorizeHttpRequests(authz -> authz
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(walletJwtDecoder)))
            .csrf(csrf -> csrf.disable());  // API uses JWT, not session-based auth

        return http.build();
    }

    /**
     * User details service for Basic Auth (actuator only).
     */
    @Bean
    public UserDetailsService userDetailsService() {
        UserDetails user = User.builder()
            .username(actuatorUsername)
            .password(passwordEncoder().encode(actuatorPassword))
            .roles("ACTUATOR")
            .build();

        return new InMemoryUserDetailsManager(user);
    }

    /**
     * Password encoder for Basic Auth credentials.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CORS configuration for frontend integration.
     *
     * Strict configuration:
     * - Explicit origins (no wildcards)
     * - Limited HTTP methods (GET, POST, PUT, DELETE, OPTIONS)
     * - Specific allowed headers (Authorization, Content-Type, X-Idempotency-Key, X-Request-ID)
     * - Exposed headers (Retry-After, X-Request-ID) for client access
     * - Credentials support for JWT authentication
     * - 1 hour preflight cache
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // Parse comma-separated origins (explicit list, no wildcards)
        List<String> origins = Arrays.asList(allowedOrigins.split(","));
        configuration.setAllowedOrigins(origins);

        // Parse comma-separated methods
        List<String> methods = Arrays.asList(allowedMethods.split(","));
        configuration.setAllowedMethods(methods);

        // Parse comma-separated allowed headers (request headers client can send)
        List<String> headers = Arrays.asList(allowedHeaders.split(","));
        configuration.setAllowedHeaders(headers);

        // Parse comma-separated exposed headers (response headers client can read)
        List<String> exposed = Arrays.asList(exposedHeaders.split(","));
        configuration.setExposedHeaders(exposed);

        // Allow credentials (cookies, authorization headers)
        configuration.setAllowCredentials(allowCredentials);

        // Preflight cache duration (in seconds)
        configuration.setMaxAge(maxAge);

        // Apply CORS to all API endpoints
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);

        return source;
    }

    @Bean
    public JwtDecoder walletJwtDecoder() {
        byte[] keyBytes = walletJwtSecret != null ? walletJwtSecret.getBytes(StandardCharsets.UTF_8) : new byte[0];
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

}
