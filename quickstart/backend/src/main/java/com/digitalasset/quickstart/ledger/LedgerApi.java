// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.ledger;

import com.daml.ledger.api.v2.*;
import com.daml.ledger.api.v2.admin.PackageManagementServiceGrpc;
import com.daml.ledger.api.v2.admin.PackageManagementServiceOuterClass;
import com.daml.ledger.api.v2.PackageServiceGrpc;
import com.daml.ledger.api.v2.PackageServiceOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.security.AuthUtils;
import com.digitalasset.quickstart.security.TokenProvider;
import com.digitalasset.transcode.Converter;
import com.digitalasset.transcode.codec.proto.ProtobufCodec;
import com.digitalasset.transcode.java.Choice;
import com.digitalasset.transcode.java.ContractId;
import com.digitalasset.transcode.java.Template;
import com.digitalasset.transcode.java.Utils;
import com.digitalasset.transcode.schema.Dictionary;
import com.digitalasset.transcode.schema.Identifier;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import daml.Daml;
import io.grpc.*;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static com.digitalasset.quickstart.utility.TracingUtils.*;

import com.google.protobuf.ByteString;

@Component
public class LedgerApi {
    private final String APP_ID;
    private final CommandSubmissionServiceGrpc.CommandSubmissionServiceFutureStub submission;
    private final CommandServiceGrpc.CommandServiceFutureStub commands;
    private final StateServiceGrpc.StateServiceStub stateService;
    private final PackageManagementServiceGrpc.PackageManagementServiceFutureStub pkg;
    private final PackageServiceGrpc.PackageServiceBlockingStub packageService;
    private final com.daml.ledger.api.v2.UpdateServiceGrpc.UpdateServiceFutureStub transactions;
    private final Dictionary<Converter<Object, ValueOuterClass.Value>> dto2Proto;
    private final Dictionary<Converter<ValueOuterClass.Value, Object>> proto2Dto;

    private final Logger logger = LoggerFactory.getLogger(LedgerApi.class);
    private final String appProviderParty;
    // Canton 3.4.7: Changed from TransactionTree to flat Transaction
    private volatile TransactionOuterClass.Transaction lastTxn = null;

    @Autowired
    public LedgerApi(LedgerConfig ledgerConfig, Optional<TokenProvider> tokenProvider, AuthUtils authUtils) {
        APP_ID = ledgerConfig.getApplicationId();
        appProviderParty = authUtils.getAppProviderPartyId();
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
                .forAddress(ledgerConfig.getHost(), ledgerConfig.getPort())
                .usePlaintext();
        // Support reverse-proxy/vhost routing (e.g., NGINX on :8888) by overriding gRPC authority
        if (ledgerConfig.getGrpcAuthority() != null && !ledgerConfig.getGrpcAuthority().isBlank()) {
            builder = builder.overrideAuthority(ledgerConfig.getGrpcAuthority());
        }
        if (tokenProvider.isEmpty()) {
            throw new IllegalStateException("TokenProvider is required for authentication");
        }
        builder.intercept(new Interceptor(tokenProvider.get()));
        ManagedChannel channel = builder.build();

        // Single log statement, not duplicating attributes for spans, so leaving as-is:
        logger.atInfo()
                .addKeyValue("host", ledgerConfig.getHost())
                .addKeyValue("port", ledgerConfig.getPort())
                .log("Connected to ledger");

        submission = CommandSubmissionServiceGrpc.newFutureStub(channel);
        commands = CommandServiceGrpc.newFutureStub(channel);
        stateService = StateServiceGrpc.newStub(channel);  // Use regular stub for streaming
        pkg = PackageManagementServiceGrpc.newFutureStub(channel);
        packageService = PackageServiceGrpc.newBlockingStub(channel);
        transactions = com.daml.ledger.api.v2.UpdateServiceGrpc.newFutureStub(channel);

        ProtobufCodec protoCodec = new ProtobufCodec();
        dto2Proto = Utils.getConverters(Daml.ENTITIES, protoCodec);
        proto2Dto = Utils.getConverters(protoCodec, Daml.ENTITIES);
    }

    @WithSpan
    public byte[] getPackageBytes(final String packageId) throws Exception {
        var req = PackageServiceOuterClass.GetPackageRequest.newBuilder()
                .setPackageId(packageId)
                .build();
        var resp = packageService.getPackage(req);
        return resp.getArchivePayload().toByteArray();
    }

    /**
     * Resolve a package reference to a concrete package-id.
     * Canton 3.5 (Splice 0.6.x) command templateIds use the "#package-name" form, but the
     * GetPackage API (schema introspection via {@link #getPackageBytes}) requires a CONCRETE
     * package-id. This resolves a "#name" ref to the highest-semver concrete package-id known to
     * the participant via ListKnownPackages. A ref that is not "#"-prefixed is returned unchanged
     * (already a concrete id). Throws if a "#name" matches zero known packages — no silent
     * fallback to a stale id.
     */
    @WithSpan
    public String resolvePackageId(final String ref) {
        if (ref == null || !ref.startsWith("#")) {
            return ref;
        }
        final String name = ref.substring(1);
        var req = PackageManagementServiceOuterClass.ListKnownPackagesRequest.newBuilder().build();
        List<PackageManagementServiceOuterClass.PackageDetails> details;
        try {
            details = toCompletableFuture(pkg.listKnownPackages(req)).get().getPackageDetailsList();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list known packages while resolving " + ref, e);
        }
        String bestId = null;
        String bestVersion = null;
        for (var d : details) {
            if (!name.equals(d.getName())) {
                continue;
            }
            if (bestVersion == null || compareSemver(d.getVersion(), bestVersion) > 0) {
                bestVersion = d.getVersion();
                bestId = d.getPackageId();
            }
        }
        if (bestId == null) {
            throw new IllegalStateException(
                    "No known package resolves package-name '" + name + "' (ref " + ref + ")");
        }
        logger.info("Resolved package-name {} -> {} (version {})", ref, bestId, bestVersion);
        return bestId;
    }

    private static int compareSemver(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = i < pa.length ? parseSegment(pa[i]) : 0;
            int vb = i < pb.length ? parseSegment(pb[i]) : 0;
            if (va != vb) {
                return Integer.compare(va, vb);
            }
        }
        return 0;
    }

    private static int parseSegment(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @WithSpan
    public <T extends Template> CompletableFuture<Void> create(
            T entity,
            String commandId
    ) {
        var ctx = tracingCtx(logger, "Creating contract",
                "commandId", commandId,
                "templateId", entity.templateId().toString(),
                "applicationId", APP_ID
        );
        return traceWithStartEvent(ctx, () -> {
            CommandsOuterClass.Command.Builder command = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload = dto2Proto.template(entity.templateId()).convert(entity);
            command.getCreateBuilder().setTemplateId(toNameIdentifier(entity.templateId())).setCreateArguments(payload.getRecord());
            return submitCommands(List.of(command.build()), commandId).thenApply(submitResponse -> null);
        });
    }

    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResult(
            ContractId<T> contractId,
            C choice,
            String commandId
    ) {
        return exerciseAndGetResult(contractId, choice, commandId, List.of());
    }

    /**
     * Exercise a choice with multiple actAs parties (for multi-party workflows)
     * This is essential for AddLiquidity (liquidityProvider + poolParty + lpIssuer)
     * and other multi-party operations.
     *
     * @param contractId The contract to exercise the choice on
     * @param choice The choice to exercise
     * @param commandId Unique command ID for idempotency
     * @param actAsParties List of parties that will act (must match DAML controllers)
     * @param readAsParties List of parties that can read (for visibility)
     * @return The result of the choice execution
     */
    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResultWithParties(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<String> actAsParties,
            List<String> readAsParties
    ) {
        return exerciseAndGetResultWithParties(contractId, choice, commandId, actAsParties, readAsParties, List.of());
    }

    /**
     * Exercise a choice with multiple actAs parties and disclosed contracts
     */
    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResultWithParties(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<String> actAsParties,
            List<String> readAsParties,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Exercising choice with multi-party",
                "commandId", commandId,
                "contractId", contractId.getContractId,
                "choiceName", choice.choiceName(),
                "templateId", choice.templateId().toString(),
                "actAsParties", String.join(",", actAsParties),
                "readAsParties", String.join(",", readAsParties),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Command.Builder cmdBuilder = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload =
                    dto2Proto.choiceArgument(choice.templateId(), choice.choiceName()).convert(choice);

            cmdBuilder.getExerciseBuilder()
                    .setTemplateId(toNameIdentifier(choice.templateId()))
                    .setContractId(contractId.getContractId)
                    .setChoice(choice.choiceName())
                    .setChoiceArgument(payload);

            // Build Commands with multi-party actAs and readAs
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setUserId(appProviderParty)
                    .addAllActAs(actAsParties)      // Multi-party actAs!
                    .addAllReadAs(readAsParties)    // Multi-party readAs!
                    .addCommands(cmdBuilder.build());

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            // Canton 3.4.7: Must provide TransactionFormat with EventFormat to specify what events to receive
            // This tells the ledger which parties' events we want to see in the response
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(actAsParties.get(0), TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            // Canton 3.4.7: Use SubmitAndWaitForTransactionRequest (new request type with TransactionFormat)
            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            // Canton 3.4.7: Add TransactionFormat (wraps EventFormat)
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            addEventWithAttributes(Span.current(), "built ledger submit request", Map.of(
                "actAsParties", String.join(",", actAsParties),
                "readAsParties", String.join(",", readAsParties)
            ));
            logger.info("Submitting multi-party ledger command: actAs={}, readAs={}", actAsParties, readAsParties);

            // Canton 3.4.7: Use submitAndWaitForTransaction instead of submitAndWaitForTransactionTree
            // Returns flat Transaction with List<Event> instead of hierarchical TransactionTree
            return toCompletableFuture(commands.submitAndWaitForTransaction(request))
                    .thenApply(response -> {
                        // Canton 3.4.7: Get flat Transaction instead of TransactionTree
                        TransactionOuterClass.Transaction txn = response.getTransaction();
                        lastTxn = txn;
                        long offset = txn.getOffset();
                        String workflowId = txn.getWorkflowId();

                        // Canton 3.4.7: Events are now a flat list, not a map by ID
                        // We need to find the ExercisedEvent in the list
                        List<EventOuterClass.Event> events = txn.getEventsList();
                        EventOuterClass.Event exercisedEvent = events.stream()
                                .filter(EventOuterClass.Event::hasExercised)
                                .findFirst()
                                .orElse(null);

                        Map<String, Object> completionAttrs = new HashMap<>();
                        completionAttrs.put("ledgerOffset", offset);
                        completionAttrs.put("workflowId", workflowId);
                        completionAttrs.put("actAsParties", String.join(",", actAsParties));
                        completionAttrs.put("eventsCount", events.size());

                        setSpanAttributes(Span.current(), completionAttrs);
                        logInfo(logger, "Exercised multi-party choice", completionAttrs);

                        // Canton 3.4.7: Extract exercise result from flat Event (not TreeEvent)
                        ValueOuterClass.Value resultPayload = exercisedEvent != null
                                ? exercisedEvent.getExercised().getExerciseResult()
                                : ValueOuterClass.Value.getDefaultInstance();

                        @SuppressWarnings("unchecked")
                        Result result = (Result) proto2Dto.choiceResult(choice.templateId(), choice.choiceName()).convert(resultPayload);
                        return result;
                    })
                    .exceptionally(ex -> {
                        logger.error("Multi-party choice exercise failed: commandId={}, actAs={}, error={}",
                                commandId, actAsParties, ex.getMessage());
                        throw new RuntimeException("Failed to exercise choice with multi-party: " + ex.getMessage(), ex);
                    });
        });
    }

    @WithSpan
    public <T extends Template, Result, C extends Choice<T, Result>>
    CompletableFuture<Result> exerciseAndGetResult(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Exercising choice",
                "commandId", commandId,
                "contractId", contractId.getContractId,
                "choiceName", choice.choiceName(),
                "templateId", choice.templateId().toString(),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Command.Builder cmdBuilder = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload =
                    dto2Proto.choiceArgument(choice.templateId(), choice.choiceName()).convert(choice);

            cmdBuilder.getExerciseBuilder()
                    .setTemplateId(toNameIdentifier(choice.templateId()))
                    .setContractId(contractId.getContractId)
                    .setChoice(choice.choiceName())
                    .setChoiceArgument(payload);

            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setUserId(appProviderParty)
                    .addActAs(appProviderParty)
                    .addReadAs(appProviderParty)
                    .addCommands(cmdBuilder.build());

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            // Canton 3.4.7: Build EventFormat to specify what events we want in response
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(appProviderParty, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            // Canton 3.4.7: Use SubmitAndWaitForTransactionRequest (new type with TransactionFormat)
            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            // Canton 3.4.7: Must include TransactionFormat
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            addEventWithAttributes(Span.current(), "built ledger submit request", Map.of());
            logger.info("Submitting ledger command");
            // Canton 3.4.7: Use submitAndWaitForTransaction (flat events) instead of submitAndWaitForTransactionTree
            return toCompletableFuture(commands.submitAndWaitForTransaction(request))
                    .thenApply(response -> {
                        // Canton 3.4.7: Flat Transaction instead of TransactionTree
                        TransactionOuterClass.Transaction txn = response.getTransaction();
                        lastTxn = txn;
                        long offset = txn.getOffset();
                        String workflowId = txn.getWorkflowId();

                        // Canton 3.4.7: Search flat list for ExercisedEvent
                        List<EventOuterClass.Event> events = txn.getEventsList();
                        EventOuterClass.Event exercisedEvent = events.stream()
                                .filter(EventOuterClass.Event::hasExercised)
                                .findFirst()
                                .orElse(null);

                        Map<String, Object> completionAttrs = new HashMap<>();
                        completionAttrs.put("ledgerOffset", offset);
                        completionAttrs.put("workflowId", workflowId);
                        completionAttrs.put("eventsCount", events.size());

                        setSpanAttributes(Span.current(), completionAttrs);
                        logInfo(logger, "Exercised choice", completionAttrs);

                        // Canton 3.4.7: Get result from flat Event (not TreeEvent)
                        ValueOuterClass.Value resultPayload = exercisedEvent != null
                                ? exercisedEvent.getExercised().getExerciseResult()
                                : ValueOuterClass.Value.getDefaultInstance();

                        @SuppressWarnings("unchecked")
                        Result result = (Result) proto2Dto.choiceResult(choice.templateId(), choice.choiceName()).convert(resultPayload);
                        return result;
                    });
        });
    }

    @WithSpan
    public CompletableFuture<CommandSubmissionServiceOuterClass.SubmitResponse> submitCommands(
            List<CommandsOuterClass.Command> cmds,
            String commandId
    ) {
        return submitCommands(cmds, commandId, List.of());
    }

    @WithSpan
    public CompletableFuture<CommandSubmissionServiceOuterClass.SubmitResponse> submitCommands(
            List<CommandsOuterClass.Command> cmds,
            String commandId,
            List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        var ctx = tracingCtx(logger, "Submitting commands",
                "commands.count", cmds.size(),
                "commandId", commandId,
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setUserId(appProviderParty)
                    .addActAs(appProviderParty)
                    .addReadAs(appProviderParty)
                    .addAllCommands(cmds);

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }

            CommandSubmissionServiceOuterClass.SubmitRequest request =
                    CommandSubmissionServiceOuterClass.SubmitRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .build();

            return toCompletableFuture(submission.submit(request));
        });
    }


    /**
     * Query active contracts from the Ledger API (authoritative, no PQS lag)
     * Returns all active contracts of the specified template type for the app provider party
     */
    @WithSpan
    public <T extends Template> CompletableFuture<List<ActiveContract<T>>> getActiveContracts(Class<T> clazz) {
        return getActiveContractsForParty(clazz, appProviderParty);
    }

    /**
     * Query active contracts for a specific party.
     */
    @WithSpan
    public <T extends Template> CompletableFuture<List<ActiveContract<T>>> getActiveContractsForParty(
            Class<T> clazz,
            String party
    ) {
        return getActiveContractsInternal(clazz, party);
    }

    private <T extends Template> CompletableFuture<List<ActiveContract<T>>> getActiveContractsInternal(
            Class<T> clazz,
            String filterParty
    ) {
        Identifier templateId = Utils.getTemplateIdByClass(clazz);
        var ctx = tracingCtx(logger, "Getting active contracts",
                "templateId", templateId.toString(),
                "party", filterParty
        );
        return trace(ctx, () -> {
            CompletableFuture<List<ActiveContract<T>>> resultFuture = new CompletableFuture<>();

            stateService.getLedgerEnd(
                StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                    @Override
                    public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                        long ledgerEndOffset = response.getOffset();
                        logger.debug("Current ledger end offset: {}", ledgerEndOffset);

                        StateServiceOuterClass.GetActiveContractsRequest request =
                                StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                                        .setEventFormat(TransactionFilterOuterClass.EventFormat.newBuilder()
                                                .putFiltersByParty(filterParty,
                                                        TransactionFilterOuterClass.Filters.newBuilder()
                                                                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                                        .setTemplateFilter(TransactionFilterOuterClass.TemplateFilter.newBuilder()
                                                                                .setTemplateId(toNameIdentifier(templateId))
                                                                                .setIncludeCreatedEventBlob(false)
                                                                                .build())
                                                                        .build())
                                                                .build())
                                                .build())
                                        .setActiveAtOffset(ledgerEndOffset)
                                        .build();

                        List<ActiveContract<T>> contracts = new ArrayList<>();

                        stateService.getActiveContracts(request, new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetActiveContractsResponse>() {
                            @Override
                            public void onNext(StateServiceOuterClass.GetActiveContractsResponse response) {

                                if (response.hasActiveContract()) {
                                    StateServiceOuterClass.ActiveContract activeContract = response.getActiveContract();
                                    String contractId = activeContract.getCreatedEvent().getContractId();
                                    ValueOuterClass.Value payloadValue = ValueOuterClass.Value.newBuilder()
                                            .setRecord(activeContract.getCreatedEvent().getCreateArguments())
                                            .build();

                                    @SuppressWarnings("unchecked")
                                    T payload = (T) proto2Dto.template(templateId).convert(payloadValue);

                                    contracts.add(new ActiveContract<>(new ContractId<>(contractId), payload));
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                logger.error("Error fetching active contracts for {}: {}", templateId, t.getMessage());
                                resultFuture.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.info("Fetched {} active contracts for {}", contracts.size(), templateId.qualifiedName());
                                resultFuture.complete(contracts);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Error getting ledger end: {}", t.getMessage());
                        resultFuture.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        // GetLedgerEnd completes after onNext
                    }
                });

            return resultFuture;
        });
    }

    /**
     * Query interface views from the Ledger API (authoritative, no PQS lag)
     */
    @WithSpan
    public CompletableFuture<List<InterfaceViewResult>> getInterfaceViews(final Identifier interfaceId) {
        return getInterfaceViewsForParty(interfaceId, appProviderParty);
    }

    @WithSpan
    public CompletableFuture<List<InterfaceViewResult>> getInterfaceViewsForParty(
            final Identifier interfaceId,
            final String party
    ) {
        return getInterfaceViewsInternal(interfaceId, party);
    }

    private CompletableFuture<List<InterfaceViewResult>> getInterfaceViewsInternal(
            final Identifier interfaceId,
            final String filterParty
    ) {
        // Canton 3.5: the WIRE interface filter (setInterfaceId below) must reference the interface
        // by package-NAME (#name), not a concrete package-id. matchesInterface (the local compare) is
        // package-id-agnostic (module+entity only), so #name is safe for both wire and local use.
        ValueOuterClass.Identifier targetInterface = toNameIdentifier(interfaceId);
        var ctx = tracingCtx(logger, "Getting interface views",
                "interfaceId", interfaceId.toString(),
                "party", filterParty
        );
        return trace(ctx, () -> {
            CompletableFuture<List<InterfaceViewResult>> resultFuture = new CompletableFuture<>();

            stateService.getLedgerEnd(
                StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                    @Override
                    public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                        long ledgerEndOffset = response.getOffset();
                        logger.debug("Current ledger end offset: {}", ledgerEndOffset);

                        TransactionFilterOuterClass.InterfaceFilter interfaceFilter =
                                TransactionFilterOuterClass.InterfaceFilter.newBuilder()
                                        .setInterfaceId(targetInterface)
                                        .setIncludeInterfaceView(true)
                                        .setIncludeCreatedEventBlob(true)
                                        .build();

                        StateServiceOuterClass.GetActiveContractsRequest request =
                                StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                                        .setEventFormat(TransactionFilterOuterClass.EventFormat.newBuilder()
                                                .putFiltersByParty(filterParty,
                                                        TransactionFilterOuterClass.Filters.newBuilder()
                                                                .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                                        .setInterfaceFilter(interfaceFilter)
                                                                        .build())
                                                                .build())
                                                .build())
                                        .setActiveAtOffset(ledgerEndOffset)
                                        .build();

                        List<InterfaceViewResult> views = new ArrayList<>();

                        stateService.getActiveContracts(request, new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetActiveContractsResponse>() {
                            @Override
                            public void onNext(StateServiceOuterClass.GetActiveContractsResponse response) {
                                if (response.hasActiveContract()) {
                                    EventOuterClass.CreatedEvent createdEvent = response.getActiveContract().getCreatedEvent();
                                    String contractId = createdEvent.getContractId();
                                    createdEvent.getInterfaceViewsList().forEach(interfaceView -> {

                                        if (matchesInterface(interfaceView.getInterfaceId(), targetInterface)) {
                                            ValueOuterClass.Record viewValue = interfaceView.hasViewValue() ? interfaceView.getViewValue() : null;
                                            ValueOuterClass.Record createArgs = createdEvent.hasCreateArguments()
                                                    ? createdEvent.getCreateArguments()
                                                    : null;
                                            views.add(new InterfaceViewResult(contractId, viewValue, createArgs));
                                        }
                                    });
                                }
                            }

                            @Override
                            public void onError(Throwable t) {
                                logger.error("Error fetching interface views for {}: {}", interfaceId, t.getMessage());
                                resultFuture.completeExceptionally(t);
                            }

                            @Override
                            public void onCompleted() {
                                logger.info("Fetched {} interface views for {}", views.size(), interfaceId.qualifiedName());
                                resultFuture.complete(views);
                            }
                        });
                    }

                    @Override
                    public void onError(Throwable t) {
                        logger.error("Error getting ledger end: {}", t.getMessage());
                        resultFuture.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                        // No-op
                    }
                });

            return resultFuture;
        });
    }

    /**
     * Check if a contract is active by querying all active contracts of that type
     * Returns true if active, false if archived or not found
     *
     * This is a simple implementation that queries all contracts of the type and checks if the CID exists.
     * For large contract sets, consider implementing a more efficient lookup.
     */
    @WithSpan
    public <T extends Template> CompletableFuture<Boolean> isContractActive(ContractId<T> contractId, Class<T> clazz) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> contracts.stream()
                        .anyMatch(c -> c.contractId.getContractId.equals(contractId.getContractId)))
                .exceptionally(ex -> {
                    logger.warn("Error checking contract status for {}: {}", contractId.getContractId, ex.getMessage());
                    return false;  // Treat errors as "not active"
                });
    }

    /**
     * Validate and get active contract with precondition check
     * Returns the contract if it exists and meets the precondition, otherwise throws
     *
     * @param contractId The contract ID to validate
     * @param clazz The template class
     * @param precondition Optional validation function (return true if valid)
     * @param errorMessage Error message if contract not found or precondition fails
     * @throws IllegalStateException if contract not found or precondition fails
     */
    @WithSpan
    public <T extends Template> CompletableFuture<ActiveContract<T>> validateContract(
            ContractId<T> contractId,
            Class<T> clazz,
            java.util.function.Predicate<T> precondition,
            String errorMessage
    ) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> {
                    Optional<ActiveContract<T>> maybeContract = contracts.stream()
                            .filter(c -> c.contractId.getContractId.equals(contractId.getContractId))
                            .findFirst();

                    if (maybeContract.isEmpty()) {
                        logger.error("Contract not found: {} ({})", contractId.getContractId, errorMessage);
                        throw new IllegalStateException("CONTRACT_NOT_FOUND: " + errorMessage);
                    }

                    ActiveContract<T> contract = maybeContract.get();
                    if (precondition != null && !precondition.test(contract.payload)) {
                        logger.error("Contract precondition failed: {} ({})", contractId.getContractId, errorMessage);
                        throw new IllegalStateException("PRECONDITION_FAILED: " + errorMessage);
                    }

                    logger.info("✅ Contract validated: {}", contractId.getContractId);
                    return contract;
                });
    }

    /**
     * Validate multiple contracts at once (more efficient - single ACS query per template type)
     * Returns map of contractId -> ActiveContract for all valid contracts
     *
     * @param contractIds List of contract IDs to validate
     * @param clazz The template class
     * @throws IllegalStateException if any contract not found
     */
    @WithSpan
    public <T extends Template> CompletableFuture<Map<String, ActiveContract<T>>> validateContracts(
            List<ContractId<T>> contractIds,
            Class<T> clazz
    ) {
        return getActiveContracts(clazz)
                .thenApply(contracts -> {
                    Map<String, ActiveContract<T>> validContracts = new HashMap<>();
                    Set<String> requestedIds = contractIds.stream()
                            .map(cid -> cid.getContractId)
                            .collect(java.util.stream.Collectors.toSet());

                    for (ActiveContract<T> contract : contracts) {
                        if (requestedIds.contains(contract.contractId.getContractId)) {
                            validContracts.put(contract.contractId.getContractId, contract);
                        }
                    }

                    // Check all requested contracts were found
                    for (String requestedId : requestedIds) {
                        if (!validContracts.containsKey(requestedId)) {
                            logger.error("Contract not found: {}", requestedId);
                            throw new IllegalStateException("CONTRACT_NOT_FOUND: " + requestedId);
                        }
                    }

                    logger.info("✅ Validated {} contracts", validContracts.size());
                    return validContracts;
                });
    }

    /**
     * Create a contract and deterministically retrieve its ContractId from the flat transaction.
     * Canton 3.4.7: Uses submitAndWaitForTransaction instead of submitAndWaitForTransactionTree.
     * This avoids the create-then-query race condition by using the command completion + transaction events.
     *
     * @param template The contract template to create
     * @param actAsParties List of parties that will create the contract
     * @param readAsParties List of parties that can read (for visibility)
     * @param commandId Unique command ID for idempotency and traceability
     * @param templateId The template identifier (for filtering created events)
     * @return The ContractId of the newly created contract
     */
    @WithSpan
    public <T extends Template> CompletableFuture<ContractId<T>> createAndGetCid(
            T template,
            List<String> actAsParties,
            List<String> readAsParties,
            String commandId,
            Identifier templateId
    ) {
        var ctx = tracingCtx(logger, "Creating contract and getting CID",
                "commandId", commandId,
                "templateId", templateId.toString(),
                "actAsParties", String.join(",", actAsParties),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            // Build create command
            CommandsOuterClass.Command.Builder command = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload = dto2Proto.template(template.templateId()).convert(template);
            command.getCreateBuilder()
                    .setTemplateId(toNameIdentifier(template.templateId()))
                    .setCreateArguments(payload.getRecord());

            // Build Commands with parties
            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setUserId(appProviderParty)
                    .addAllActAs(actAsParties)
                    .addAllReadAs(readAsParties)
                    .addCommands(command.build());

            // Canton 3.4.7: Build EventFormat to specify what events we want in response
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(actAsParties.get(0), TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            // Canton 3.4.7: Use SubmitAndWaitForTransactionRequest (new type with TransactionFormat)
            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            logger.info("Submitting create command: commandId={}, templateId={}, actAs={}",
                    commandId, templateId, String.join(",", actAsParties));

            // Canton 3.4.7: Use submitAndWaitForTransaction (flat events) instead of submitAndWaitForTransactionTree
            return toCompletableFuture(commands.submitAndWaitForTransaction(request))
                    .thenApply(response -> {
                        // Canton 3.4.7: Get flat Transaction instead of TransactionTree
                        TransactionOuterClass.Transaction txn = response.getTransaction();
                        lastTxn = txn;
                        String txId = txn.getUpdateId();  // Use updateId instead of transactionId
                        long offset = txn.getOffset();

                        logger.info("Transaction completed: updateId={}, offset={}, commandId={}", txId, offset, commandId);

                        // Canton 3.4.7: Extract events from flat list (not map)
                        List<EventOuterClass.Event> events = txn.getEventsList();
                        ValueOuterClass.Identifier targetTemplateId = toIdentifier(templateId);

                        // Debug logging
                        logger.debug("Transaction has {} events for commandId={}", events.size(), commandId);

                        // Find the created event matching module + entity name (package-id independent)
                        String targetModule = templateId.moduleName();
                        String targetEntity = templateId.entityName();

                        // Canton 3.4.7: Search flat list instead of map values
                        Optional<String> createdContractId = events.stream()
                                .filter(EventOuterClass.Event::hasCreated)
                                .map(EventOuterClass.Event::getCreated)
                                .filter(created ->
                                        created.getTemplateId().getModuleName().equals(targetModule) &&
                                        created.getTemplateId().getEntityName().equals(targetEntity))
                                .map(created -> created.getContractId())
                                .findFirst();

                        if (createdContractId.isEmpty()) {
                            logger.error("No CreatedEvent found for {}:{} in transaction updateId={}",
                                    targetModule, targetEntity, txId);
                            throw new IllegalStateException(
                                    "Contract creation failed: No CreatedEvent found for " + targetModule + ":" + targetEntity);
                        }

                        String contractId = createdContractId.get();
                        logger.info("✅ Contract created: cid={}, updateId={}, commandId={}",
                                contractId, txId, commandId);

                        return new ContractId<T>(contractId);
                    })
                    .exceptionally(ex -> {
                        logger.error("Create and get CID failed: commandId={}, error={}",
                                commandId, ex.getMessage(), ex);
                        throw new RuntimeException("Failed to create contract: " + ex.getMessage(), ex);
                    });
        });
    }

    // Convenience template id DTO (subset for diagnostics)
    public static final class TemplateIdInfo {
        private final String packageId;
        private final String moduleName;
        private final String entityName;
        public TemplateIdInfo(String packageId, String moduleName, String entityName) {
            this.packageId = packageId;
            this.moduleName = moduleName;
            this.entityName = entityName;
        }
        public String getPackageId() { return packageId; }
        public String getModuleName() { return moduleName; }
        public String getEntityName() { return entityName; }
    }

    @WithSpan
    public CompletableFuture<TemplateIdInfo> getTemplateIdForPoolCid(String party, String cid) {
        // Best-effort: return known module/entity; packageId not material here
        return CompletableFuture.completedFuture(new TemplateIdInfo("", "AMM.Pool", "Pool"));
    }

    @WithSpan
    public CompletableFuture<Long> getLedgerEndOffset() {
        CompletableFuture<Long> fut = new CompletableFuture<>();
        stateService.getLedgerEnd(StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                    @Override public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                        fut.complete(response.getOffset());
                    }
                    @Override public void onError(Throwable t) { fut.completeExceptionally(t); }
                    @Override public void onCompleted() { }
                });
        return fut;
    }

    // Canton 3.4.7: Changed from getLastTxTree to getLastTransaction
    public Optional<TransactionOuterClass.Transaction> getLastTransaction() {
        return Optional.ofNullable(lastTxn);
    }

    @WithSpan
    public CompletableFuture<Void> uploadDar(byte[] darBytes) {
        var req = PackageManagementServiceOuterClass.UploadDarFileRequest.newBuilder()
                .setDarFile(com.google.protobuf.ByteString.copyFrom(darBytes))
                .build();
        return toCompletableFuture(pkg.uploadDarFile(req)).thenApply(resp -> null);
    }

    /**
     * Canton 3.4.7: Changed from exerciseAndGetTransactionTree to exerciseAndGetTransaction
     * Returns flat Transaction instead of TransactionTree
     */
    @WithSpan
    public <T extends Template, C extends Choice<T, ?>> CompletableFuture<TransactionOuterClass.Transaction>
    exerciseAndGetTransaction(
            ContractId<T> contractId,
            C choice,
            String commandId,
            List<String> actAsParties,
            List<String> readAsParties
    ) {
        var ctx = tracingCtx(logger, "Exercising choice (flat transaction)",
                "commandId", commandId,
                "contractId", contractId.getContractId,
                "choiceName", choice.choiceName(),
                "templateId", choice.templateId().toString(),
                "actAsParties", String.join(",", actAsParties),
                "readAsParties", String.join(",", readAsParties),
                "applicationId", APP_ID
        );
        return trace(ctx, () -> {
            CommandsOuterClass.Command.Builder cmdBuilder = CommandsOuterClass.Command.newBuilder();
            ValueOuterClass.Value payload =
                    dto2Proto.choiceArgument(choice.templateId(), choice.choiceName()).convert(choice);
            cmdBuilder.getExerciseBuilder()
                    .setTemplateId(toNameIdentifier(choice.templateId()))
                    .setContractId(contractId.getContractId)
                    .setChoice(choice.choiceName())
                    .setChoiceArgument(payload);

            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(commandId)
                    .setUserId(appProviderParty)
                    .addAllActAs(actAsParties)
                    .addAllReadAs(readAsParties)
                    .addCommands(cmdBuilder.build());

            // Canton 3.4.7: Add EventFormat
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(actAsParties.get(0), TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            // Canton 3.4.7: Use SubmitAndWaitForTransactionRequest
            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            // Canton 3.4.7: Use submitAndWaitForTransaction instead of submitAndWaitForTransactionTree
            return toCompletableFuture(commands.submitAndWaitForTransaction(request))
                    .thenApply(response -> {
                        lastTxn = response.getTransaction();
                        return lastTxn;
                    });
        });
    }

    /**
     * Simple wrapper for active contract data from Ledger API
     */
    public static class ActiveContract<T extends Template> {
        public final ContractId<T> contractId;
        public final T payload;

        public ActiveContract(ContractId<T> contractId, T payload) {
            this.contractId = contractId;
            this.payload = payload;
        }
    }

    private static <T> CompletableFuture<T> toCompletableFuture(ListenableFuture<T> listenableFuture) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        Futures.addCallback(listenableFuture, new FutureCallback<>() {
            @Override
            public void onSuccess(T result) {
                completableFuture.complete(result);
            }

            @Override
            public void onFailure(@Nonnull Throwable t) {
                completableFuture.completeExceptionally(t);
            }
        }, MoreExecutors.directExecutor());
        return completableFuture;
    }

    private static ValueOuterClass.Identifier toIdentifier(Identifier id) {
        String packageId = null;
        try {
            packageId = (String) Identifier.class.getMethod("packageId").invoke(id);
        } catch (ReflectiveOperationException ignored) {
            // Older Identifier versions may not expose packageId(); fallback below.
        }
        if (packageId == null || packageId.isBlank()) {
            packageId = id.packageNameAsPackageId();
        }
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(packageId)
                .setModuleName(id.moduleName())
                .setEntityName(id.entityName())
                .build();
    }

    /**
     * Build a wire Identifier that references the template by package-NAME (#name) rather than a
     * concrete package-id. Canton 3.5 (Splice 0.6.x) removed the package-id reference format from
     * template-id fields on the Ledger API — ACS template filters AND create/exercise command
     * template-ids must use #package-name. Reuses the codegen Identifier's packageNameAsPackageId()
     * (the same helper toIdentifier falls back to); module+entity are preserved. The result decoder
     * keeps using the codegen Identifier, so typed decoding is unaffected.
     */
    private static ValueOuterClass.Identifier toNameIdentifier(Identifier id) {
        return ValueOuterClass.Identifier.newBuilder()
                .setPackageId(id.packageNameAsPackageId())
                .setModuleName(id.moduleName())
                .setEntityName(id.entityName())
                .build();
    }

    private boolean matchesInterface(ValueOuterClass.Identifier candidate, ValueOuterClass.Identifier target) {
        if (!candidate.getModuleName().equals(target.getModuleName())) {
            return false;
        }
        if (!candidate.getEntityName().equals(target.getEntityName())) {
            return false;
        }
        // Accept differing package IDs across deployments when module/entity match.
        return true;
    }

    public record InterfaceViewResult(
            String contractId,
            ValueOuterClass.Record viewValue,
            ValueOuterClass.Record createArguments
    ) { }

    /**
     * Raw active contract view for debug flows.
     */
    public record RawActiveContract(
            String contractId,
            ValueOuterClass.Identifier templateId,
            ValueOuterClass.Record createArguments,
            com.google.protobuf.ByteString createdEventBlob
    ) { }

    /**
     * Raw active contract with createdEventBlob and synchronizerId.
     * Used by OpenMiningRoundRefreshService to build replacement DisclosedContracts
     * from the local participant's authoritative ACS.
     */
    public record RawActiveContractWithBlob(
            String contractId,
            ValueOuterClass.Identifier templateId,
            ValueOuterClass.Record createArguments,
            com.google.protobuf.ByteString createdEventBlob,
            String synchronizerId
    ) { }


    /**
     * Fetch all active contracts visible to a party using a wildcard filter (no template/interface restriction).
     */
    @WithSpan
    public CompletableFuture<List<RawActiveContract>> getActiveContractsRawForParty(final String party) {
        var ctx = tracingCtx(logger, "Getting active contracts (raw)", "party", party);
        return trace(ctx, () -> {
            CompletableFuture<List<RawActiveContract>> resultFuture = new CompletableFuture<>();

            stateService.getLedgerEnd(
                    StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                    new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                        @Override
                        public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                            long ledgerEndOffset = response.getOffset();

                            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                                    .putFiltersByParty(party,
                                            TransactionFilterOuterClass.Filters.newBuilder()
                                                    .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                            .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                                            .build())
                                                    .build())
                                    .build();

                            StateServiceOuterClass.GetActiveContractsRequest request =
                                    StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                                            .setEventFormat(eventFormat)
                                            .setActiveAtOffset(ledgerEndOffset)
                                            .build();

                            List<RawActiveContract> contracts = new ArrayList<>();

                            stateService.getActiveContracts(request, new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetActiveContractsResponse>() {
                                @Override
                                public void onNext(StateServiceOuterClass.GetActiveContractsResponse response) {
                                    if (response.hasActiveContract()) {
                                        EventOuterClass.CreatedEvent created = response.getActiveContract().getCreatedEvent();
                                        ValueOuterClass.Identifier templateId = created.getTemplateId();
                                        ValueOuterClass.Record args = created.hasCreateArguments()
                                                ? created.getCreateArguments()
                                                : null;
                                        com.google.protobuf.ByteString blob = com.google.protobuf.ByteString.EMPTY;
                                        contracts.add(new RawActiveContract(created.getContractId(), templateId, args, blob));
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    logger.error("Error fetching raw active contracts for {}: {}", party, t.getMessage());
                                    resultFuture.completeExceptionally(t);
                                }

                                @Override
                                public void onCompleted() {
                                    logger.info("Fetched {} raw active contracts for {}", contracts.size(), party);
                                    resultFuture.complete(contracts);
                                }
                            });
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("Error getting ledger end: {}", t.getMessage());
                            resultFuture.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            // no-op
                        }
                    });

            return resultFuture;
        });
    }

    /**
     * Fetch all active contracts visible to a party using a wildcard filter,
     * WITH includeCreatedEventBlob=true and synchronizerId captured.
     * Used by OpenMiningRoundRefreshService to build replacement DisclosedContracts.
     */
    @WithSpan
    public CompletableFuture<List<RawActiveContractWithBlob>> getActiveContractsWithBlobForParty(final String party) {
        var ctx = tracingCtx(logger, "Getting active contracts (raw+blob)", "party", party);
        return trace(ctx, () -> {
            CompletableFuture<List<RawActiveContractWithBlob>> resultFuture = new CompletableFuture<>();

            stateService.getLedgerEnd(
                    StateServiceOuterClass.GetLedgerEndRequest.newBuilder().build(),
                    new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetLedgerEndResponse>() {
                        @Override
                        public void onNext(StateServiceOuterClass.GetLedgerEndResponse response) {
                            long ledgerEndOffset = response.getOffset();

                            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                                    .putFiltersByParty(party,
                                            TransactionFilterOuterClass.Filters.newBuilder()
                                                    .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                                            .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder()
                                                                    .setIncludeCreatedEventBlob(true)
                                                                    .build())
                                                            .build())
                                                    .build())
                                    .build();

                            StateServiceOuterClass.GetActiveContractsRequest request =
                                    StateServiceOuterClass.GetActiveContractsRequest.newBuilder()
                                            .setEventFormat(eventFormat)
                                            .setActiveAtOffset(ledgerEndOffset)
                                            .build();

                            List<RawActiveContractWithBlob> contracts = new ArrayList<>();

                            stateService.getActiveContracts(request, new io.grpc.stub.StreamObserver<StateServiceOuterClass.GetActiveContractsResponse>() {
                                @Override
                                public void onNext(StateServiceOuterClass.GetActiveContractsResponse response) {
                                    if (response.hasActiveContract()) {
                                        StateServiceOuterClass.ActiveContract ac = response.getActiveContract();
                                        EventOuterClass.CreatedEvent created = ac.getCreatedEvent();
                                        contracts.add(new RawActiveContractWithBlob(
                                                created.getContractId(),
                                                created.getTemplateId(),
                                                created.hasCreateArguments() ? created.getCreateArguments() : null,
                                                created.getCreatedEventBlob(),
                                                ac.getSynchronizerId()
                                        ));
                                    }
                                }

                                @Override
                                public void onError(Throwable t) {
                                    logger.error("Error fetching active contracts with blob for {}: {}", party, t.getMessage());
                                    resultFuture.completeExceptionally(t);
                                }

                                @Override
                                public void onCompleted() {
                                    logger.debug("Fetched {} active contracts with blob for {}", contracts.size(), party);
                                    resultFuture.complete(contracts);
                                }
                            });
                        }

                        @Override
                        public void onError(Throwable t) {
                            logger.error("Error getting ledger end: {}", t.getMessage());
                            resultFuture.completeExceptionally(t);
                        }

                        @Override
                        public void onCompleted() {
                            // no-op
                        }
                    });

            return resultFuture;
        });
    }

    /**
     * Fetch active contracts visible to a party filtered by template module/entity,
     * WITH includeCreatedEventBlob=true and synchronizerId captured.
     *
     * Uses WildcardFilter + client-side filtering because Canton's TemplateFilter
     * requires a non-empty packageId (no wildcard package matching).
     * Used by LocalFallbackChoiceContextProvider for targeted OMR/AmuletRules queries.
     */
    @WithSpan
    public CompletableFuture<List<RawActiveContractWithBlob>> getActiveContractsWithBlobForPartyByTemplate(
            final String party, final String moduleName, final String entityName) {
        var ctx = tracingCtx(logger, "Getting active contracts (filtered+blob)",
                "party", party, "template", moduleName + ":" + entityName);
        return trace(ctx, () -> {
            // Use wildcard query, then filter client-side by module/entity
            return getActiveContractsWithBlobForParty(party).thenApply(allContracts -> {
                List<RawActiveContractWithBlob> filtered = allContracts.stream()
                        .filter(ac -> ac.templateId() != null
                                && moduleName.equals(ac.templateId().getModuleName())
                                && entityName.equals(ac.templateId().getEntityName()))
                        .toList();
                logger.debug("Fetched {} active {} contracts with blob for {} (from {} total)",
                        filtered.size(), moduleName + ":" + entityName, party, allContracts.size());
                return filtered;
            });
        });
    }

    /**
     * Exercise a choice using raw identifiers (no generated bindings).
     */
    @WithSpan
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> exerciseRaw(
            final ValueOuterClass.Identifier templateId,
            final String contractId,
            final String choiceName,
            final ValueOuterClass.Record choiceArgs,
            final List<String> actAs,
            final List<String> readAs,
            final List<CommandsOuterClass.DisclosedContract> disclosedContracts
    ) {
        return exerciseRaw(templateId, contractId, choiceName, choiceArgs, actAs, readAs, disclosedContracts, null);
    }

    /**
     * Exercise a choice using raw identifiers (no generated bindings) with optional synchronizerId.
     */
    @WithSpan
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> exerciseRaw(
            final ValueOuterClass.Identifier templateId,
            final String contractId,
            final String choiceName,
            final ValueOuterClass.Record choiceArgs,
            final List<String> actAs,
            final List<String> readAs,
            final List<CommandsOuterClass.DisclosedContract> disclosedContracts,
            final String synchronizerId
    ) {
        var ctx = tracingCtx(logger, "Exercising raw choice",
                "templateId", templateId.toString(),
                "contractId", contractId,
                "choiceName", choiceName,
                "actAs", String.join(",", actAs),
                "readAs", String.join(",", readAs));
        return trace(ctx, () -> {
            CommandsOuterClass.Command exerciseCommand = CommandsOuterClass.Command.newBuilder()
                    .setExercise(CommandsOuterClass.ExerciseCommand.newBuilder()
                            .setTemplateId(templateId)
                            .setContractId(contractId)
                            .setChoice(choiceName)
                            .setChoiceArgument(ValueOuterClass.Value.newBuilder().setRecord(choiceArgs).build())
                            .build())
                    .build();

            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(java.util.UUID.randomUUID().toString())
                    .setUserId(appProviderParty)
                    .addAllActAs(actAs)
                    .addAllReadAs(readAs)
                    .addCommands(exerciseCommand);

            if (disclosedContracts != null && !disclosedContracts.isEmpty()) {
                commandsBuilder.addAllDisclosedContracts(disclosedContracts);
            }
            if (synchronizerId != null && !synchronizerId.isBlank()) {
                commandsBuilder.setSynchronizerId(synchronizerId);
            }

            // Ensure filtersByParty is populated so the ledger accepts the request
            String partyForFilters = !actAs.isEmpty()
                    ? actAs.get(0)
                    : (!readAs.isEmpty() ? readAs.get(0) : appProviderParty);
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(partyForFilters, TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();
            if (logger.isDebugEnabled()) {
                logger.debug("exerciseRaw transaction format: actAsParty={}, filtersByPartyCount={}",
                        partyForFilters,
                        eventFormat.getFiltersByPartyCount());
            }

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            return toCompletableFuture(commands.submitAndWaitForTransaction(request));
        });
    }

    /**
     * Exercise a choice with a step label for structured logging.
     */
    @WithSpan
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> exerciseRawWithLabel(
            final String step,
            final ValueOuterClass.Identifier templateId,
            final String contractId,
            final String choiceName,
            final ValueOuterClass.Record choiceArgs,
            final List<String> actAs,
            final List<String> readAs,
            final List<CommandsOuterClass.DisclosedContract> disclosedContracts,
            final String synchronizerId
    ) {
        logger.info("[LedgerApi] submit step={} actAs={} readAs={} synchronizerId={} contractId={} choice={}",
                step,
                actAs != null ? String.join(",", actAs) : "",
                readAs != null ? String.join(",", readAs) : "",
                synchronizerId,
                contractId,
                choiceName);
        return exerciseRaw(templateId, contractId, choiceName, choiceArgs, actAs, readAs, disclosedContracts, synchronizerId);
    }


    /**
     * Create a contract using raw identifiers (no generated bindings).
     */
    
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> createRaw(
            final ValueOuterClass.Identifier templateId,
            final ValueOuterClass.Record createArgs,
            final List<String> actAs,
            final List<String> readAs
    ) {
        return createRaw(templateId, createArgs, actAs, readAs, null);
    }

    /**
     * Create a contract using raw identifiers with optional synchronizerId.
     */
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> createRaw(
            final ValueOuterClass.Identifier templateId,
            final ValueOuterClass.Record createArgs,
            final List<String> actAs,
            final List<String> readAs,
            final String synchronizerId
    ) {
        var ctx = tracingCtx(logger, "Creating raw contract",
                "templateId", templateId.toString(),
                "actAs", String.join(",", actAs),
                "readAs", String.join(",", readAs));
        return trace(ctx, () -> {
            if (actAs == null || actAs.isEmpty()) {
                throw new IllegalArgumentException("actAs must contain at least one party");
            }
            CommandsOuterClass.Command createCommand = CommandsOuterClass.Command.newBuilder()
                    .setCreate(CommandsOuterClass.CreateCommand.newBuilder()
                            .setTemplateId(templateId)
                            .setCreateArguments(createArgs)
                            .build())
                    .build();

            CommandsOuterClass.Commands.Builder commandsBuilder = CommandsOuterClass.Commands.newBuilder()
                    .setCommandId(java.util.UUID.randomUUID().toString())
                    .setUserId(appProviderParty)
                    .addAllActAs(actAs)
                    .addAllReadAs(readAs)
                    .addCommands(createCommand);
            if (synchronizerId != null && !synchronizerId.isBlank()) {
                commandsBuilder.setSynchronizerId(synchronizerId);
            }

            // Provide event format so the ledger knows which parties' events to return
            TransactionFilterOuterClass.EventFormat eventFormat = TransactionFilterOuterClass.EventFormat.newBuilder()
                    .putFiltersByParty(actAs.get(0), TransactionFilterOuterClass.Filters.newBuilder()
                            .addCumulative(TransactionFilterOuterClass.CumulativeFilter.newBuilder()
                                    .setWildcardFilter(TransactionFilterOuterClass.WildcardFilter.newBuilder().build())
                                    .build())
                            .build())
                    .build();

            CommandServiceOuterClass.SubmitAndWaitForTransactionRequest request =
                    CommandServiceOuterClass.SubmitAndWaitForTransactionRequest.newBuilder()
                            .setCommands(commandsBuilder.build())
                            .setTransactionFormat(TransactionFilterOuterClass.TransactionFormat.newBuilder()
                                    .setEventFormat(eventFormat)
                                    .setTransactionShape(TransactionFilterOuterClass.TransactionShape.TRANSACTION_SHAPE_LEDGER_EFFECTS)
                                    .build())
                            .build();

            return toCompletableFuture(commands.submitAndWaitForTransaction(request))
                    .thenApply(response -> {
                        lastTxn = response.getTransaction();
                        return response;
                    });
        });
    }

    /**
     * Create a contract with a step label for structured logging.
     */
    public CompletableFuture<CommandServiceOuterClass.SubmitAndWaitForTransactionResponse> createRawWithLabel(
            final String step,
            final ValueOuterClass.Identifier templateId,
            final ValueOuterClass.Record createArgs,
            final List<String> actAs,
            final List<String> readAs,
            final String synchronizerId
    ) {
        logger.info("[LedgerApi] submit step={} actAs={} readAs={} synchronizerId={}",
                step,
                actAs != null ? String.join(",", actAs) : "",
                readAs != null ? String.join(",", readAs) : "",
                synchronizerId);
        return createRaw(templateId, createArgs, actAs, readAs, synchronizerId);
    }

    private static class Interceptor implements ClientInterceptor {
        private final Metadata.Key<String> AUTHORIZATION_HEADER = Metadata.Key.of("Authorization", Metadata.ASCII_STRING_MARSHALLER);
        private final TokenProvider tokenProvider;

        public Interceptor(TokenProvider tokenProvider) {
            this.tokenProvider = tokenProvider;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
            ClientCall<ReqT, RespT> clientCall = next.newCall(method, callOptions);
            return new ForwardingClientCall.SimpleForwardingClientCall<>(clientCall) {
                @Override
                public void start(Listener<RespT> responseListener, Metadata headers) {
                    headers.put(AUTHORIZATION_HEADER, "Bearer " + tokenProvider.getToken());
                    super.start(responseListener, headers);
                }
            };
        }
    }
}
