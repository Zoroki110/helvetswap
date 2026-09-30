package com.digitalasset.quickstart.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class TransactionHistoryService {

    private static final Logger LOG = LoggerFactory.getLogger(TransactionHistoryService.class);
    private static final int MAX_RECORDS = 1000;
    private final Deque<TransactionHistoryEntry> history = new ArrayDeque<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${clearportx.history.path:/tmp/clearportx-history.json}")
    private String historyPath;

    @PostConstruct
    public void init() {
        loadHistory();
    }

    public synchronized TransactionHistoryEntry recordPoolCreation(String poolId,
                                                                   String poolCid,
                                                                   String tokenA,
                                                                   String tokenB,
                                                                   BigDecimal bootstrapA,
                                                                   BigDecimal bootstrapB,
                                                                   String operator,
                                                                   String poolParty) {
        TransactionHistoryEntry entry = baseEntry("Pool Creation Transaction", "POOL_CREATION", poolId, poolCid, null);
        entry.tokenA = tokenA;
        entry.tokenB = tokenB;
        entry.amountADesired = formatAmount(bootstrapA);
        entry.amountBDesired = formatAmount(bootstrapB);
        entry.status = "settled";
        entry.eventTimeline.add(new TimelineItem("pool-created",
                "Pool Created",
                String.format(Locale.ROOT, "Pool %s created by %s for %s", poolId, operator, poolParty),
                "completed",
                entry.createdAt));
        append(entry);
        return entry;
    }

    public synchronized TransactionHistoryEntry recordAddLiquidity(String poolId,
                                                                   String poolCid,
                                                                   String tokenA,
                                                                   String tokenB,
                                                                   BigDecimal amountA,
                                                                   BigDecimal amountB,
                                                                   BigDecimal minLp,
                                                                   BigDecimal lpMinted,
                                                                   String actorParty) {
        TransactionHistoryEntry entry = baseEntry("AddLiquidity Transaction", "ADD_LIQUIDITY", poolId, poolCid, null);
        entry.tokenA = tokenA;
        entry.tokenB = tokenB;
        entry.amountADesired = formatAmount(amountA);
        entry.amountBDesired = formatAmount(amountB);
        entry.minLpAmount = formatAmount(minLp);
        entry.lpMintedAmount = formatAmount(lpMinted);
        entry.lpTokenSymbol = "LP-" + tokenA + "-" + tokenB;
        entry.status = "settled";
        entry.eventTimeline.add(new TimelineItem("add-liquidity-settled",
                "Add Liquidity Settled",
                String.format(Locale.ROOT, "%s added %s %s / %s %s", actorParty, formatAmount(amountA), tokenA, formatAmount(amountB), tokenB),
                "completed",
                entry.createdAt));
        append(entry);
        return entry;
    }

    public synchronized TransactionHistoryEntry recordSwap(String poolId,
                                                           String poolCid,
                                                           String inputSymbol,
                                                           String outputSymbol,
                                                           BigDecimal amountIn,
                                                           BigDecimal amountOut,
                                                           String actorParty) {
        return recordSwap(null, poolId, poolCid, inputSymbol, outputSymbol, amountIn, amountOut, actorParty);
    }

    public synchronized TransactionHistoryEntry recordSwap(String eventId,
                                                           String poolId,
                                                           String poolCid,
                                                           String inputSymbol,
                                                           String outputSymbol,
                                                           BigDecimal amountIn,
                                                           BigDecimal amountOut,
                                                           String actorParty) {
        if (eventId != null && !eventId.isBlank()) {
            TransactionHistoryEntry existing = findById(eventId);
            if (existing != null) {
                return existing;
            }
        }
        TransactionHistoryEntry entry = baseEntry("Swap Transaction", "SWAP", poolId, poolCid, eventId);
        entry.tokenA = inputSymbol;
        entry.tokenB = outputSymbol;
        entry.amountADesired = formatAmount(amountIn);
        entry.amountBDesired = formatAmount(amountOut);
        entry.status = "settled";
        entry.eventTimeline.add(new TimelineItem("swap-settled",
                "Swap Settled",
                String.format(Locale.ROOT, "%s swapped %s %s → %s %s", actorParty, formatAmount(amountIn), inputSymbol, formatAmount(amountOut), outputSymbol),
                "completed",
                entry.createdAt));
        append(entry);
        return entry;
    }

    public synchronized List<TransactionHistoryEntry> getRecent(int limit) {
        int size = Math.min(limit, history.size());
        List<TransactionHistoryEntry> list = new ArrayList<>(size);
        int i = 0;
        for (TransactionHistoryEntry entry : history) {
            list.add(entry);
            i++;
            if (i >= size) break;
        }
        return list;
    }

    private void append(TransactionHistoryEntry entry) {
        history.addFirst(entry);
        while (history.size() > MAX_RECORDS) {
            history.removeLast();
        }
        persistHistory();
    }

    private static TransactionHistoryEntry baseEntry(String title, String type, String poolId, String poolCid, String entryId) {
        TransactionHistoryEntry entry = new TransactionHistoryEntry();
        entry.id = (entryId != null && !entryId.isBlank()) ? entryId : UUID.randomUUID().toString();
        entry.title = title;
        entry.type = type;
        entry.createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        entry.poolId = poolId;
        entry.contractId = poolCid;
        entry.status = "pending";
        entry.eventTimeline = new ArrayList<>();
        return entry;
    }

    private static String formatAmount(BigDecimal value) {
        if (value == null) {
            return "0";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private TransactionHistoryEntry findById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (TransactionHistoryEntry entry : history) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    private synchronized void loadHistory() {
        if (historyPath == null || historyPath.isBlank()) {
            return;
        }
        Path path = Paths.get(historyPath);
        if (!Files.exists(path)) {
            return;
        }
        try {
            byte[] raw = Files.readAllBytes(path);
            List<TransactionHistoryEntry> stored = mapper.readValue(raw, new TypeReference<List<TransactionHistoryEntry>>() {});
            history.clear();
            for (TransactionHistoryEntry entry : stored) {
                history.addLast(entry);
            }
            LOG.info("Loaded {} history entries from {}", history.size(), historyPath);
        } catch (Exception e) {
            LOG.warn("Failed to load transaction history from {}: {}", historyPath, e.getMessage());
        }
    }

    private synchronized void persistHistory() {
        if (historyPath == null || historyPath.isBlank()) {
            return;
        }
        Path path = Paths.get(historyPath);
        try {
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            byte[] payload = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new ArrayList<>(history));
            Files.write(path, payload);
        } catch (IOException e) {
            LOG.warn("Failed to persist transaction history to {}: {}", historyPath, e.getMessage());
        }
    }

    public static class TransactionHistoryEntry {
        public String id;
        public String title;
        public String type;
        public String status;
        public String createdAt;
        public String expiresAt;
        public String tokenA;
        public String tokenB;
        public String amountADesired;
        public String amountBDesired;
        public String minLpAmount;
        public String lpTokenSymbol;
        public String lpMintedAmount;
        public String poolId;
        public String contractId;
        public List<TimelineItem> eventTimeline = new ArrayList<>();

        public TransactionHistoryEntry() {}
    }

    public static class TimelineItem {
        public String id;
        public String title;
        public String description;
        public String status;
        public String timestamp;

        public TimelineItem() {}

        public TimelineItem(String id, String title, String description, String status, String timestamp) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.status = status;
            this.timestamp = timestamp;
        }
    }
}

