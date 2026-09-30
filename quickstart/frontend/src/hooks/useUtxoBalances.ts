import { useCallback, useEffect, useState } from "react";
import { backendApi } from "../services/backendApi";

export type UtxoBalance = {
  instrumentAdmin: string;
  instrumentId: string;
  amount: string;
  decimals: number;
};

export type UtxoBalanceMap = Record<string, UtxoBalance>;

type UseUtxoBalancesOptions = {
  ownerOnly?: boolean;
  refreshIntervalMs?: number;
};

type UseUtxoBalancesResult = {
  balances: UtxoBalanceMap;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
};

const DEFAULT_DECIMALS = 10;

export function useUtxoBalances(
  partyId: string | null | undefined,
  options: UseUtxoBalancesOptions = {}
): UseUtxoBalancesResult {
  const { ownerOnly = true, refreshIntervalMs = 15000 } = options;
  const [balances, setBalances] = useState<UtxoBalanceMap>({});
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const fetchBalances = useCallback(async () => {
    if (!partyId) {
      setBalances({});
      setError(null);
      setLoading(false);
      return;
    }

    setLoading(true);
    try {
      const res = await backendApi.getHoldingUtxos(partyId, ownerOnly);
      const rows: any[] = Array.isArray(res)
        ? res
        : Array.isArray((res as any)?.result)
          ? (res as any).result
          : [];

      const map = rows.reduce((acc: UtxoBalanceMap, row: any) => {
        const instrumentAdmin =
          row?.instrumentAdmin ??
          row?.instrument_admin ??
          row?.instrument?.admin ??
          row?.instrument?.instrumentAdmin ??
          row?.instrument_id?.admin ??
          row?.instrumentId?.admin ??
          "";
        const instrumentId =
          row?.instrumentId ??
          row?.instrument_id ??
          row?.instrument?.id ??
          row?.instrument?.instrumentId?.id ??
          row?.instrument?.instrumentId ??
          row?.instrument?.assetCode ??
          row?.instrument?.symbol ??
          "";
        if (!instrumentAdmin || !instrumentId) {
          return acc;
        }

        const decimalsCandidate =
          row?.decimals ??
          row?.precision ??
          row?.instrument?.decimals ??
          row?.instrument?.precision ??
          DEFAULT_DECIMALS;
        const decimals =
          typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
            ? decimalsCandidate
            : parseInt(String(decimalsCandidate), 10) || DEFAULT_DECIMALS;

        const amountCandidate =
          row?.amount ??
          row?.quantity ??
          row?.balance ??
          row?.holdingAmount ??
          row?.value ??
          "0";
        const amount = typeof amountCandidate === "string" ? amountCandidate : String(amountCandidate ?? "0");

        const key = `${instrumentAdmin}::${instrumentId}`;
        const existing = acc[key];
        acc[key] = {
          instrumentAdmin,
          instrumentId,
          decimals: existing?.decimals ?? decimals,
          amount: existing ? decimalAddStrings(existing.amount, amount) : amount,
        };
        return acc;
      }, {});

      setBalances(map);
      setError(null);
    } catch (err) {
      console.warn("Failed to load UTXO balances for", partyId, err);
      setError(err instanceof Error ? err : new Error("Failed to load balances"));
      setBalances({});
    } finally {
      setLoading(false);
    }
  }, [partyId, ownerOnly]);

  useEffect(() => {
    fetchBalances();
  }, [fetchBalances]);

  useEffect(() => {
    if (!partyId || refreshIntervalMs <= 0) {
      return;
    }
    const interval = window.setInterval(() => {
      fetchBalances();
    }, refreshIntervalMs);
    return () => {
      window.clearInterval(interval);
    };
  }, [partyId, refreshIntervalMs, fetchBalances]);

  useEffect(() => {
    if (typeof window === "undefined") {
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
    reload: fetchBalances,
  };
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

