import { useCallback, useEffect, useState } from "react";
import { walletManager } from "../wallet";

export type HoldingUtxo = {
  contractId: string;
  instrumentId: string;
  amount: string;
  decimals: number;
  owner?: string;
  observers?: string[];
  raw?: unknown;
};

type UseHoldingUtxosParams = {
  walletType?: string | null;
};

export function useHoldingUtxos(params: UseHoldingUtxosParams) {
  const { walletType } = params;
  const [utxos, setUtxos] = useState<HoldingUtxo[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [debugged, setDebugged] = useState(false);

  const load = useCallback(async () => {
    if (walletType !== "loop") {
      setUtxos([]);
      setError(null);
      setLoading(false);
      return;
    }
    const connector = walletManager.getLoopConnector();
    if (!connector || typeof (connector as any).getActiveContracts !== "function") {
      setUtxos([]);
      setError(new Error("Loop connector not ready for UTXO fetch"));
      setLoading(false);
      return;
    }

    setLoading(true);
    setError(null);
    try {
      const contracts = await (connector as any).getActiveContracts({
        interfaceId: "#splice-api-token-holding-v1:Splice.Api.Token.HoldingV1:Holding",
      });
      const mapped = Array.isArray(contracts)
        ? (contracts.map(normalizeUtxo).filter(Boolean) as HoldingUtxo[])
        : [];
      setUtxos(mapped);
      if (!debugged) {
        console.debug("Loop UTXOs (Holding):", mapped);
        setDebugged(true);
      }
    } catch (err: any) {
      setUtxos([]);
      setError(err instanceof Error ? err : new Error("Failed to load UTXOs"));
    } finally {
      setLoading(false);
    }
  }, [walletType, debugged]);

  useEffect(() => {
    load();
  }, [load]);

  return { utxos, loading, error, refresh: load };
}

function normalizeUtxo(raw: any): HoldingUtxo | null {
  const cid = raw?.contractId ?? raw?.contract_id ?? raw?.cid ?? raw?.id ?? "";
  if (!cid) return null;

  const payload = raw?.payload ?? raw?.fields ?? raw?.arguments ?? raw?.data ?? raw;

  const instrument = payload?.instrumentId ?? payload?.instrument_id ?? payload?.instrument ?? {};
  const instrumentId =
    instrument?.id ??
    instrument?.instrumentId?.id ??
    instrument?.assetCode ??
    instrument?.symbol ??
    "UNKNOWN";

  const amountCandidate = payload?.amount ?? payload?.quantity ?? payload?.balance ?? payload?.holdingAmount ?? "0";
  const amount = typeof amountCandidate === "string" ? amountCandidate : String(amountCandidate ?? "0");

  const decimalsCandidate = payload?.decimals ?? payload?.precision ?? 10;
  const decimals =
    typeof decimalsCandidate === "number" && Number.isFinite(decimalsCandidate)
      ? decimalsCandidate
      : parseInt(String(decimalsCandidate), 10) || 10;

  const owner = payload?.owner ?? payload?.party ?? payload?.holder;
  const observers = payload?.observers ?? payload?.witnesses ?? undefined;

  return {
    contractId: cid,
    instrumentId: instrumentId,
    amount,
    decimals,
    owner,
    observers: Array.isArray(observers) ? observers : undefined,
    raw,
  };
}
