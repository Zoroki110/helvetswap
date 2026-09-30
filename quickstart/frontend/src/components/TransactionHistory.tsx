import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { SwissCross } from './HelvetAtoms';
import toast from 'react-hot-toast';
import { backendApi } from '../services/backendApi';
import { useWalletAuth } from '../wallet';
import type { PoolInfo, TransactionHistoryEntry } from '../types/canton';
import { useIsMobile } from '../hooks/useIsMobile';

const numberFormatter = new Intl.NumberFormat('en', {
  notation: 'compact',
  compactDisplay: 'short',
  maximumFractionDigits: 2,
});

const addThousandsSeparator = (digits: string) =>
  digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');

const formatFullAmount = (value?: string) => {
  if (!value) return '—';
  const trimmed = value.trim();
  if (!/^-?\d+(\.\d+)?$/.test(trimmed)) {
    return trimmed;
  }
  const negative = trimmed.startsWith('-');
  const unsigned = negative ? trimmed.slice(1) : trimmed;
  const [rawInt = '0', rawFrac] = unsigned.split('.');
  const intDigits = rawInt.replace(/^0+(?=\d)/, '') || '0';
  const formattedInt = addThousandsSeparator(intDigits);
  if (!rawFrac) {
    return negative ? `-${formattedInt}` : formattedInt;
  }
  const trimmedFrac = rawFrac.replace(/0+$/, '');
  const body = trimmedFrac.length ? `${formattedInt}.${trimmedFrac}` : formattedInt;
  return negative ? `-${body}` : body;
};

const dateTimeFormatter = new Intl.DateTimeFormat('en-US', {
  month: 'short',
  day: 'numeric',
  year: 'numeric',
  hour: 'numeric',
  minute: '2-digit',
});

// Keep compact formatter available if needed later; suppress unused lint for now.
// eslint-disable-next-line @typescript-eslint/no-unused-vars
const formatCompact = (value?: string) => {
  if (!value) return '—';
  const num = Number(value);
  if (!Number.isFinite(num)) return value;
  return numberFormatter.format(num);
};

const formatDateTime = (iso?: string) => {
  if (!iso) return '—';
  try {
    return dateTimeFormatter.format(new Date(iso));
  } catch {
    return iso;
  }
};

const buildPairKey = (a: string, b: string) => [a?.toUpperCase?.() ?? '', b?.toUpperCase?.() ?? ''].sort().join('/');

const trimNumberString = (value: number, precision = 10) => {
  if (!Number.isFinite(value)) {
    return null;
  }
  const fixed = value.toFixed(precision);
  const trimmed = fixed.replace(/0+$/, '').replace(/\.$/, '');
  return trimmed.length > 0 ? trimmed : '0';
};

const formatRelativeTime = (iso?: string) => {
  if (!iso) return '—';
  const created = new Date(iso).getTime();
  if (Number.isNaN(created)) return iso;
  const diffMs = Date.now() - created;
  if (diffMs < 60_000) return 'Just now';
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
};

const statusLabels: Record<TransactionHistoryEntry['status'], string> = {
  settled: 'Settled',
  pending: 'Pending',
  failed: 'Failed',
};

const TransactionHistory: React.FC = () => {
  const isMobile = useIsMobile();
  const { partyId } = useWalletAuth();
  const [transactions, setTransactions] = useState<TransactionHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dismissedIds, setDismissedIds] = useState<Set<string>>(new Set());
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [pools, setPools] = useState<PoolInfo[]>([]);

  const fetchHistory = useCallback(async () => {
    if (!partyId) {
      setTransactions([]);
      setError(null);
      setLoading(false);
      setRefreshing(false);
      return;
    }
    try {
      setError(null);
      const entries = await backendApi.getTransactionHistory();
      setTransactions(entries);
    } catch (err) {
      console.error('Failed to load transaction history:', err);
      setError('Unable to load history. Please try again shortly.');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [partyId]);

  useEffect(() => {
    fetchHistory();
  }, [fetchHistory]);

  useEffect(() => {
    let mounted = true;
    if (partyId) {
      backendApi
        .getPools()
        .then((data) => {
          if (mounted) {
            setPools(data);
          }
        })
        .catch(() => {
          // pools load failed — non-fatal for history view
        });
    } else if (mounted) {
      setPools([]);
    }
    return () => {
      mounted = false;
    };
  }, [partyId]);

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    if (!partyId) {
      return;
    }
    const handler = () => fetchHistory();
    window.addEventListener('clearportx:transactions:refresh', handler);
    return () => window.removeEventListener('clearportx:transactions:refresh', handler);
  }, [fetchHistory, partyId]);

  const visibleTransactions = useMemo(
    () => transactions.filter((tx) => !dismissedIds.has(tx.id)),
    [transactions, dismissedIds]
  );

  useEffect(() => {
    if (!selectedId && visibleTransactions.length > 0) {
      setSelectedId(visibleTransactions[0].id);
    } else if (selectedId && !visibleTransactions.find(tx => tx.id === selectedId)) {
      setSelectedId(visibleTransactions[0]?.id || null);
    }
  }, [visibleTransactions, selectedId]);

  const selectedTransaction = useMemo(
    () => visibleTransactions.find((tx) => tx.id === selectedId) || null,
    [visibleTransactions, selectedId]
  );

  const handleRefresh = async () => {
    if (!partyId) {
      return;
    }
    setRefreshing(true);
    await fetchHistory();
  };

  const handleDismiss = (id: string) => {
    setDismissedIds((prev) => {
      const next = new Set(prev);
      next.add(id);
      return next;
    });
  };

  const handleCopyContractId = async (contractId: string) => {
    try {
      await navigator.clipboard.writeText(contractId);
      toast.success('Contract ID copied to clipboard');
    } catch {
      toast.error('Unable to copy Contract ID');
    }
  };

  const formatTypeLabel = (type: TransactionHistoryEntry['type']) => {
    switch (type) {
      case 'ADD_LIQUIDITY':
        return 'Add Liquidity';
      case 'SWAP':
        return 'Swap';
      case 'POOL_CREATION':
        return 'Pool Creation';
      default:
        return type.replace('_', ' ');
    }
  };

  const poolLookup = useMemo(() => {
    const map = new Map<string, PoolInfo>();
    pools.forEach((pool) => {
      if (pool.poolId) {
        map.set(pool.poolId, pool);
      }
      map.set(buildPairKey(pool.tokenA.symbol, pool.tokenB.symbol), pool);
    });
    return map;
  }, [pools]);

  const estimateSwapOutput = useCallback((tx: TransactionHistoryEntry): number | null => {
    if (tx.type !== 'SWAP') {
      return null;
    }
    const amountIn = Number(tx.amountADesired);
    if (!Number.isFinite(amountIn) || amountIn <= 0) {
      return null;
    }
    const pool =
      (tx.poolId && poolLookup.get(tx.poolId)) ||
      poolLookup.get(buildPairKey(tx.tokenA, tx.tokenB));
    if (!pool) {
      return null;
    }
    const isAtoB = tx.tokenA === pool.tokenA.symbol && tx.tokenB === pool.tokenB.symbol;
    const isBtoA = tx.tokenA === pool.tokenB.symbol && tx.tokenB === pool.tokenA.symbol;
    if (!isAtoB && !isBtoA) {
      return null;
    }
    const reserveIn = isAtoB ? pool.reserveA : pool.reserveB;
    const reserveOut = isAtoB ? pool.reserveB : pool.reserveA;
    if (!Number.isFinite(reserveIn) || !Number.isFinite(reserveOut) || reserveIn <= 0 || reserveOut <= 0) {
      return null;
    }
    const feeRate = pool.feeRate ?? 0.003;
    const inputAfterFee = amountIn * (1 - feeRate);
    if (inputAfterFee <= 0) {
      return null;
    }
    const outputAmount = (inputAfterFee * reserveOut) / (reserveIn + inputAfterFee);
    return Number.isFinite(outputAmount) && outputAmount > 0 ? outputAmount : null;
  }, [poolLookup]);

  const formatSwapOutput = useCallback((tx: TransactionHistoryEntry) => {
    const estimated = estimateSwapOutput(tx);
    if (estimated != null) {
      const trimmed = trimNumberString(estimated);
      if (trimmed) {
        return formatFullAmount(trimmed);
      }
    }
    return formatFullAmount(tx.amountBDesired);
  }, [estimateSwapOutput]);

  const summarizeAmount = (tx: TransactionHistoryEntry) => {
    if (tx.type === 'SWAP') {
      return `${formatFullAmount(tx.amountADesired)} ${tx.tokenA} → ${formatSwapOutput(tx)} ${tx.tokenB}`;
    }
    return `${formatFullAmount(tx.amountADesired)} ${tx.tokenA} / ${formatFullAmount(tx.amountBDesired)} ${tx.tokenB}`;
  };

  const renderDetails = (tx: TransactionHistoryEntry) => {
    // Common trailing fields (rendered for every tx type).
    const trailing = [
      { label: 'Expires At', value: formatDateTime(tx.expiresAt) },
      { label: 'Status', value: statusLabels[tx.status] },
    ];

    if (tx.type === 'SWAP') {
      // Swap-specific fields: input, output, rate, price impact.
      const inputStr = `${formatFullAmount(tx.amountADesired)} ${tx.tokenA}`;
      const outputStr = `${formatSwapOutput(tx)} ${tx.tokenB}`;

      const inNum = Number(tx.amountADesired);
      const estimated = estimateSwapOutput(tx);
      const outNum = estimated != null ? estimated : Number(tx.amountBDesired);
      const rateStr =
        Number.isFinite(inNum) && inNum > 0 && Number.isFinite(outNum) && outNum > 0
          ? `1 ${tx.tokenB} ≈ ${(inNum / outNum).toLocaleString(undefined, { maximumFractionDigits: 6 })} ${tx.tokenA}`
          : '—';

      // Price impact / fee are computed against the pool reserves when available.
      const pool = tx.poolId ? poolLookup.get(tx.poolId) : undefined;
      let priceImpactStr = '—';
      let feeStr = '—';
      if (pool && Number.isFinite(inNum) && inNum > 0) {
        const reserveA = Number(pool.reserveA);
        const reserveB = Number(pool.reserveB);
        if (Number.isFinite(reserveA) && reserveA > 0 && Number.isFinite(reserveB) && reserveB > 0) {
          const spot = reserveB / reserveA;
          const effective = Number.isFinite(outNum) && outNum > 0 ? outNum / inNum : spot;
          const impact = spot > 0 ? Math.abs((spot - effective) / spot) * 100 : 0;
          priceImpactStr = `${impact.toFixed(4)}%`;
        }
        const feeRate = Number(pool.feeRate);
        if (Number.isFinite(feeRate) && feeRate > 0) {
          const fee = inNum * feeRate;
          feeStr = `${formatFullAmount(fee.toString())} ${tx.tokenA}`;
        }
      }

      return [
        { label: 'Input', value: inputStr },
        { label: 'Output', value: outputStr },
        { label: 'Rate', value: rateStr },
        { label: 'Fee', value: feeStr },
        { label: 'Price Impact', value: priceImpactStr },
        ...trailing,
      ];
    }

    if (tx.type === 'ADD_LIQUIDITY') {
      return [
        { label: 'Token A', value: tx.tokenA || '—' },
        { label: 'Amount A Desired', value: `${formatFullAmount(tx.amountADesired)} ${tx.tokenA}` },
        { label: 'Token B', value: tx.tokenB || '—' },
        { label: 'Amount B Desired', value: `${formatFullAmount(tx.amountBDesired)} ${tx.tokenB}` },
        { label: 'Min LP Amount', value: tx.minLpAmount ? `${formatFullAmount(tx.minLpAmount)} ${tx.lpTokenSymbol || 'LP'}` : '—' },
        { label: 'LP Tokens Minted', value: tx.lpMintedAmount ? `${formatFullAmount(tx.lpMintedAmount)} ${tx.lpTokenSymbol || 'LP'}` : '—' },
        ...trailing,
      ];
    }

    // POOL_CREATION / TOKEN_MINT / UNKNOWN — show generic pair info without LP labels.
    return [
      { label: 'Token A', value: tx.tokenA || '—' },
      { label: 'Amount A', value: `${formatFullAmount(tx.amountADesired)} ${tx.tokenA}` },
      { label: 'Token B', value: tx.tokenB || '—' },
      { label: 'Amount B', value: `${formatFullAmount(tx.amountBDesired)} ${tx.tokenB}` },
      ...trailing,
    ];
  };

  if (!partyId) {
    return (
      <div className="fu" style={{ maxWidth: "var(--view-max-w)", margin: "0 auto" }}>
        <div style={{ fontFamily: "var(--display)", fontSize: "1.6rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)", marginBottom: "1.5rem" }}>Transaction History</div>
        <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)", padding: "3rem 1.4rem", textAlign: "center" }}>
          <p style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>Connect your wallet to view your transaction history.</p>
        </div>
      </div>
    );
  }

  if (loading) {
    return (
      <div style={{ maxWidth: "var(--view-max-w)", margin: "0 auto", padding: "2rem 0" }}>
        <div style={{ fontFamily: "var(--display)", fontSize: "1.6rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)", marginBottom: "1.5rem" }}>Transaction History</div>
        <div className="shimmer-block" style={{ width: "100%", height: 200 }} />
      </div>
    );
  }

  return (
    <div className="fu" style={{ maxWidth: "var(--view-max-w)", margin: "0 auto" }}>
      {/* Header */}
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1.5rem" }}>
        <div style={{ fontFamily: "var(--display)", fontSize: "1.6rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)" }}>Transaction History</div>
        <button className="btn-ghost" style={{ gap: "0.4rem" }} onClick={handleRefresh} disabled={refreshing}>
          <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M6 1V3M6 1C3.24 1 1 3.24 1 6s2.24 5 5 5 5-2.24 5-5" stroke="var(--text-3)" strokeWidth="1.2" /><path d="M4 1h2v2" stroke="var(--text-3)" strokeWidth="1" /></svg>
          {refreshing ? 'Refreshing...' : 'Refresh'}
        </button>
      </div>

      {error && (
        <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--red-muted)", padding: "0.6rem 1rem", background: "rgba(140,74,74,0.08)", border: "1px solid rgba(140,74,74,0.15)", marginBottom: "1rem" }}>
          {error}
        </div>
      )}

      {isMobile ? (
        visibleTransactions.length === 0 ? (
          <div style={{ padding: "3rem 1.4rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
            No transactions available yet.
          </div>
        ) : (
          visibleTransactions.map((tx) => (
            <div key={tx.id} style={{
              background: "var(--ink-2)", border: "1px solid var(--rule)",
              marginBottom: "0.75rem",
            }}>
              {/* Card header: tap to expand */}
              <div
                className="rule-hover"
                onClick={() => setSelectedId(selectedId === tx.id ? null : tx.id)}
                style={{ padding: "var(--card-px)", cursor: "pointer" }}
              >
                <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "0.4rem" }}>
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-1)" }}>
                    {formatTypeLabel(tx.type)}
                  </span>
                  <span style={{
                    fontFamily: "var(--mono)", fontSize: "0.68rem", letterSpacing: "0.08em",
                    color: tx.status === 'settled' ? "var(--green)" : tx.status === 'failed' ? "var(--red-muted)" : "var(--gold)",
                    background: tx.status === 'settled' ? "rgba(74,140,111,0.12)" : tx.status === 'failed' ? "rgba(140,74,74,0.12)" : "var(--gold-pale)",
                    padding: "0.15rem 0.5rem",
                  }}>
                    {statusLabels[tx.status]}
                  </span>
                </div>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-2)", marginBottom: "0.3rem" }}>
                  {summarizeAmount(tx)}
                </div>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)" }}>
                  {formatRelativeTime(tx.createdAt)}
                </div>
              </div>

              {/* Expanded detail: 2-column grid */}
              {selectedId === tx.id && (
                <div style={{
                  padding: "1.2rem var(--card-px) 1.4rem",
                  borderTop: "1px solid var(--rule)",
                  background: "var(--ink-3)",
                }}>
                  <div className="label-caps" style={{ color: "var(--gold)", marginBottom: "0.8rem" }}>
                    {tx.title || formatTypeLabel(tx.type)}
                  </div>
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginBottom: "0.4rem" }}>
                    <SwissCross size={7} />
                    <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>
                      {(tx.tokenA === 'Amulet' ? 'CC' : tx.tokenA)}<span style={{ color: "var(--text-3)" }}>/{tx.tokenB}</span>
                    </span>
                  </div>
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)", marginBottom: "1rem" }}>
                    Created: {formatDateTime(tx.createdAt)}
                  </div>
                  <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "1px", background: "var(--rule)" }}>
                    {renderDetails(tx).map((detail) => (
                      <div key={detail.label} style={{ padding: "0.7rem 0.8rem", background: "var(--ink-2)" }}>
                        <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--text-3)", marginBottom: "0.3rem" }}>{detail.label}</div>
                        <div style={{ fontFamily: "var(--mono)", fontSize: "0.75rem", color: detail.value === "Settled" ? "var(--green)" : "var(--text-1)" }}>{detail.value}</div>
                      </div>
                    ))}
                  </div>
                  {tx.contractId && (
                    <div style={{ marginTop: "0.8rem" }}>
                      <div className="label-caps" style={{ marginBottom: "0.3rem" }}>Contract ID</div>
                      <div
                        style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-2)", cursor: "pointer", wordBreak: "break-all" }}
                        onClick={() => handleCopyContractId(tx.contractId)}
                      >
                        {tx.contractId}
                      </div>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))
        )
      ) : (
        <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)" }}>
          {/* Table header */}
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 2fr 0.8fr", padding: "0.7rem 1.4rem", borderBottom: "1px solid var(--rule)" }}>
            {["Time", "Type", "Amount", "Status"].map(h => (
              <span key={h} style={{ fontFamily: "var(--mono)", fontSize: "0.66rem", letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--text-3)" }}>{h}</span>
            ))}
          </div>

          {visibleTransactions.length === 0 ? (
            <div style={{ padding: "3rem 1.4rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
              No transactions available yet.
            </div>
          ) : (
            visibleTransactions.map((tx) => (
              <div key={tx.id}>
                <div
                  className="rule-hover"
                  onClick={() => setSelectedId(selectedId === tx.id ? null : tx.id)}
                  style={{ display: "grid", gridTemplateColumns: "1fr 1fr 2fr 0.8fr", padding: "0.9rem 1.4rem", borderBottom: "1px solid var(--rule)", cursor: "pointer", alignItems: "center" }}
                >
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-3)" }}>{formatRelativeTime(tx.createdAt)}</span>
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-1)" }}>{formatTypeLabel(tx.type)}</span>
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-2)" }}>{summarizeAmount(tx)}</span>
                  <span style={{
                    fontFamily: "var(--mono)", fontSize: "0.68rem", letterSpacing: "0.08em",
                    color: tx.status === 'settled' ? "var(--green)" : tx.status === 'failed' ? "var(--red-muted)" : "var(--gold)",
                    background: tx.status === 'settled' ? "rgba(74,140,111,0.12)" : tx.status === 'failed' ? "rgba(140,74,74,0.12)" : "var(--gold-pale)",
                    padding: "0.15rem 0.5rem", display: "inline-block", width: "fit-content",
                  }}>{statusLabels[tx.status]}</span>
                </div>

                {/* Expanded detail panel */}
                {selectedId === tx.id && (
                  <div style={{ padding: "1.2rem 1.4rem 1.4rem", background: "var(--ink-3)", borderBottom: "1px solid var(--rule)" }}>
                    <div className="label-caps" style={{ color: "var(--gold)", marginBottom: "0.8rem" }}>{tx.title || formatTypeLabel(tx.type)}</div>
                    <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", marginBottom: "0.4rem" }}>
                      <SwissCross size={7} />
                      <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>
                        {(tx.tokenA === 'Amulet' ? 'CC' : tx.tokenA)}<span style={{ color: "var(--text-3)" }}>/{tx.tokenB}</span>
                      </span>
                    </div>
                    <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)", marginBottom: "1rem" }}>
                      Created: {formatDateTime(tx.createdAt)}
                    </div>
                    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", gap: "1px", background: "var(--rule)" }}>
                      {renderDetails(tx).map((detail) => (
                        <div key={detail.label} style={{ padding: "0.7rem 0.8rem", background: "var(--ink-2)" }}>
                          <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--text-3)", marginBottom: "0.3rem" }}>{detail.label}</div>
                          <div style={{ fontFamily: "var(--mono)", fontSize: "0.75rem", color: detail.value === "Settled" ? "var(--green)" : "var(--text-1)" }}>{detail.value}</div>
                        </div>
                      ))}
                    </div>
                    {tx.contractId && (
                      <div style={{ marginTop: "0.8rem" }}>
                        <div className="label-caps" style={{ marginBottom: "0.3rem" }}>Contract ID</div>
                        <div
                          style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-2)", cursor: "pointer", wordBreak: "break-all" }}
                          onClick={() => handleCopyContractId(tx.contractId)}
                        >
                          {tx.contractId}
                        </div>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};

export default TransactionHistory;