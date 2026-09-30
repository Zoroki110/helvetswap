// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Request ID filter - generates/extracts X-Request-ID for correlation.
 *
 * - Extracts X-Request-ID from request header (if present)
 * - Generates UUID if missing
 * - Adds to MDC for correlated logging
 * - Returns in response header
 *
 * Order: 1 (runs before security filters)
 */
@Component
@Order(1)
public class RequestIdFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-ID";
    private static final String MDC_KEY = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        // Get or generate request ID
        String requestId = req.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }

        // Add to MDC for logging
        MDC.put(MDC_KEY, requestId);

        try {
            // Add to response header
            res.setHeader(REQUEST_ID_HEADER, requestId);

            // Continue filter chain
            chain.doFilter(request, response);
        } finally {
            // Clean up MDC
            MDC.remove(MDC_KEY);
        }
    }
}
