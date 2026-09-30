package com.digitalasset.quickstart.controller;

import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.controller.DomainErrorStatusMapper;
import com.digitalasset.quickstart.security.JwtAuthService;
import com.digitalasset.quickstart.security.JwtAuthService.AuthenticatedUser;
import com.digitalasset.quickstart.service.ResolveGrantService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Profile("local")
@RestController
@RequestMapping("/api/debug")
public class ResolveGrantController {

    private final ResolveGrantService svc;
    private final com.digitalasset.quickstart.ledger.LedgerApi ledgerApi;
    private final JwtAuthService jwtAuthService;

    public ResolveGrantController(ResolveGrantService svc,
                                  com.digitalasset.quickstart.ledger.LedgerApi ledgerApi,
                                  JwtAuthService jwtAuthService) {
        this.svc = svc;
        this.ledgerApi = ledgerApi;
        this.jwtAuthService = jwtAuthService;
    }

    public record ResolveReq(String poolId, String party) {}
    public record ResolveResp(boolean success,
                              String poolId,
                              String poolCid,
                              boolean visibilityGranted,
                              Boolean partyVisible,
                              String module,
                              String entity,
                              String packageId,
                              String error) {}

    @PostMapping("/resolve-and-grant")
    public ResponseEntity<ResolveResp> resolveAndGrant(@RequestBody ResolveReq req,
                                                       @RequestHeader(value = "Authorization", required = false) String authorization,
                                                       @RequestHeader(value = "X-Party", required = false) String headerParty) {
        Result<String, DomainError> partyResult = resolveTraderParty(req.party(), headerParty, authorization);
        if (partyResult.isErr()) {
            DomainError error = partyResult.getErrorUnsafe();
            return ResponseEntity.status(DomainErrorStatusMapper.map(error))
                    .body(new ResolveResp(false, req.poolId(), null, false, null, null, null, null, error.message()));
        }
        String resolvedParty = partyResult.getValueUnsafe();
        try {
            var out = svc.resolveAndGrant(req.poolId(), resolvedParty);
            boolean partyVisible = ledgerApi.getActiveContractsForParty(helvet_amm.amm.pool.Pool.class, resolvedParty)
                    .join()
                    .stream()
                    .anyMatch(p -> p.contractId.getContractId.equals(out.poolCid()));

            return ResponseEntity.ok(new ResolveResp(
                    true,
                    out.poolId(),
                    out.poolCid(),
                    out.visibilityGranted(),
                    partyVisible,
                    out.module(),
                    out.entity(),
                    out.packageId(),
                    null
            ));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(
                    new ResolveResp(false, req.poolId(), null, false, null, null, null, null, e.toString()));
        }
    }

    private Result<String, DomainError> resolveTraderParty(String bodyParty,
                                                           String headerParty,
                                                           String authorizationHeader) {
        if (authorizationHeader != null && !authorizationHeader.isBlank()) {
            Result<AuthenticatedUser, DomainError> authResult = jwtAuthService.authenticate(authorizationHeader);
            if (authResult.isOk()) {
                return Result.ok(authResult.getValueUnsafe().partyId());
            }
            return Result.err(authResult.getErrorUnsafe());
        }
        if (bodyParty != null && !bodyParty.isBlank()) {
            return Result.ok(bodyParty);
        }
        if (headerParty != null && !headerParty.isBlank()) {
            return Result.ok(headerParty);
        }
        return Result.err(new ValidationError("Provide Authorization token or party", ValidationError.Type.AUTHENTICATION));
    }
}
