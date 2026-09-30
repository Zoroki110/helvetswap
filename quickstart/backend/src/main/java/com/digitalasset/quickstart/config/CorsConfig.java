package com.digitalasset.quickstart.config;

import org.springframework.context.annotation.Configuration;

// TEMPORARILY DISABLED to avoid CORS conflicts
// Multiple CORS configurations were causing "multiple values" error
// Using WebSecurityConfig CORS configuration instead
// @Configuration
public class CorsConfig {
    /*
    @Bean
    public CorsFilter corsFilter() {
        // Moved to WebSecurityConfig
    }
    */
}
