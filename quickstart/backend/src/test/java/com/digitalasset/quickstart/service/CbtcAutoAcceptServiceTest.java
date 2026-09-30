// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController.AcceptRequest;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController.AcceptWithContextResponse;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionDto;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class CbtcAutoAcceptServiceTest {

    private static final String OPERATOR = "ClearportX-DEX-1::1220abc";

    private TransferInstructionAcsQueryService tiQuery;
    private DevNetTransferInstructionController acceptController;
    private AuthUtils authUtils;
    private CbtcAutoAcceptService service;

    @BeforeEach
    void setUp() {
        tiQuery = mock(TransferInstructionAcsQueryService.class);
        acceptController = mock(DevNetTransferInstructionController.class);
        authUtils = mock(AuthUtils.class);
        when(authUtils.getAppProviderPartyId()).thenReturn(OPERATOR);
        service = new CbtcAutoAcceptService(tiQuery, acceptController, authUtils, "0.00000001");
    }

    private TransferInstructionDto ti(String cid, String amount, String instrumentId, String receiver) {
        return new TransferInstructionDto(
                cid, "sender::1220xyz", receiver, "cbtc-network::1220abc",
                instrumentId, amount, Instant.now().plusSeconds(3600)
        );
    }

    @Test
    void acceptsEligibleCbtcInbound() {
        TransferInstructionDto inbound = ti("00abc...001", "0.001", "CBTC", OPERATOR);
        when(tiQuery.listForReceiver(OPERATOR)).thenReturn(Result.ok(List.of(inbound)));
        when(acceptController.acceptWithContext(any(AcceptRequest.class)))
                .thenReturn(ResponseEntity.ok(new AcceptWithContextResponse(true, "update-id-1", "newCid", null)));

        service.pollAndAcceptCbtcInbound();

        verify(acceptController, times(1)).acceptWithContext(any(AcceptRequest.class));
    }

    @Test
    void skipsDustBelowMinAmount() {
        TransferInstructionDto dust = ti("00dust", "0.000000001", "CBTC", OPERATOR);
        when(tiQuery.listForReceiver(OPERATOR)).thenReturn(Result.ok(List.of(dust)));

        service.pollAndAcceptCbtcInbound();

        verify(acceptController, never()).acceptWithContext(any(AcceptRequest.class));
    }

    @Test
    void skipsNonCbtcInstruments() {
        TransferInstructionDto amulet = ti("00amulet", "1.0", "Amulet", OPERATOR);
        when(tiQuery.listForReceiver(OPERATOR)).thenReturn(Result.ok(List.of(amulet)));

        service.pollAndAcceptCbtcInbound();

        verify(acceptController, never()).acceptWithContext(any(AcceptRequest.class));
    }

    @Test
    void skipsOutboundTransfersWhereOperatorIsSender() {
        TransferInstructionDto outbound = ti("00out", "1.0", "CBTC", "other::1220zzz");
        when(tiQuery.listForReceiver(OPERATOR)).thenReturn(Result.ok(List.of(outbound)));

        service.pollAndAcceptCbtcInbound();

        verify(acceptController, never()).acceptWithContext(any(AcceptRequest.class));
    }

    @Test
    void tolerantOfAcceptFailures() {
        TransferInstructionDto inbound = ti("00abc...002", "0.001", "CBTC", OPERATOR);
        when(tiQuery.listForReceiver(OPERATOR)).thenReturn(Result.ok(List.of(inbound)));
        when(acceptController.acceptWithContext(any(AcceptRequest.class)))
                .thenReturn(ResponseEntity.ok(new AcceptWithContextResponse(false, null, null, "already-archived")));

        // Should not throw despite failed accept
        assertDoesNotThrow(() -> service.pollAndAcceptCbtcInbound());
    }

    @Test
    void skipsCycleIfOperatorPartyMissing() {
        when(authUtils.getAppProviderPartyId()).thenReturn(null);

        service.pollAndAcceptCbtcInbound();

        verify(tiQuery, never()).listForReceiver(any());
    }
}
