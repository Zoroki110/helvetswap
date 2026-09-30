package com.digitalasset.quickstart.dto;

import java.util.List;
import java.util.Map;

public record MintTokensResponse(
        List<Map<String, String>> mintedTokens,
        List<String> steps
) {}

