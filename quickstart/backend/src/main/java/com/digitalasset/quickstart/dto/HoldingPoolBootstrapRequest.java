package com.digitalasset.quickstart.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class HoldingPoolBootstrapRequest {
    @NotBlank(message = "tiCidA is required")
    public String tiCidA;
    @NotBlank(message = "tiCidB is required")
    public String tiCidB;
    @NotNull(message = "amountA is required")
    public BigDecimal amountA;
    @NotNull(message = "amountB is required")
    public BigDecimal amountB;
    public String lpProvider;

    public HoldingPoolBootstrapRequest() {}
}

