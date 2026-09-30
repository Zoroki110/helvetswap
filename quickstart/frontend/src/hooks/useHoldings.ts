import { useEffect, useState, useCallback } from "react";
import { apiGet } from "../api/client";
import { walletManager } from "../wallet";

export type HoldingSummary = {
  symbol: string;
  quantity: string;
  decimals: number;
  displayName?: string;
};

type HoldingsResponse = Array<Record<string, unknown>>;

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
  const fracStr = fracLen > 0 ? fracRes.toString().padStart(fracLen, "0").replace(/0+$/, "") : "";
  return fracStr.length > 0 ? `${intRes.toString()}.${fracStr}` : intRes.toString();
}

export function useHoldings(params: { partyId?: string | null; walletType?: string | null }) {
  const { partyId, walletType } = params;
  const [holdings, setHoldings] = useState<HoldingSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [loopRetry, setLoopRetry] = useState(0);
  const [zoroRetry, setZoroRetry] = useState(0);

  const load = useCallback(async () => {
    if (!partyId) {
      setHoldings([]);
      setError(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      // Loop path: use connector wrapper methods to preserve provider binding and map balances.
      if (walletType === "loop") {
        const connector = walletManager.getLoopConnector();
        if (!connector) {
          if (loopRetry < 3) {
            setLoopRetry((r) => r + 1);
            setTimeout(() => load(), 500);
          }
          setLoading(false);
          return;
        }
        try {
          const loopHoldings = await (connector as any).getHoldings();
          const normalized = Array.isArray(loopHoldings)
            ? (loopHoldings.map(normalizeLoopHolding).filter(Boolean) as HoldingSummary[])
            : [];
          setHoldings(normalized);
          setLoading(false);
          return;
        } catch (err) {
          console.warn("[Loop] getHoldings failed, fallback to backend", err);
        }
      }

      if (walletType === "zoro") {
        const connector = (walletManager as any).getZoroConnector?.();
        if (!connector) {
          if (zoroRetry < 3) {
            setZoroRetry((r) => r + 1);
            setTimeout(() => load(), 500);
          }
          setLoading(false);
          return;
        }
        try {
          const zoroHoldings = await (connector as any).getHoldings();
          const normalized = Array.isArray(zoroHoldings)
            ? (zoroHoldings.map(normalizeZoroHolding).filter(Boolean) as HoldingSummary[])
            : [];
          const aggregated = aggregateHoldings(normalized);
          setHoldings(aggregated);
          setLoading(false);
          return;
        } catch (err) {
          console.warn("[Zoro] getHoldings failed, fallback to backend", err);
        }
      }

      // Default path: backend API.
      const data = await apiGet<HoldingsResponse>(`/api/holdings/${encodeURIComponent(partyId)}`);
      const normalized = Array.isArray(data)
        ? (data.map(normalizeHolding).filter(Boolean) as HoldingSummary[])
        : [];
      setHoldings(normalized);
    } catch (err: any) {
      console.warn("Failed to load holdings for", partyId, err);
      setHoldings([]);
      setError(err instanceof Error ? err : new Error("Failed to load holdings"));
    } finally {
      setLoading(false);
    }
  }, [partyId, walletType, loopRetry, zoroRetry]);

  useEffect(() => {
    load();
  }, [load]);

  return { holdings, loading, error };
}

function normalizeHolding(raw: Record<string, unknown>): HoldingSummary {
  const instrument = (raw as any)?.instrument;
  const metadata = (raw as any)?.metadata ?? instrument?.metadata;
  const rawInstrumentId = instrument?.instrumentId?.id ?? (raw as any)?.instrumentId ?? null;

  const symbolCandidate =
    instrument?.instrumentId?.id ??
    instrument?.symbol ??
    instrument?.assetCode ??
    metadata?.symbol ??
    metadata?.code ??
    (raw as any)?.symbol ??
    (raw as any)?.token ??
    (raw as any)?.assetCode ??
    (raw as any)?.currencyCode ??
    rawInstrumentId ??
    "UNKNOWN";

  let symbol = String(symbolCandidate ?? "UNKNOWN").toUpperCase();
  let displayName =
    (raw as any)?.name ??
    (raw as any)?.metadata?.name ??
    metadata?.name ??
    instrument?.name ??
    (symbol === "CC" ? "Canton Coin" : undefined);

  if (rawInstrumentId === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
    displayName = "Canton Coin";
  }

  const quantityCandidate =
    (raw as any)?.amount ??
    (raw as any)?.balance ??
    (raw as any)?.holdingAmount ??
    (raw as any)?.quantity ??
    "0";
  const quantity =
    typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");

  const decimalsCandidate = (raw as any)?.decimals ?? (raw as any)?.precision ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  return { symbol, quantity, decimals, displayName };
}

function normalizeLoopHolding(raw: Record<string, unknown>): HoldingSummary | null {
  if (!raw) return null;
  const symbolRaw = (raw as any)?.symbol ?? (raw as any)?.instrument_id?.id ?? (raw as any)?.instrumentId?.id;
  let symbol = String(symbolRaw ?? "UNKNOWN").toUpperCase();
  const instrumentId = (raw as any)?.instrument_id ?? (raw as any)?.instrumentId;
  const decimalsCandidate = (raw as any)?.decimals ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  let displayName: string | undefined = (raw as any)?.name;

  if (instrumentId?.id === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
    displayName = "Canton Coin";
  }
  if (symbol === "CBTC") {
    displayName = displayName ?? "CBTC";
  }

  const totalUnlocked = String((raw as any)?.total_unlocked_coin ?? "0");
  const totalLocked = String((raw as any)?.total_locked_coin ?? "0");
  const quantity = decimalAddStrings(totalUnlocked, totalLocked);

  return { symbol, quantity, decimals, displayName };
}

function normalizeZoroHolding(raw: Record<string, unknown>): HoldingSummary | null {
  if (!raw) return null;
  const instrumentId =
    (raw as any)?.instrument?.id ??
    (raw as any)?.instrumentId?.id ??
    (raw as any)?.instrument_id?.id ??
    (raw as any)?.instrumentId ??
    (raw as any)?.assetCode ??
    (raw as any)?.symbol;

  let symbol = String(instrumentId ?? "UNKNOWN").toUpperCase();
  let displayName: string | undefined = (raw as any)?.name ?? (raw as any)?.displayName;

  if (instrumentId === "Amulet" || symbol === "AMULET") {
    symbol = "CC";
    displayName = "Canton Coin";
  }

  const quantityCandidate =
    (raw as any)?.amount ?? (raw as any)?.quantity ?? (raw as any)?.balance ?? (raw as any)?.value ?? "0";
  const quantity =
    typeof quantityCandidate === "string" ? quantityCandidate : String(quantityCandidate ?? "0");

  const decimalsCandidate = (raw as any)?.decimals ?? (raw as any)?.precision ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  return { symbol, quantity, decimals, displayName };
}

function aggregateHoldings(items: HoldingSummary[]): HoldingSummary[] {
  const map = new Map<string, HoldingSummary>();
  for (const item of items) {
    const existing = map.get(item.symbol);
    if (!existing) {
      map.set(item.symbol, { ...item });
      continue;
    }
    const quantity = decimalAddStrings(existing.quantity, item.quantity);
    map.set(item.symbol, {
      symbol: item.symbol,
      quantity,
      decimals: existing.decimals ?? item.decimals,
      displayName: existing.displayName || item.displayName,
    });
  }
  return Array.from(map.values());
}
