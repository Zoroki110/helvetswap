package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ApiError;
import com.digitalasset.quickstart.dto.ApiResponse;
import com.digitalasset.quickstart.dto.ErrorCode;
import com.digitalasset.quickstart.dto.ErrorMapper;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.service.SwapIntentAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService;
import com.digitalasset.quickstart.service.TransferInstructionAcsQueryService.TransferInstructionWithMemo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/devnet/swap-intents")
@Profile("devnet")
public class DevNetSwapIntentController {

    private static final Logger logger = LoggerFactory.getLogger(DevNetSwapIntentController.class);
    private final SwapIntentAcsQueryService swapIntentQueryService;
    private final TransferInstructionAcsQueryService tiQueryService;
    private final AuthUtils authUtils;
    private final ObjectMapper mapper = new ObjectMapper();

    public DevNetSwapIntentController(final SwapIntentAcsQueryService swapIntentQueryService,
                                      final TransferInstructionAcsQueryService tiQueryService,
                                      final AuthUtils authUtils) {
        this.swapIntentQueryService = swapIntentQueryService;
        this.tiQueryService = tiQueryService;
        this.authUtils = authUtils;
    }

    /**
     * GET /api/devnet/swap-intents/pending
     */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listPendingIntents(
            @RequestParam(value = "operatorParty", required = false) String operatorParty
    ) {
        String op = (operatorParty == null || operatorParty.isBlank())
                ? authUtils.getAppProviderPartyId()
                : operatorParty;

        Result<List<SwapIntentAcsQueryService.SwapIntentDto>, com.digitalasset.quickstart.common.DomainError> intents =
                swapIntentQueryService.listForParty(op);
        if (intents.isErr()) {
            return respond("swap-intents", Result.err(new ApiError(
                    ErrorCode.INTERNAL,
                    "Failed to query SwapIntent ACS",
                    Map.of("cause", intents.getErrorUnsafe().message()),
                    false,
                    null,
                    null
            )));
        }

        Result<List<TransferInstructionWithMemo>, com.digitalasset.quickstart.common.DomainError> tiList =
                tiQueryService.listForReceiverWithMemo(op);
        Map<String, String> requestIdByTi = new HashMap<>();
        if (tiList.isOk()) {
            for (TransferInstructionWithMemo row : tiList.getValueUnsafe()) {
                String rid = extractRequestId(row.memo());
                if (rid != null && row.transferInstruction() != null) {
                    requestIdByTi.put(row.transferInstruction().contractId(), rid);
                }
            }
        }

        List<Map<String, Object>> payload = intents.getValueUnsafe().stream().map(i -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intentCid", i.contractId());
            row.put("transferInstructionCid", i.transferInstructionCid());
            row.put("requestId", requestIdByTi.get(i.transferInstructionCid()));
            row.put("user", i.user());
            row.put("operator", i.operator());
            return row;
        }).toList();

        return respond("swap-intents", Result.ok(payload));
    }

    private <T> ResponseEntity<ApiResponse<T>> respond(String requestId, Result<T, ApiError> result) {
        if (result.isOk()) {
            return ResponseEntity.ok(ApiResponse.success(requestId, result.getValueUnsafe()));
        }
        ApiError error = result.getErrorUnsafe();
        logger.error("[DevNetSwapIntent] requestId={} failed code={} message={} details={}", requestId, error.code, error.message, error.details);
        return ResponseEntity.status(ErrorMapper.toHttpStatus(error.code))
                .body(ApiResponse.failure(requestId, error));
    }

    private String extractRequestId(String memoRaw) {
        if (memoRaw == null || memoRaw.isBlank()) {
            return null;
        }
        try {
            var node = mapper.readTree(memoRaw);
            var rid = node.get("requestId");
            return rid != null ? rid.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}

