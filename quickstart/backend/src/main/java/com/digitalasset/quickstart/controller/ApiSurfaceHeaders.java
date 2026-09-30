package com.digitalasset.quickstart.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

public final class ApiSurfaceHeaders {
    public static final String HEADER = "X-ClearportX-Api-Surface";
    public static final String AGNOSTIC = "agnostic";
    public static final String DEVNET = "devnet";

    private ApiSurfaceHeaders() {
    }

    public static <T> ResponseEntity<T> withSurface(ResponseEntity<T> response, String surface) {
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(HEADER, surface);
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }
}

