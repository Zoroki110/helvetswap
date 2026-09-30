package com.digitalasset.quickstart.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Blocks all debug endpoints when not in "local" profile.
 * Returns 404 (not 403) to avoid revealing endpoint existence.
 */
@RestController
@Profile("!local")
public class DebugEndpointBlocker {

    @RequestMapping({"/api/debug/**", "/api/clearportx/debug/**"})
    public void blockDebug(HttpServletResponse response) {
        response.setStatus(HttpStatus.NOT_FOUND.value());
    }
}
