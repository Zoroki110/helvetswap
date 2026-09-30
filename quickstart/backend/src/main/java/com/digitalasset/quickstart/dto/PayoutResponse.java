package com.digitalasset.quickstart.dto;

public record PayoutResponse(
        String cid,
        String ledgerUpdateId,
        String executeBefore,
        String sender,
        String receiver,
        String instrumentAdmin,
        String instrumentId,
        String amount,
        String memo,
        String factoryId,
        Integer disclosedContractsCount
) {}

