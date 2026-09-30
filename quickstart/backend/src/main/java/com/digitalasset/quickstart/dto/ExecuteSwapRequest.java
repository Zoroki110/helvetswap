// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * ExecuteSwapRequest - Request to execute a prepared swap
 */
public class ExecuteSwapRequest {
    @NotBlank(message = "swapReadyCid is required")
    public String swapReadyCid;

    // Default constructor for Jackson
    public ExecuteSwapRequest() {}

    public ExecuteSwapRequest(String swapReadyCid) {
        this.swapReadyCid = swapReadyCid;
    }
}
