package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.common.DomainError;
import com.digitalasset.quickstart.common.Result;
import com.digitalasset.quickstart.common.errors.LedgerVisibilityError;
import com.digitalasset.quickstart.common.errors.UnexpectedError;
import com.digitalasset.quickstart.common.errors.ValidationError;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.dto.HoldingDto;
import com.digitalasset.quickstart.dto.HoldingLockDto;
import com.digitalasset.quickstart.dto.HoldingUtxoDto;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.tokenstandard.openapi.ApiClient;
import com.digitalasset.quickstart.tokenstandard.openapi.ApiException;
import com.digitalasset.quickstart.tokenstandard.openapi.metadata.DefaultMetadataApi;
import com.digitalasset.quickstart.tokenstandard.openapi.metadata.model.Instrument;
import com.digitalasset.transcode.schema.Identifier;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import splice_api_token_holding_v1.Identifiers;

@Service
public class HoldingsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(HoldingsService.class);
    private static final Pattern PARTY_PATTERN = Pattern.compile("^[^:]+::[0-9a-fA-F]+$");
    private static final long METADATA_TTL_MILLIS = 60_000L;
    private static final long VISIBILITY_RETRY_DELAY_MILLIS = 250L;
    private static final int VISIBILITY_RETRY_ATTEMPTS = 1;
    private static final Set<String> RESERVED_METADATA_KEYS = Set.of(
            "metadataHash",
            "metadataUri",
            "metadataVersion",
            "symbol",
            "name",
            "description",
            "tokenType",
            "currencyCode",
            "logoUri"
    );
    private static final String METADATA_API_NAME = "splice-api-token-metadata-v1";
    private static final Identifier HOLDING_INTERFACE_ID = Identifiers.Splice_Api_Token_HoldingV1__Holding;

    private static final class CachedInstrument {
        private final Instrument instrument;
        private final long fetchedAtMillis;

        private CachedInstrument(final Instrument instrument, final long fetchedAtMillis) {
            this.instrument = instrument;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        private boolean isFresh(final long now) {
            return (now - fetchedAtMillis) < METADATA_TTL_MILLIS;
        }
    }

    private final LedgerApi ledgerApi;
    private final DefaultMetadataApi metadataApi;
    private final ConcurrentHashMap<String, CompletableFuture<CachedInstrument>> metadataCache = new ConcurrentHashMap<>();

    public HoldingsService(final LedgerApi ledgerApi, final LedgerConfig ledgerConfig) {
        this.ledgerApi = ledgerApi;
        if (ledgerConfig.getRegistryBaseUri() != null && !ledgerConfig.getRegistryBaseUri().isBlank()) {
            ApiClient client = new ApiClient();
            client.updateBaseUri(ledgerConfig.getRegistryBaseUri());
            this.metadataApi = new DefaultMetadataApi(client);
        } else {
            this.metadataApi = null;
        }
    }

    @WithSpan
    public CompletableFuture<Result<List<HoldingDto>, DomainError>> getHoldingsByParty(final String partyId) {
        Result<String, DomainError> validation = validatePartyId(partyId);
        if (validation.isErr()) {
            return completedError(validation.getErrorUnsafe());
        }
        String normalizedParty = validation.getValueUnsafe();
        return loadHoldingsWithRetry(normalizedParty, VISIBILITY_RETRY_ATTEMPTS);
    }


    @WithSpan
    public CompletableFuture<Result<List<HoldingUtxoDto>, DomainError>> getHoldingUtxos(final String partyId) {
        Result<String, DomainError> validation = validatePartyId(partyId);
        if (validation.isErr()) {
            return CompletableFuture.completedFuture(Result.err(validation.getErrorUnsafe()));
        }
        String normalizedParty = validation.getValueUnsafe();
        CompletableFuture<List<LedgerApi.InterfaceViewResult>> primary =
                ledgerApi.getInterfaceViewsForParty(HOLDING_INTERFACE_ID, normalizedParty);
        CompletableFuture<List<LedgerApi.InterfaceViewResult>> fallback =
                ledgerApi.getInterfaceViews(HOLDING_INTERFACE_ID);

        return primary.thenCombine(fallback, (a, b) -> {
                    if (a == null || a.isEmpty()) {
                        return b != null ? b : List.<LedgerApi.InterfaceViewResult>of();
                    }
                    if (b == null || b.isEmpty()) {
                        return a;
                    }
                    java.util.Map<String, LedgerApi.InterfaceViewResult> merged = new java.util.LinkedHashMap<>();
                    a.forEach(iv -> merged.put(iv.contractId(), iv));
                    b.forEach(iv -> merged.putIfAbsent(iv.contractId(), iv));
                    return new java.util.ArrayList<>(merged.values());
                })
                .<Result<List<HoldingUtxoDto>, DomainError>>handle((views, throwable) -> {
                    if (throwable != null) {
                        return Result.err(mapThrowable(throwable));
                    }
                    List<HoldingUtxoDto> dtos = views.stream()
                            .map(iv -> parseHoldingView(iv.viewValue(), iv.createArguments())
                                    .map(view -> toUtxoDto(iv.contractId(), view)))
                            .flatMap(java.util.Optional::stream)
                            .collect(java.util.stream.Collectors.toList());
                    return Result.ok(dtos);
                });
    }

    private CompletableFuture<Result<List<HoldingDto>, DomainError>> loadHoldingsWithRetry(
            final String normalizedParty,
            final int remainingRetries
    ) {
        return loadHoldingsOnce(normalizedParty).thenCompose(result -> {
            if (shouldRetryVisibility(result) && remainingRetries > 0) {
                CompletableFuture<Result<List<HoldingDto>, DomainError>> retryFuture = new CompletableFuture<>();
                CompletableFuture.delayedExecutor(VISIBILITY_RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                        .execute(() -> loadHoldingsWithRetry(normalizedParty, remainingRetries - 1)
                                .whenComplete((retryResult, retryError) -> {
                                    if (retryError != null) {
                                        retryFuture.completeExceptionally(retryError);
                                    } else {
                                        retryFuture.complete(retryResult);
                                    }
                                }));
                return retryFuture;
            }
            return CompletableFuture.completedFuture(result);
        });
    }

    private CompletableFuture<Result<List<HoldingDto>, DomainError>> loadHoldingsOnce(final String normalizedParty) {
        CompletableFuture<List<LedgerApi.InterfaceViewResult>> primary =
                ledgerApi.getInterfaceViewsForParty(HOLDING_INTERFACE_ID, normalizedParty);
        CompletableFuture<List<LedgerApi.InterfaceViewResult>> fallback =
                ledgerApi.getInterfaceViews(HOLDING_INTERFACE_ID);

        return primary.thenCombine(fallback, (a, b) -> {
                    if (a == null || a.isEmpty()) {
                        return b != null ? b : List.<LedgerApi.InterfaceViewResult>of();
                    }
                    if (b == null || b.isEmpty()) {
                        return a;
                    }
                    // merge by contractId
                    Map<String, LedgerApi.InterfaceViewResult> merged = new LinkedHashMap<>();
                    a.forEach(iv -> merged.put(iv.contractId(), iv));
                    b.forEach(iv -> merged.putIfAbsent(iv.contractId(), iv));
                    return new ArrayList<>(merged.values());
                })
                .thenCompose(views -> mapHoldingsForParty(normalizedParty, views))
                .<Result<List<HoldingDto>, DomainError>>handle((dtos, throwable) -> {
                    if (throwable != null) {
                        return Result.err(mapThrowable(throwable));
                    }
                    return Result.ok(dtos);
                });
    }

    private boolean shouldRetryVisibility(final Result<List<HoldingDto>, DomainError> result) {
        return result.isErr() && result.getErrorUnsafe() instanceof LedgerVisibilityError;
    }

    private CompletableFuture<List<HoldingDto>> mapHoldingsForParty(
            final String partyId,
            final List<LedgerApi.InterfaceViewResult> interfaceViews
    ) {
        List<HoldingViewFields> scopedViews = interfaceViews.stream()
                .map(view -> parseHoldingView(view.viewValue(), view.createArguments()))
                .flatMap(Optional::stream)
                .peek(view -> LOGGER.info("Holding interface view owner={}, instrument={} amount={} registry={}", view.owner(), view.instrumentId(), view.amount(), view.registry()))
                .filter(view -> partyId.equals(view.owner()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (scopedViews.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<HoldingDto>> dtoFutures = scopedViews.stream()
                .map(this::toHoldingDto)
                .toList();
        return sequence(dtoFutures);
    }

    private CompletableFuture<HoldingDto> toHoldingDto(final HoldingViewFields view) {
        CompletableFuture<Instrument> instrumentFuture = instrumentFutureFor(view.instrumentId());
        return instrumentFuture.thenApply(instrument -> buildHoldingDto(view, instrument));
    }

    private HoldingDto buildHoldingDto(final HoldingViewFields view, final Instrument instrument) {
        Map<String, String> metadataValues = new LinkedHashMap<>(view.metadataValues());

        String instrumentId = trimToNull(view.instrumentId());
        String registry = trimToNull(view.registry());
        String symbol = firstNonBlank(trimToNull(metadataValues.get("symbol")),
                instrument != null ? trimToNull(instrument.getSymbol()) : null);
        String name = firstNonBlank(trimToNull(metadataValues.get("name")),
                instrument != null ? trimToNull(instrument.getName()) : null);
        String description = trimToNull(metadataValues.get("description"));
        String tokenType = trimToNull(metadataValues.get("tokenType"));
        String currencyCode = trimToNull(metadataValues.get("currencyCode"));
        String logoUri = trimToNull(metadataValues.get("logoUri"));
        String metadataHash = trimToNull(metadataValues.get("metadataHash"));
        String metadataVersion = firstNonBlank(trimToNull(metadataValues.get("metadataVersion")),
                metadataVersionFromInstrument(instrument));

        Integer decimals = Optional.ofNullable(instrument)
                .map(Instrument::getDecimals)
                .orElse(parseInteger(metadataValues.get("decimals")));
        BigDecimal normalizedAmount = normalizeAmount(view.amount(), decimals);

        Map<String, String> attributes = extractAttributes(metadataValues);

        return new HoldingDto(
                instrumentId,
                symbol,
                name,
                description,
                normalizedAmount,
                decimals,
                tokenType,
                currencyCode,
                registry,
                logoUri,
                attributes,
                metadataHash,
                metadataVersion
        );
    }

    private record HoldingViewFields(
            String owner,
            String instrumentId,
            String registry,
            BigDecimal amount,
            Map<String, String> metadataValues,
            HoldingLockDto lock
    ) { }

    private HoldingUtxoDto toUtxoDto(final String contractId, final HoldingViewFields view) {
        Integer decimals = parseInteger(view.metadataValues().get("decimals"));
        if (decimals == null) {
            decimals = 10;
        }
        return new HoldingUtxoDto(
                contractId,
                view.registry(),
                view.instrumentId(),
                view.amount(),
                decimals,
                view.owner(),
                List.of(),
                view.lock()
        );
    }

    private Optional<HoldingViewFields> parseHoldingView(final ValueOuterClass.Record viewRecord,
                                                         final ValueOuterClass.Record createArguments) {
        ValueOuterClass.Record source = viewRecord != null ? viewRecord : createArguments;
        if (source == null) {
            return Optional.empty();
        }
        ValueOuterClass.Value ownerValue = getField(source, "owner", 0);
        ValueOuterClass.Value instrumentValue = getField(source, "instrumentId", 1);
        ValueOuterClass.Value amountValue = getField(source, "amount", 2);
        ValueOuterClass.Value lockValue = getField(source, "lock", 3);
        ValueOuterClass.Value metaValue = getField(source, "meta", 4);

        if (ownerValue == null || instrumentValue == null || amountValue == null) {
            return Optional.empty();
        }

        String owner = ownerValue.getParty();
        ValueOuterClass.Record instrumentRecord = instrumentValue.hasRecord() ? instrumentValue.getRecord() : null;
        ValueOuterClass.Value registryValue = getField(instrumentRecord, "admin", 0);
        ValueOuterClass.Value instrumentIdValue = getField(instrumentRecord, "id", 1);
        String registry = registryValue != null ? registryValue.getParty() : null;
        String instrumentId = instrumentIdValue != null ? instrumentIdValue.getText() : null;

        if (owner == null || instrumentId == null) {
            return Optional.empty();
        }

        BigDecimal amount = amountValue.getSumCase() == ValueOuterClass.Value.SumCase.NUMERIC
                ? new BigDecimal(amountValue.getNumeric())
                : BigDecimal.ZERO;

        Map<String, String> metadataValues = extractMetadataValues(metaValue);
        HoldingLockDto lockDto = extractLockDto(lockValue);
        return Optional.of(new HoldingViewFields(owner, instrumentId, registry, amount, metadataValues, lockDto));
    }

    private HoldingLockDto extractLockDto(final ValueOuterClass.Value lockValue) {
        if (lockValue == null) return null;
        if (lockValue.getSumCase() != ValueOuterClass.Value.SumCase.OPTIONAL) return null;
        if (!lockValue.getOptional().hasValue()) return null;
        ValueOuterClass.Value innerValue = lockValue.getOptional().getValue();
        if (innerValue.getSumCase() != ValueOuterClass.Value.SumCase.RECORD) return null;
        ValueOuterClass.Record lockRecord = innerValue.getRecord();

        ValueOuterClass.Value lockersValue = getField(lockRecord, "lockers", 0);
        ValueOuterClass.Value expiresAtValue = getField(lockRecord, "expiresAt", 1);
        ValueOuterClass.Value holderHintValue = getField(lockRecord, "holderHint", 2);
        ValueOuterClass.Value observersValue = getField(lockRecord, "observers", 3);

        List<String> lockers = extractPartyList(lockersValue);
        String expiresAt = extractOptionalTimestamp(expiresAtValue);
        String holderHint = extractOptionalText(holderHintValue);
        List<String> observers = extractOptionalPartyList(observersValue);

        return new HoldingLockDto(lockers, expiresAt, holderHint, observers);
    }

    private List<String> extractPartyList(final ValueOuterClass.Value value) {
        if (value == null) return List.of();
        if (value.getSumCase() == ValueOuterClass.Value.SumCase.LIST) {
            return value.getList().getElementsList().stream()
                    .filter(v -> v.getSumCase() == ValueOuterClass.Value.SumCase.PARTY)
                    .map(ValueOuterClass.Value::getParty)
                    .toList();
        }
        if (value.getSumCase() == ValueOuterClass.Value.SumCase.RECORD) {
            ValueOuterClass.Value inner = getField(value.getRecord(), "map", 0);
            if (inner != null) return extractPartyList(inner);
        }
        return List.of();
    }

    private List<String> extractOptionalPartyList(final ValueOuterClass.Value value) {
        if (value == null) return null;
        if (value.getSumCase() != ValueOuterClass.Value.SumCase.OPTIONAL) return null;
        if (!value.getOptional().hasValue()) return null;
        return extractPartyList(value.getOptional().getValue());
    }

    private String extractOptionalText(final ValueOuterClass.Value value) {
        if (value == null) return null;
        if (value.getSumCase() != ValueOuterClass.Value.SumCase.OPTIONAL) return null;
        if (!value.getOptional().hasValue()) return null;
        ValueOuterClass.Value inner = value.getOptional().getValue();
        if (inner.getSumCase() != ValueOuterClass.Value.SumCase.TEXT) return null;
        return inner.getText();
    }

    private String extractOptionalTimestamp(final ValueOuterClass.Value value) {
        if (value == null) return null;
        if (value.getSumCase() != ValueOuterClass.Value.SumCase.OPTIONAL) return null;
        if (!value.getOptional().hasValue()) return null;
        ValueOuterClass.Value inner = value.getOptional().getValue();
        if (inner.getSumCase() != ValueOuterClass.Value.SumCase.TIMESTAMP) return null;
        long epochMicros = inner.getTimestamp();
        return java.time.Instant.ofEpochSecond(epochMicros / 1_000_000L, (epochMicros % 1_000_000L) * 1_000L).toString();
    }

    private Map<String, ValueOuterClass.Value> toFieldMap(final ValueOuterClass.Record record) {
        if (record == null) {
            return Map.of();
        }
        Map<String, ValueOuterClass.Value> map = new LinkedHashMap<>();
        record.getFieldsList().forEach(field -> map.put(field.getLabel(), field.getValue()));
        return map;
    }

    private Map<String, String> extractMetadataValues(final ValueOuterClass.Value metadataValue) {
        if (metadataValue == null || metadataValue.getSumCase() != ValueOuterClass.Value.SumCase.RECORD) {
            return new LinkedHashMap<>();
        }
        Map<String, ValueOuterClass.Value> metaFields = toFieldMap(metadataValue.getRecord());
        ValueOuterClass.Value valuesValue = metaFields.get("values");
        if (valuesValue == null || valuesValue.getSumCase() != ValueOuterClass.Value.SumCase.TEXT_MAP) {
            valuesValue = getField(metadataValue.getRecord(), "values", 0);
            if (valuesValue == null || valuesValue.getSumCase() != ValueOuterClass.Value.SumCase.TEXT_MAP) {
                return new LinkedHashMap<>();
            }
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        valuesValue.getTextMap().getEntriesList().forEach(entry -> {
            if (entry.hasValue() && entry.getValue().getSumCase() == ValueOuterClass.Value.SumCase.TEXT) {
                metadata.put(entry.getKey(), entry.getValue().getText());
            }
        });
        return metadata;
    }


    private ValueOuterClass.Value getField(final ValueOuterClass.Record record, final String label, final int index) {
        if (record == null) {
            return null;
        }
        for (ValueOuterClass.RecordField field : record.getFieldsList()) {
            if (label.equals(field.getLabel())) {
                return field.getValue();
            }
        }
        if (record.getFieldsCount() > index) {
            return record.getFields(index).getValue();
        }
        return null;
    }
    private Map<String, String> extractAttributes(final Map<String, String> metadataValues) {
        if (metadataValues.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        metadataValues.forEach((key, value) -> {
            if (!RESERVED_METADATA_KEYS.contains(key)) {
                attributes.put(key, value);
            }
        });
        return attributes;
    }

    private CompletableFuture<Instrument> instrumentFutureFor(final String instrumentId) {
        if (metadataApi == null || instrumentId == null || instrumentId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        long now = System.currentTimeMillis();
        CompletableFuture<CachedInstrument> cachedFuture = metadataCache.get(instrumentId);
        if (cachedFuture != null) {
            return cachedFuture.thenCompose(cached -> {
                if (cached != null && cached.isFresh(now)) {
                    return CompletableFuture.completedFuture(cached.instrument);
                }
                metadataCache.remove(instrumentId, cachedFuture);
                return refreshInstrumentMetadata(instrumentId);
            });
        }
        return refreshInstrumentMetadata(instrumentId);
    }

    private CompletableFuture<Instrument> refreshInstrumentMetadata(final String instrumentId) {
        CompletableFuture<CachedInstrument> newFuture = fetchInstrumentMetadata(instrumentId);
        metadataCache.put(instrumentId, newFuture);
        return newFuture.thenApply(cached -> cached != null ? cached.instrument : null);
    }

    private CompletableFuture<CachedInstrument> fetchInstrumentMetadata(final String instrumentId) {
        try {
            return metadataApi.getInstrument(instrumentId)
                    .handle((instrument, throwable) -> {
                        if (throwable != null) {
                            LOGGER.warn("Failed to fetch metadata for {}: {}", instrumentId, throwable.getMessage());
                            return null;
                        }
                        return new CachedInstrument(instrument, System.currentTimeMillis());
                    });
        } catch (ApiException e) {
            LOGGER.warn("Metadata API rejected {}: {}", instrumentId, e.getMessage());
            return CompletableFuture.completedFuture(null);
        }
    }

    private Result<String, DomainError> validatePartyId(final String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return Result.err(new ValidationError("partyId is required", ValidationError.Type.REQUEST));
        }
        if (!PARTY_PATTERN.matcher(partyId).matches()) {
            return Result.err(new ValidationError("Invalid partyId", ValidationError.Type.REQUEST));
        }
        return Result.ok(partyId);
    }

    // Operator note: once splice-api-token-holding-v1 is installed on the participant,
    // GET /api/holdings/{partyId} will return actual holdings (e.g., Canton Coin for
    // ClearportX-DEX-1) instead of this placeholder UnexpectedError.
    private DomainError mapThrowable(final Throwable throwable) {
        Throwable root = unwrap(throwable);
        String message = root.getMessage();
        if (message != null) {
            String lowered = message.toLowerCase();
            if (lowered.contains("not visible") || lowered.contains("permission")) {
                return new LedgerVisibilityError(message);
            }
            if (lowered.contains("no_templates_for_package_name_and_qualified_name")) {
                return new UnexpectedError("CIP-0056 Holding DAR is not installed on this participant; install splice-api-token-holding-v1 to enable holdings lookups.");
            }
        }
        return new UnexpectedError(message != null ? message : root.toString());
    }

    private Throwable unwrap(final Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private CompletableFuture<Result<List<HoldingDto>, DomainError>> completedError(final DomainError error) {
        return CompletableFuture.completedFuture(Result.err(error));
    }

    private <T> CompletableFuture<List<T>> sequence(final List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDone = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        return allDone.thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
    }

    private BigDecimal normalizeAmount(final BigDecimal amount, final Integer decimals) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        if (decimals == null) {
            return amount.stripTrailingZeros();
        }
        try {
            return amount.setScale(decimals, RoundingMode.DOWN);
        } catch (ArithmeticException ex) {
            LOGGER.warn("Failed to normalize amount scale: {}", ex.getMessage());
            return amount;
        }
    }

    private String metadataVersionFromInstrument(final Instrument instrument) {
        if (instrument == null || instrument.getSupportedApis() == null) {
            return null;
        }
        Integer version = instrument.getSupportedApis().get(METADATA_API_NAME);
        return version != null ? version.toString() : null;
    }

    private Integer parseInteger(final String value) {
        try {
            return value == null ? null : Integer.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String trimToNull(final String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(final String primary, final String fallback) {
        return primary != null ? primary : fallback;
    }
}

