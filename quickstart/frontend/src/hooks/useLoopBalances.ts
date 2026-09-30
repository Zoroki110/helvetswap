import { useCallback, useEffect, useState } from "react";
import { walletManager } from "../wallet";

export type LoopBalance = {
  amount: string;
  decimals: number;
};

export type LoopBalanceMap = Record<string, LoopBalance>;

type UseLoopBalancesOptions = {
  refreshIntervalMs?: number;
  paused?: boolean;
};

type UseLoopBalancesResult = {
  balances: LoopBalanceMap;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
};

const DEFAULT_DECIMALS = 10;

export function useLoopBalances(
  partyId: string | null | undefined,
  walletType: string | null | undefined,
  options: UseLoopBalancesOptions = {}
): UseLoopBalancesResult {
  const { refreshIntervalMs = 15000, paused = false } = options;
  const [balances, setBalances] = useState<LoopBalanceMap>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const fetchBalances = useCallback(async (force = false) => {
    if (paused && !force) {
      return;
    }
    if (!partyId || walletType !== "loop") {
      setBalances({});
      setError(null);
      setLoading(false);
      return;
    }

    const connector = walletManager.getOrCreateLoopConnector();
    const status = await connector.checkConnected();
    if (!status.connected) {
      setBalances({});
      setError(null);
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      const rawHoldings = await connector.getHoldings();
      const rows = Array.isArray(rawHoldings) ? rawHoldings : [];
      const map = rows.reduce((acc: LoopBalanceMap, row: any) => {
        const normalized = normalizeLoopHolding(row);
        if (!normalized) {
          return acc;
        }
        const key = normalized.symbol.toUpperCase();
        const existing = acc[key];
        acc[key] = {
          amount: existing ? decimalAddStrings(existing.amount, normalized.quantity) : normalized.quantity,
          decimals: existing?.decimals ?? normalized.decimals,
        };
        return acc;
      }, {});

      setBalances(map);
      setError(null);
    } catch (err) {
      console.warn("Failed to load Loop balances", err);
      setError(err instanceof Error ? err : new Error("Failed to load Loop balances"));
      setBalances({});
    } finally {
      setLoading(false);
    }
  }, [partyId, walletType, paused]);

  useEffect(() => {
    if (paused) {
      return;
    }
    fetchBalances();
  }, [fetchBalances, paused]);

  useEffect(() => {
    if (paused || !partyId || walletType !== "loop" || refreshIntervalMs <= 0) {
      return;
    }
    const interval = window.setInterval(() => {
      fetchBalances();
    }, refreshIntervalMs);
    return () => {
      window.clearInterval(interval);
    };
  }, [partyId, walletType, refreshIntervalMs, fetchBalances]);

  useEffect(() => {
    if (typeof window === "undefined") {
      return;
    }
    if (paused) {
      return;
    }
    const handleWalletConnected = () => {
      fetchBalances();
    };
    window.addEventListener("clearportx:wallet:connected", handleWalletConnected as EventListener);
    return () => {
      window.removeEventListener("clearportx:wallet:connected", handleWalletConnected as EventListener);
    };
  }, [fetchBalances]);

  return {
    balances,
    loading,
    error,
    reload: () => fetchBalances(true),
  };
}

type NormalizedHolding = {
  symbol: string;
  quantity: string;
  decimals: number;
};

function normalizeLoopHolding(raw: Record<string, unknown>): NormalizedHolding | null {
  if (!raw) return null;
  const symbolRaw =
    (raw as any)?.symbol ??
    (raw as any)?.instrument_id?.id ??
    (raw as any)?.instrumentId?.id ??
    (raw as any)?.instrument_id ??
    (raw as any)?.instrumentId;
  let symbol = String(symbolRaw ?? "UNKNOWN").toUpperCase();

  const instrumentId = (raw as any)?.instrument_id ?? (raw as any)?.instrumentId;
  if (instrumentId?.id === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
  }

  const decimalsCandidate = (raw as any)?.decimals ?? DEFAULT_DECIMALS;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || DEFAULT_DECIMALS;

  const totalUnlocked = String((raw as any)?.total_unlocked_coin ?? "");
  const totalLocked = String((raw as any)?.total_locked_coin ?? "");
  if (totalUnlocked || totalLocked) {
    const quantity = decimalAddStrings(totalUnlocked || "0", totalLocked || "0");
    return { symbol, quantity, decimals };
  }

  const quantityCandidate =
    (raw as any)?.amount ??
    (raw as any)?.balance ??
    (raw as any)?.holdingAmount ??
    (raw as any)?.quantity ??
    "0";
  const quantity = typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");
  return { symbol, quantity, decimals };
}

function decimalAddStrings(a: string, b: string): string {
  const sa = a ?? "0";
  const sb = b ?? "0";
  const [ia, fa = ""] = sa.split(".");
  const [ib, fb = ""] = sb.split(".");
  const fracLen = Math.max(fa.length, fb.length);
  const normFa = (fa + "0".repeat(fracLen)).slice(0, fracLen);
  const normFb = (fb + "0".repeat(fracLen)).slice(0, fracLen);
  const intA = BigInt(ia || "0");
  const intB = BigInt(ib || "0");
  const fracA = BigInt(normFa || "0");
  const fracB = BigInt(normFb || "0");
  const fracSum = fracA + fracB;
  const baseStr = "1" + "0".repeat(fracLen || 0);
  const base = BigInt(baseStr);
  const carry = fracSum / base;
  const fracRes = fracSum % base;
  const intRes = intA + intB + carry;
  const fracStr =
    fracLen > 0 ? fracRes.toString().padStart(fracLen, "0").replace(/0+$/, "") : "";
  return fracStr.length > 0 ? `${intRes.toString()}.${fracStr}` : intRes.toString();
}

