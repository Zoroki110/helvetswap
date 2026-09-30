import { useCallback, useEffect, useState } from "react";
import { apiGet } from "../api/client";

export type LegacyBalance = {
  amount: string;
  decimals: number;
};

export type LegacyBalanceMap = Record<string, LegacyBalance>;

type LegacyTokenRow = {
  symbol?: string;
  amount?: string | number;
  balance?: string | number;
  quantity?: string | number;
  decimals?: number;
};

type UseLegacyBalancesResult = {
  balances: LegacyBalanceMap;
  loading: boolean;
  error: Error | null;
  reload: () => Promise<void>;
};

export function useLegacyBalances(partyId: string | null | undefined): UseLegacyBalancesResult {
  const [balances, setBalances] = useState<LegacyBalanceMap>({});
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
      const rows = await apiGet<LegacyTokenRow[]>(`/api/wallet/tokens/${encodeURIComponent(partyId)}`);
      const list = Array.isArray(rows) ? rows : [];
      const map = list.reduce<LegacyBalanceMap>((acc, entry) => {
        const symbol = entry?.symbol?.toUpperCase?.();
        if (!symbol) {
          return acc;
        }
        const decimals = typeof entry.decimals === "number" ? entry.decimals : 10;
        const current = acc[symbol];
        const nextAmount =
          toNumber(entry.amount ?? entry.balance ?? entry.quantity) +
          (current ? toNumber(current.amount) : 0);
        acc[symbol] = {
          amount: Number.isFinite(nextAmount) ? nextAmount.toString() : "0",
          decimals,
        };
        return acc;
      }, {});
      setBalances(map);
      setError(null);
    } catch (err) {
      console.warn(`Failed to load wallet tokens for ${partyId}`, err);
      setError(err instanceof Error ? err : new Error("Failed to load balances"));
      setBalances({});
    } finally {
      setLoading(false);
    }
  }, [partyId]);

  useEffect(() => {
    if (!partyId) {
      setBalances({});
      setError(null);
      setLoading(false);
      return;
    }
    fetchBalances();
  }, [partyId, fetchBalances]);

  return {
    balances,
    loading,
    error,
    reload: fetchBalances,
  };
}

function toNumber(value: unknown): number {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : 0;
  }
  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : 0;
  }
  return 0;
}
