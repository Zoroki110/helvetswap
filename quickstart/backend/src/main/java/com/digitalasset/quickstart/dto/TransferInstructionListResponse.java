package com.digitalasset.quickstart.dto;

import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;

import java.util.List;

public record TransferInstructionListResponse(
        int count,
        List<TransferInstructionDto> items
) { }

