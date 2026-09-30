// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController.AcceptRequest;
import com.digitalasset.quickstart.controller.DevNetTransferInstructionController.AcceptWithContextResponse;
import com.digitalasset.quickstart.security.AuthUtils;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Auto-accepts incoming CBTC TransferOffers where the operator is receiver.
 *
 * <p>The deployed Accept choice on {@code Utility.Registry.App.V0.Model.Transfer:TransferOffer}
 * (utility-registry-app-v0-0.7.0 dalf $$sc_TransferOffer_8) dereferences
 * {@code ExtraArgs.context} to look up a {@code TransferRule} via
 * {@code transferRuleContextKey}. The registry-context fetch and disclosed-contracts
 * assembly are non-trivial; this service reuses
 * {@link DevNetTransferInstructionController#acceptWithContext} instead of
 * re-implementing the flow.
 *
 * <p>Default-OFF via property {@code clearportx.cbtc-auto-accept.enabled}. Operator
 * flips ON post-deploy to begin auto-accepting inbound CBTC.
 */
@Service
@ConditionalOnProperty(
        name = "clearportx.cbtc-auto-accept.enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class CbtcAutoAcceptService {

    private static final Logger LOG = LoggerFactory.getLogger(CbtcAutoAcceptService.class);
    private static final String CBTC_INSTRUMENT_ID = "CBTC";

    private final TransferInstructionAcsQueryService tiQuery;
    private final DevNetTransferInstructionController acceptController;
    private final AuthUtils authUtils;
    private final BigDecimal minAmount;

    public CbtcAutoAcceptService(
            TransferInstructionAcsQueryService tiQuery,
            DevNetTransferInstructionController acceptController,
            AuthUtils authUtils,
            @Value("${clearportx.cbtc-auto-accept.min-amount:0.00000001}") String minAmountStr
    ) {
        this.tiQuery = tiQuery;
        this.acceptController = acceptController;
        this.authUtils = authUtils;
        this.minAmount = new BigDecimal(minAmountStr);
    }

    @Scheduled(fixedDelayString = "${clearportx.cbtc-auto-accept.poll-interval-ms:30000}")
    @WithSpan
    public void pollAndAcceptCbtcInbound() {
        String operator = authUtils.getAppProviderPartyId();
        if (operator == null || operator.isBlank()) {
            LOG.debug("[CbtcAutoAccept] operator party not resolved; skipping cycle");
            return;
        }

        Result<List<TransferInstructionAcsQueryService.TransferInstructionDto>, DomainError> result =
                tiQuery.listForReceiver(operator);
        if (result.isErr()) {
            LOG.warn("[CbtcAutoAccept] listForReceiver failed: {}", result.getErrorUnsafe().message());
            return;
        }

        int total = 0, accepted = 0, dustSkipped = 0, errored = 0;
        for (var ti : result.getValueUnsafe()) {
            if (!CBTC_INSTRUMENT_ID.equals(ti.instrumentId())) {
                continue;
            }
            if (!operator.equals(ti.receiver())) {
                continue;
            }
            total++;

            BigDecimal amount;
            try {
                amount = new BigDecimal(ti.amount());
            } catch (NumberFormatException e) {
                errored++;
                LOG.debug("[CbtcAutoAccept] tiCid={} amount unparseable: {}",
                        abbreviate(ti.contractId()), ti.amount());
                continue;
            }

            if (amount.compareTo(minAmount) < 0) {
                dustSkipped++;
                continue;
            }

            try {
                ResponseEntity<AcceptWithContextResponse> resp =
                        acceptController.acceptWithContext(new AcceptRequest(ti.contractId(), operator));
                AcceptWithContextResponse body = resp.getBody();
                if (body != null && body.accepted()) {
                    accepted++;
                    LOG.info("[CbtcAutoAccept] tiCid={} accepted updateId={}",
                            abbreviate(ti.contractId()), body.updateId());
                } else {
                    errored++;
                    String err = body != null ? body.error() : "null-body";
                    LOG.warn("[CbtcAutoAccept] tiCid={} not accepted: {}",
                            abbreviate(ti.contractId()), err);
                }
            } catch (RuntimeException e) {
                errored++;
                LOG.warn("[CbtcAutoAccept] tiCid={} exception: {}",
                        abbreviate(ti.contractId()), e.getMessage());
            }
        }

        if (total > 0) {
            LOG.info("[CbtcAutoAccept] cycle complete: total={} accepted={} dustSkipped={} errored={}",
                    total, accepted, dustSkipped, errored);
        }
    }

    private static String abbreviate(String s) {
        if (s == null || s.length() <= 16) {
            return s;
        }
        return s.substring(0, 16) + "...";
    }
}
