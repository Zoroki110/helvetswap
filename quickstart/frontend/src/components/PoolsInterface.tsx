import React, { useCallback, useEffect } from 'react';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { useNavigate } from 'react-router-dom';
import { TokenInfo, TransactionHistoryEntry } from '../types/canton';
import { SwissCross } from './HelvetAtoms';
import { useIsMobile } from '../hooks/useIsMobile';

const PoolsInterface: React.FC = () => {
  const { pools, setPools } = useAppStore();
  const navigate = useNavigate();
  const isMobile = useIsMobile();
  const [loading, setLoading] = React.useState(true);
  const [volumeByPool, setVolumeByPool] = React.useState<Record<string, number>>({});
  const [totalVolume24h, setTotalVolume24h] = React.useState(0);
  const [volumeDay, setVolumeDay] = React.useState<string>(() => new Date().toISOString().slice(0, 10));

  useEffect(() => {
    const loadPools = async () => {
      try {
        setLoading(true);
        const poolsData = await backendApi.getPools();
        setPools(poolsData);
      } catch (error) {
        console.error('Error loading pools:', error);
      } finally {
        setLoading(false);
      }
    };

    loadPools();
  }, [setPools]);

  // Calculer les statistiques globales
  const totalTVL = pools.reduce((sum, pool) => {
    // Approximation: TVL = reserveA + reserveB (en assumant que les tokens ont des valeurs similaires)
    return sum + pool.reserveA + pool.reserveB;
  }, 0);

  const effectiveTotalVolume24h = totalVolume24h;
  const averageAPR = pools.length > 0
    ? pools.reduce((sum, pool) => sum + pool.apr, 0) / pools.length
    : 0;

  const formatNumber = (num: number) => {
    if (num >= 1_000_000) {
      return `$${(num / 1_000_000).toFixed(2)}M`;
    } else if (num >= 1_000) {
      return `$${(num / 1_000).toFixed(2)}K`;
    }
    return `$${num.toFixed(2)}`;
  };

  const formatTokenCompact = (amount: number) => {
    if (!Number.isFinite(amount)) {
      return '0';
    }
    if (amount >= 1_000_000) {
      return `${(amount / 1_000_000).toFixed(2)}M`;
    }
    if (amount >= 1_000) {
      return `${(amount / 1_000).toFixed(2)}K`;
    }
    if (amount >= 1) {
      return amount.toFixed(2);
    }
    return amount.toFixed(6).replace(/0+$/, '').replace(/\.$/, '');
  };

  const computeVolumeSnapshot = useCallback((entries: TransactionHistoryEntry[]) => {
    const dayKey = new Date().toISOString().slice(0, 10);
    const byPool: Record<string, number> = {};
    let total = 0;
    entries.forEach((entry) => {
      if (entry.type !== 'SWAP') return;
      if (!entry.createdAt) return;
      const entryDay = new Date(entry.createdAt).toISOString().slice(0, 10);
      if (entryDay !== dayKey) return;
      const amountIn = Number(entry.amountADesired);
      if (!Number.isFinite(amountIn)) return;
      total += amountIn;
      const poolKey = entry.poolId || entry.contractId;
      if (poolKey) {
        byPool[poolKey] = (byPool[poolKey] ?? 0) + amountIn;
      }
    });
    setVolumeByPool(byPool);
    setTotalVolume24h(total);
    setVolumeDay(dayKey);
  }, []);

  const refreshVolumes = useCallback(async () => {
    try {
      const entries = await backendApi.getTransactionHistory();
      computeVolumeSnapshot(entries);
    } catch {
      // transaction history load failed — non-fatal for volume display
    }
  }, [computeVolumeSnapshot]);

  useEffect(() => {
    refreshVolumes();
  }, [refreshVolumes]);

  useEffect(() => {
    const interval = window.setInterval(() => {
      const dayKey = new Date().toISOString().slice(0, 10);
      if (dayKey !== volumeDay) {
        refreshVolumes();
      }
    }, 60_000);
    return () => window.clearInterval(interval);
  }, [refreshVolumes, volumeDay]);

  if (loading) {
    return (
      <div style={{ maxWidth: "var(--view-max-w)", margin: "0 auto", padding: "2rem 0" }}>
        <div className="shimmer-block" style={{ width: "100%", height: 200 }} />
      </div>
    );
  }

  return (
    <div className="fu" style={{ maxWidth: "var(--view-max-w)", margin: "0 auto" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: "1.5rem" }}>
        <div>
          <div style={{ fontFamily: "var(--display)", fontSize: "1.6rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)", marginBottom: "0.3rem" }}>Liquidity Pools</div>
          <p style={{ fontFamily: "var(--serif)", fontSize: "0.85rem", color: "var(--text-3)" }}>Active pools available for trading and liquidity provision</p>
        </div>
      </div>

      {isMobile ? (
        pools.length === 0 ? (
          <div style={{ padding: "2rem 1.4rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
            No pools available yet.
          </div>
        ) : (
          pools.map((pool) => (
            <div key={pool.contractId} style={{
              background: "var(--ink-2)", border: "1px solid var(--rule)",
              marginBottom: "0.75rem",
            }}>
              <div style={{
                display: "flex", justifyContent: "space-between", alignItems: "center",
                padding: "0.8rem var(--card-px)",
                borderBottom: "1px solid var(--rule)",
              }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <SwissCross size={8} />
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>
                    {pool.tokenA.symbol}<span style={{ color: "var(--text-3)" }}>/{pool.tokenB.symbol}</span>
                  </span>
                </div>
                <button className="btn-ghost" style={{ height: 30, fontSize: "0.68rem", padding: "0 0.8rem" }}
                  onClick={() => navigate('/liquidity', { state: { pool } })}>
                  Manage
                </button>
              </div>
              <div style={{ padding: "0.4rem var(--card-px)" }}>
                {[
                  ["Reserve A", `${formatTokenCompact(pool.reserveA)} ${pool.tokenA.symbol}`],
                  ["Reserve B", `${formatTokenCompact(pool.reserveB)} ${pool.tokenB.symbol}`],
                  ["Fee", `${(pool.feeRate * 100).toFixed(2)}%`],
                ].map(([label, value]) => (
                  <div key={label} style={{
                    display: "flex", justifyContent: "space-between",
                    padding: "0.4rem 0",
                    fontFamily: "var(--mono)", fontSize: "0.72rem",
                  }}>
                    <span style={{ color: "var(--text-3)" }}>{label}</span>
                    <span style={{ color: "var(--text-2)" }}>{value}</span>
                  </div>
                ))}
              </div>
            </div>
          ))
        )
      ) : (
        <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)" }}>
          {/* Table header */}
          <div style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr 1fr 0.6fr 0.8fr", padding: "0.7rem 1.4rem", borderBottom: "1px solid var(--rule)" }}>
            {["Pair", "Reserve A", "Reserve B", "Fee", "Actions"].map(h => (
              <span key={h} style={{ fontFamily: "var(--mono)", fontSize: "0.66rem", letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--text-3)" }}>{h}</span>
            ))}
          </div>

          {pools.length === 0 ? (
            <div style={{ padding: "2rem 1.4rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
              No pools available yet.
            </div>
          ) : (
            pools.map((pool) => (
              <div key={pool.contractId} className="rule-hover" style={{ display: "grid", gridTemplateColumns: "1.2fr 1fr 1fr 0.6fr 0.8fr", padding: "1rem 1.4rem", borderBottom: "1px solid var(--rule)", alignItems: "center" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <SwissCross size={8} />
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>
                    {pool.tokenA.symbol}<span style={{ color: "var(--text-3)" }}>/{pool.tokenB.symbol}</span>
                  </span>
                </div>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-2)" }}>{formatTokenCompact(pool.reserveA)} {pool.tokenA.symbol}</span>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-2)" }}>{formatTokenCompact(pool.reserveB)} {pool.tokenB.symbol}</span>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-3)" }}>{(pool.feeRate * 100).toFixed(2)}%</span>
                <button className="btn-ghost" style={{ height: 30, fontSize: "0.68rem", padding: "0 0.8rem" }} onClick={() => navigate('/liquidity', { state: { pool } })}>
                  Manage
                </button>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  );
};

export default PoolsInterface;