package com.digitalasset.quickstart.service;

import com.daml.ledger.api.v2.CommandsOuterClass;
import com.daml.ledger.api.v2.ValueOuterClass;
import com.digitalasset.quickstart.config.LedgerConfig;
import com.digitalasset.quickstart.ledger.LedgerApi;
import com.digitalasset.quickstart.service.OpenMiningRoundRefreshService.ScanOmrEntry;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for Phase 3F.10 Step A safety-margin selection rule.
 *
 * Covers:
 *  - selectsNewerWhenOldestWithinMargin   — basic margin filter
 *  - rejectsQueuedOmrEvenIfLatestOpensAt  — OQ2 lock (`opensAt <= now`)
 *  - throwsOnAllWithinMargin              — Option C retry-then-dead-set
 *  - syncMismatchKeepsOriginal            — OQ4 graceful degradation
 */
class OpenMiningRoundRefreshServiceSafetyMarginTest {

    private static final String OMR_TEMPLATE_ID = "pkg-123:Splice.Round:OpenMiningRound";
    private static final String SYNC_ID = "global-domain::test-sync-1";
    private static final String FAKE_BLOB = Base64.getEncoder().encodeToString("blob".getBytes());

    private LedgerApi ledgerApi;
    private LedgerConfig ledgerConfig;

    @BeforeEach
    void setUp() {
        ledgerApi = mock(LedgerApi.class);
        ledgerConfig = mock(LedgerConfig.class);
    }

    private static ScanOmrEntry omr(String cidSuffix, Instant opensAt, Instant closesAt, long round) {
        return new ScanOmrEntry(
                "00cid" + cidSuffix,
                FAKE_BLOB,
                OMR_TEMPLATE_ID,
                SYNC_ID,
                opensAt,
                closesAt,
                round
        );
    }

    private static CommandsOuterClass.DisclosedContract existingOmrEntry(String cidSuffix) {
        ValueOuterClass.Identifier tid = ValueOuterClass.Identifier.newBuilder()
                .setPackageId("pkg-old")
                .setModuleName("Splice.Round")
                .setEntityName("OpenMiningRound")
                .build();
        return CommandsOuterClass.DisclosedContract.newBuilder()
                .setTemplateId(tid)
                .setContractId("00stale" + cidSuffix)
                .setCreatedEventBlob(ByteString.copyFromUtf8("stale-blob"))
                .setSynchronizerId(SYNC_ID)
                .build();
    }

    // ─── Test 1: selectBest — older active within margin → newer active wins ───
    @Test
    void selectsNewerWhenOldestWithinMargin() {
        Instant now = Instant.parse("2026-05-08T08:18:55Z");
        // Round 43408: opens 07:59:21, closes 08:19:21 → 26s margin, FAILS (< 30s)
        // Round 43409: opens 08:09:39, closes 08:29:39 → 644s margin, PASSES
        // Round 43410: opens 08:19:56 (after now), closes 08:39:56 → not yet open
        ScanOmrEntry oldest = omr("a", Instant.parse("2026-05-08T07:59:21Z"),
                Instant.parse("2026-05-08T08:19:21Z"), 43408);
        ScanOmrEntry middle = omr("b", Instant.parse("2026-05-08T08:09:39Z"),
                Instant.parse("2026-05-08T08:29:39Z"), 43409);
        ScanOmrEntry queued = omr("c", Instant.parse("2026-05-08T08:19:56Z"),
                Instant.parse("2026-05-08T08:39:56Z"), 43410);

        ScanOmrEntry best = OpenMiningRoundRefreshService.selectBest(
                List.of(oldest, middle, queued), now);

        assertNotNull(best);
        assertEquals(43409L, best.roundNumber());
        assertEquals("00cidb", best.contractId());
    }

    // ─── Test 2: OQ2 lock — queued OMR has latest opensAt but is rejected ───
    @Test
    void rejectsQueuedOmrEvenIfLatestOpensAt() {
        Instant now = Instant.parse("2026-05-08T08:15:00Z");
        // Round 43408 active+safe: opens 07:59:21, closes 08:19:21 → 261s margin
        // Round 43409 active+safe: opens 08:09:39, closes 08:29:39 → 879s margin (latest active opensAt)
        // Round 43410 QUEUED: opens 08:19:56 (after now), closes 08:39:56 — has latest opensAt overall
        ScanOmrEntry oldest = omr("a", Instant.parse("2026-05-08T07:59:21Z"),
                Instant.parse("2026-05-08T08:19:21Z"), 43408);
        ScanOmrEntry middle = omr("b", Instant.parse("2026-05-08T08:09:39Z"),
                Instant.parse("2026-05-08T08:29:39Z"), 43409);
        ScanOmrEntry queued = omr("c", Instant.parse("2026-05-08T08:19:56Z"),
                Instant.parse("2026-05-08T08:39:56Z"), 43410);

        ScanOmrEntry best = OpenMiningRoundRefreshService.selectBest(
                List.of(oldest, middle, queued), now);

        // The queued OMR has latest opensAt, but opensAt > now → must be rejected.
        assertNotNull(best);
        assertNotEquals(43410L, best.roundNumber(),
                "queued OMR must NEVER be selected (opensAt > now)");
        assertEquals(43409L, best.roundNumber(),
                "middle (active, latest opensAt among open OMRs) must be selected");
    }

    // ─── Test 3: all within margin → applySafetyMargin throws after retry ───
    @Test
    void throwsOnAllWithinMargin() throws Exception {
        // Build a service whose fetchScanOmrResponse() returns "all-fail" twice.
        OpenMiningRoundRefreshService service =
                new OpenMiningRoundRefreshService(ledgerApi, ledgerConfig, Optional.empty());
        OpenMiningRoundRefreshService spy = spy(service);

        // Two OMRs, both within 30s of close, plus one queued.
        Instant now = Instant.now();
        String allFailBody = """
                {
                  "open_mining_rounds": {
                    "00cidA": {
                      "contract": {
                        "template_id": "pkg:Splice.Round:OpenMiningRound",
                        "contract_id": "00cidA",
                        "payload": {
                          "round": {"number": "1"},
                          "opensAt": "%s",
                          "targetClosesAt": "%s"
                        },
                        "created_event_blob": "%s"
                      },
                      "domain_id": "%s"
                    },
                    "00cidB": {
                      "contract": {
                        "template_id": "pkg:Splice.Round:OpenMiningRound",
                        "contract_id": "00cidB",
                        "payload": {
                          "round": {"number": "2"},
                          "opensAt": "%s",
                          "targetClosesAt": "%s"
                        },
                        "created_event_blob": "%s"
                      },
                      "domain_id": "%s"
                    }
                  }
                }
                """.formatted(
                now.minusSeconds(60).toString(),  now.plusSeconds(10).toString(), FAKE_BLOB, SYNC_ID,
                now.minusSeconds(30).toString(),  now.plusSeconds(20).toString(), FAKE_BLOB, SYNC_ID);

        doReturn(allFailBody).when(spy).fetchScanOmrResponse();

        List<CommandsOuterClass.DisclosedContract> disclosed = List.of(existingOmrEntry("z"));

        ExecutionException ex = assertThrows(ExecutionException.class,
                () -> spy.applySafetyMarginFromScan(disclosed, SYNC_ID, "req-test").get());

        assertTrue(ex.getCause() instanceof OmrSafetyMarginFailException,
                "cause must be OmrSafetyMarginFailException, got: " + ex.getCause());
        OmrSafetyMarginFailException safety = (OmrSafetyMarginFailException) ex.getCause();
        assertEquals("req-test", safety.getRequestId());
        assertEquals("OMR_SAFETY_MARGIN_FAIL", safety.getErrorClass());

        // Two fetches: first pass + retry.
        verify(spy, times(2)).fetchScanOmrResponse();
    }

    // ─── Test 4: synchronizerId mismatch → original disclosed returned + WARN ───
    @Test
    void syncMismatchKeepsOriginal() throws Exception {
        OpenMiningRoundRefreshService service =
                new OpenMiningRoundRefreshService(ledgerApi, ledgerConfig, Optional.empty());
        OpenMiningRoundRefreshService spy = spy(service);

        Instant now = Instant.now();
        // One OMR, safe margin, but with a DIFFERENT synchronizerId from what caller has.
        String body = """
                {
                  "open_mining_rounds": {
                    "00cidX": {
                      "contract": {
                        "template_id": "pkg:Splice.Round:OpenMiningRound",
                        "contract_id": "00cidX",
                        "payload": {
                          "round": {"number": "100"},
                          "opensAt": "%s",
                          "targetClosesAt": "%s"
                        },
                        "created_event_blob": "%s"
                      },
                      "domain_id": "OTHER-DOMAIN::xyz"
                    }
                  }
                }
                """.formatted(
                now.minusSeconds(60).toString(), now.plusSeconds(600).toString(), FAKE_BLOB);

        doReturn(body).when(spy).fetchScanOmrResponse();

        List<CommandsOuterClass.DisclosedContract> disclosed = List.of(existingOmrEntry("orig"));

        List<CommandsOuterClass.DisclosedContract> result =
                spy.applySafetyMarginFromScan(disclosed, SYNC_ID, "req-mismatch").get();

        // Mismatch: original disclosed is returned unchanged (graceful degradation).
        assertSame(disclosed, result, "original disclosed list must be returned on sync mismatch");
        verify(spy, times(1)).fetchScanOmrResponse();
    }
}
