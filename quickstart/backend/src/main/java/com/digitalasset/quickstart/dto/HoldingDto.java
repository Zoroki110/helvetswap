package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;
import java.util.Map;

public record HoldingDto(
        String instrumentId,
        String symbol,
        String name,
        String description,
        BigDecimal amount,
        Integer decimals,
        String tokenType,
        String currencyCode,
        String registry,
        String logoUri,
        Map<String, String> attributes,
        String metadataHash,
        String metadataVersion
) { }
