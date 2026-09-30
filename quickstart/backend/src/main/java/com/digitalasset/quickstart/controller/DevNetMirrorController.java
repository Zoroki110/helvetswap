package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.PayoutRequest;
import com.digitalasset.quickstart.dto.PayoutResponse;
import com.digitalasset.quickstart.dto.TransferInstructionListResponse;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.AcceptOfferResult;
import com.digitalasset.quickstart.service.CbtcTransferOfferService.CbtcTransferOfferDto;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
@Profile("devnet")
public class DevNetMirrorController {

    private final DevNetController devNetController;

    public DevNetMirrorController(final DevNetController devNetController) {
        this.devNetController = devNetController;
    }

    /**
     * GET /api/cbtc/offers (agnostic surface)
     */
    @GetMapping("/cbtc/offers")
    public CompletableFuture<ResponseEntity<List<CbtcTransferOfferDto>>> getCbtcOffers(
            @RequestParam(value = "receiverParty") String receiverParty,
            @RequestHeader(value = "X-Party", required = false) String xPartyHeader,
            @RequestHeader(value = "Authorization", required = false) String authHeader
    ) {
        return devNetController.getCbtcOffers(receiverParty, xPartyHeader, authHeader)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * POST /api/cbtc/offers/{offerCid}/accept (agnostic surface)
     */
    @PostMapping("/cbtc/offers/{offerCid}/accept")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public CompletionStage<ResponseEntity<AcceptOfferResult>> acceptCbtcOffer(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable("offerCid") String offerCid,
            @RequestBody(required = false) DevNetController.CbtcAcceptRequest body
    ) {
        return devNetController.acceptCbtcOffer(jwt, offerCid, body)
                .thenApply(response -> ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC));
    }

    /**
     * POST /api/payout/amulet (agnostic surface)
     */
    @PostMapping("/payout/amulet")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<ApiResponse<PayoutResponse>> payoutAmulet(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PayoutRequest request,
            HttpServletRequest httpRequest
    ) {
        ResponseEntity<ApiResponse<PayoutResponse>> response = devNetController.payoutAmulet(jwt, request, httpRequest);
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC);
    }

    /**
     * POST /api/payout/cbtc (agnostic surface)
     */
    @PostMapping("/payout/cbtc")
    @PreAuthorize("@partyGuard.isPoolParty(#jwt)")
    public ResponseEntity<ApiResponse<PayoutResponse>> payoutCbtc(
            @AuthenticationPrincipal Jwt jwt,
            @RequestBody PayoutRequest request,
            HttpServletRequest httpRequest
    ) {
        ResponseEntity<ApiResponse<PayoutResponse>> response = devNetController.payoutCbtc(jwt, request, httpRequest);
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC);
    }

    /**
     * GET /api/transfer-instructions/outgoing (agnostic surface)
     */
    @GetMapping("/transfer-instructions/outgoing")
    public ResponseEntity<ApiResponse<TransferInstructionListResponse>> listOutgoing(
            @RequestParam("senderParty") String senderParty,
            @RequestParam("instrumentAdmin") String instrumentAdmin,
            @RequestParam("instrumentId") String instrumentId,
            HttpServletRequest httpRequest
    ) {
        ResponseEntity<ApiResponse<TransferInstructionListResponse>> response =
                devNetController.listOutgoing(senderParty, instrumentAdmin, instrumentId, httpRequest);
        return ApiSurfaceHeaders.withSurface(response, ApiSurfaceHeaders.AGNOSTIC);
    }
}

