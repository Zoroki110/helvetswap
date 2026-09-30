// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.ValueOuterClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Cure (iv.1) outbound payout memo emission tests.
 *
 * Tests target the package-private helpers `buildSwapMemoJson` and
 * `injectSwapMemoIntoTransferFactoryArg` on {@link PayoutService}. Instantiates
 * PayoutService with mock dependencies (none of the helpers depend on the
 * injected fields beyond the ObjectMapper instance which is field-initialized).
 */
class PayoutMemoEmissionTest {

    private static final String INBOUND_MEMO_ATOB = "{\"v\":1,"
            + "\"requestId\":\"8f3e2d0c-9b1a-4e3f-a4d2-7e9c1f5b0a3e\","
            + "\"poolCid\":\"00abc123def456\","
            + "\"direction\":\"AtoB\","
            + "\"minOut\":\"0.0002574569\","
            + "\"receiverParty\":\"auth0_testuser::1220abc\","
            + "\"deadline\":\"2026-05-21T15:30:00.000Z\"}";

    private static final String INBOUND_MEMO_BTOA = "{\"v\":1,"
            + "\"requestId\":\"a1b2c3d4-5678-90ab-cdef-1234567890ab\","
            + "\"poolCid\":\"00xyz789\","
            + "\"direction\":\"BtoA\","
            + "\"minOut\":\"1.5\","
            + "\"receiverParty\":\"auth0_other::1220xyz\","
            + "\"deadline\":\"2026-05-22T10:00:00.000Z\"}";

    private PayoutService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new PayoutService(
                mock(com.digitalasset.quickstart.ledger.LedgerApi.class),
                mock(TemplateSchemaService.class),
                mock(HoldingSelectorService.class),
                mock(com.digitalasset.quickstart.security.AuthUtils.class),
                mock(com.digitalasset.quickstart.config.TemplateSchemaDebugConfig.class),
                mock(com.digitalasset.quickstart.config.LedgerConfig.class),
                ""
        );
        mapper = new ObjectMapper();
    }

    @Test
    void testMemoJsonContainsAll5Fields() throws Exception {
        Optional<String> result = service.buildSwapMemoJson(
                INBOUND_MEMO_ATOB,
                "8f3e2d0c-9b1a-4e3f-a4d2-7e9c1f5b0a3e"
        );
        assertTrue(result.isPresent());
        JsonNode out = mapper.readTree(result.get());
        assertEquals(5, out.size(), "outbound memo must have exactly 5 fields");
        assertTrue(out.has("v"));
        assertTrue(out.has("requestId"));
        assertTrue(out.has("poolCid"));
        assertTrue(out.has("direction"));
        assertTrue(out.has("originator"));
        assertEquals(1, out.get("v").asInt(), "v must be int 1");
        assertEquals("00abc123def456", out.get("poolCid").asText());
    }

    @Test
    void testRequestIdByteEqualToUnprefixedInput() throws Exception {
        String unprefixed = "8f3e2d0c-9b1a-4e3f-a4d2-7e9c1f5b0a3e";
        Optional<String> result = service.buildSwapMemoJson(INBOUND_MEMO_ATOB, unprefixed);
        assertTrue(result.isPresent());
        JsonNode out = mapper.readTree(result.get());
        assertEquals(unprefixed, out.get("requestId").asText(),
                "outbound requestId must be byte-equal to the unprefixed input");
    }

    @Test
    void testDirectionEncodingBothBranches() throws Exception {
        Optional<String> atobResult = service.buildSwapMemoJson(INBOUND_MEMO_ATOB, "req-1");
        Optional<String> btoaResult = service.buildSwapMemoJson(INBOUND_MEMO_BTOA, "req-2");
        assertTrue(atobResult.isPresent());
        assertTrue(btoaResult.isPresent());
        assertEquals("AtoB", mapper.readTree(atobResult.get()).get("direction").asText());
        assertEquals("BtoA", mapper.readTree(btoaResult.get()).get("direction").asText());
    }

    @Test
    void testOriginatorLiteral() throws Exception {
        Optional<String> result = service.buildSwapMemoJson(INBOUND_MEMO_ATOB, "req-x");
        assertTrue(result.isPresent());
        JsonNode out = mapper.readTree(result.get());
        assertEquals("helvetswap-amm", out.get("originator").asText(),
                "originator literal must match SWAP_MEMO_ORIGINATOR constant");
        assertEquals("helvetswap-amm", PayoutService.SWAP_MEMO_ORIGINATOR);
    }

    @Test
    void testMetaKeyConstant() {
        assertEquals("helvetswap.app/swap-memo", PayoutService.SWAP_MEMO_META_KEY,
                "meta-key constant must be the partner-spec'd Splice-style reverse-DNS path");
    }

    @Test
    void testDefensiveOnBadInboundMemo() {
        assertTrue(service.buildSwapMemoJson(null, "req-1").isEmpty(),
                "null inbound memo → empty");
        assertTrue(service.buildSwapMemoJson("", "req-1").isEmpty(),
                "blank inbound memo → empty");
        assertTrue(service.buildSwapMemoJson("not-json{{{", "req-1").isEmpty(),
                "unparseable inbound memo → empty");
        assertTrue(service.buildSwapMemoJson("{\"requestId\":\"x\"}", "req-1").isEmpty(),
                "missing poolCid+direction → empty");
    }

    @Test
    void testInjectSwapMemoNavigatesNestedRecordPath() {
        // Build a minimal TransferFactory_Transfer arg with the nested transfer.meta.values shape.
        ValueOuterClass.Record emptyMeta = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("values")
                        .setValue(ValueOuterClass.Value.newBuilder()
                                .setTextMap(ValueOuterClass.TextMap.newBuilder().build())
                                .build())
                        .build())
                .build();
        ValueOuterClass.Record transfer = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("meta")
                        .setValue(ValueOuterClass.Value.newBuilder().setRecord(emptyMeta).build())
                        .build())
                .build();
        ValueOuterClass.Record arg = ValueOuterClass.Record.newBuilder()
                .addFields(ValueOuterClass.RecordField.newBuilder()
                        .setLabel("transfer")
                        .setValue(ValueOuterClass.Value.newBuilder().setRecord(transfer).build())
                        .build())
                .build();

        ValueOuterClass.Record result = service.injectSwapMemoIntoTransferFactoryArg(arg, "{\"v\":1}");

        // Navigate result back down and assert entry was added.
        ValueOuterClass.Record resultTransfer = result.getFieldsList().stream()
                .filter(f -> "transfer".equals(f.getLabel())).findFirst().get()
                .getValue().getRecord();
        ValueOuterClass.Record resultMeta = resultTransfer.getFieldsList().stream()
                .filter(f -> "meta".equals(f.getLabel())).findFirst().get()
                .getValue().getRecord();
        ValueOuterClass.TextMap resultValues = resultMeta.getFieldsList().stream()
                .filter(f -> "values".equals(f.getLabel())).findFirst().get()
                .getValue().getTextMap();
        assertEquals(1, resultValues.getEntriesCount());
        assertEquals("helvetswap.app/swap-memo", resultValues.getEntries(0).getKey());
        assertEquals("{\"v\":1}", resultValues.getEntries(0).getValue().getText());
    }
}
