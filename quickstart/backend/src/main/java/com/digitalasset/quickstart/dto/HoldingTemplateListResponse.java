package com.digitalasset.quickstart.dto;

import java.util.List;

public record HoldingTemplateListResponse(
        int count,
        List<HoldingTemplateInfo> items
) {
    public record HoldingTemplateInfo(
            String contractId,
            String instrumentAdmin,
            String instrumentId,
            String packageId,
            String moduleName,
            String entityName
    ) {}
}

