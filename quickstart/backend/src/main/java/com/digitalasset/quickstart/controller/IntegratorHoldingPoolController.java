package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.dto.ErrorResponse;
import com.digitalasset.quickstart.dto.HoldingPoolResponse;
import com.digitalasset.quickstart.dto.IntegratorPoolStateDto;
import com.digitalasset.quickstart.service.HoldingPoolService;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/integrator")
public class IntegratorHoldingPoolController {

    private final HoldingPoolService holdingPoolService;

    public IntegratorHoldingPoolController(final HoldingPoolService holdingPoolService) {
        this.holdingPoolService = holdingPoolService;
    }

    @GetMapping("/holding-pools")
    @WithSpan
    public CompletableFuture<ResponseEntity<?>> listForIntegrator() {
        return holdingPoolService.list().thenApply(this::toIntegratorListResponse);
    }

    private ResponseEntity<?> toIntegratorListResponse(Result<List<HoldingPoolResponse>, DomainError> result) {
        if (result.isOk()) {
            List<IntegratorPoolStateDto> integratorList = result.getValueUnsafe().stream()
                    .map(IntegratorPoolStateDto::fromFull)
                    .toList();
            return ResponseEntity.ok(integratorList);
        }
        DomainError err = result.getErrorUnsafe();
        HttpStatus status = DomainErrorStatusMapper.map(err);
        ErrorResponse payload = new ErrorResponse(err.code(), err.message(), status.value(), "/api/integrator/holding-pools");
        return ResponseEntity.status(status).body(payload);
    }
}
