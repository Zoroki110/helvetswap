package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.ledger.LedgerApi.RawActiveContract;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Query SwapIntent ACS entries visible to a party.
 */
@Service
public class SwapIntentAcsQueryService {

    private static final Logger LOG = LoggerFactory.getLogger(SwapIntentAcsQueryService.class);
    private static final String MODULE = "AMM.SwapIntent";
    private static final String ENTITY = "SwapIntent";

    private final LedgerApi ledgerApi;

    public SwapIntentAcsQueryService(final LedgerApi ledgerApi) {
        this.ledgerApi = ledgerApi;
    }

    public record SwapIntentDto(
            String contractId,
            String transferInstructionCid,
            String user,
            String operator
    ) { }

    @WithSpan
    public Result<List<SwapIntentDto>, DomainError> listForParty(String party) {
        try {
            List<RawActiveContract> acs = ledgerApi.getActiveContractsRawForParty(party).toCompletableFuture().join();
            List<SwapIntentDto> intents = new ArrayList<>();
            for (RawActiveContract rac : acs) {
                if (!isSwapIntent(rac.templateId())) {
                    continue;
                }
                parseSwapIntent(rac).ifPresent(intents::add);
            }
            return Result.ok(intents);
        } catch (Exception e) {
            LOG.error("Failed to list SwapIntent for {}: {}", party, e.getMessage(), e);
            return Result.err(new UnexpectedError(e.getMessage()));
        }
    }

    @WithSpan
    public Optional<SwapIntentDto> findByTransferInstructionCid(String party, String tiCid) {
        Result<List<SwapIntentDto>, DomainError> list = listForParty(party);
        if (list.isErr()) {
            return Optional.empty();
        }
        return list.getValueUnsafe().stream()
                .filter(si -> tiCid.equals(si.transferInstructionCid()))
                .findFirst();
    }

    private boolean isSwapIntent(final ValueOuterClass.Identifier id) {
        return id != null
                && ENTITY.equals(id.getEntityName())
                && MODULE.equals(id.getModuleName());
    }

    private Optional<SwapIntentDto> parseSwapIntent(final RawActiveContract rac) {
        ValueOuterClass.Record args = rac.createArguments();
        if (args == null) {
            return Optional.empty();
        }
        String user = textOrParty(args, "user", 0);
        String operator = textOrParty(args, "operator", 1);
        String tiCid = contractIdField(args, "transferInstructionCid", 3);
        if (tiCid == null) {
            return Optional.empty();
        }
        return Optional.of(new SwapIntentDto(rac.contractId(), tiCid, user, operator));
    }

    private String textOrParty(final ValueOuterClass.Record rec, final String label, final int indexFallback) {
        ValueOuterClass.Value v = field(rec, label, indexFallback);
        if (v == null) return null;
        return switch (v.getSumCase()) {
            case TEXT -> v.getText();
            case PARTY -> v.getParty();
            default -> null;
        };
    }

    private String contractIdField(final ValueOuterClass.Record rec, final String label, final int indexFallback) {
        ValueOuterClass.Value v = field(rec, label, indexFallback);
        if (v == null) return null;
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.CONTRACT_ID) {
            return v.getContractId();
        }
        if (v.getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
            return v.getText();
        }
        return null;
    }

    private ValueOuterClass.Value field(final ValueOuterClass.Record rec, final String label, final int indexFallback) {
        Optional<ValueOuterClass.Value> byLabel = rec.getFieldsList().stream()
                .filter(f -> label.equals(f.getLabel()))
                .map(ValueOuterClass.RecordField::getValue)
                .findFirst();
        if (byLabel.isPresent()) {
            return byLabel.get();
        }
        if (indexFallback >= 0 && indexFallback < rec.getFieldsCount()) {
            return rec.getFields(indexFallback).getValue();
        }
        return null;
    }
}

