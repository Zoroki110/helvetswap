import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores';
import { backendApi } from '../services/backendApi';
import { TokenInfo, PoolInfo, LpTokenInfo, LpPositionInfo } from '../types/canton';
import toast from 'react-hot-toast';
import { useWalletAuth, walletManager } from '../wallet';
import { useLoopBalances, useUtxoBalances } from '../hooks';
import TokenSelector from './TokenSelector';
import { SwissCross } from './HelvetAtoms';
import { submitTx } from '../loop/submitTx';
import { getLoopProvider } from '../loop/loopProvider';

const OPERATOR_PARTY =
  process.env.REACT_APP_OPERATOR_PARTY ||
  process.env.REACT_APP_OPERATOR_PARTY_ID ||
  'helvetswap-validator-1::12206d3b89fef3da6b1e40abcc7d2cbe174abae6062be451950095ca32397e84bf8b';

const LiquidityInterface: React.FC = () => {
  const location = useLocation();
  const navigate = useNavigate();
  const { pools, setPools } = useAppStore();
  const { partyId, walletType } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;

  const [mode, setMode] = useState<'add' | 'remove'>('add');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [lpTokens, setLpTokens] = useState<LpTokenInfo[]>([]);
  const [lpPositions, setLpPositions] = useState<LpPositionInfo[]>([]);
  const [selectedTokenA, setSelectedTokenA] = useState<TokenInfo | null>(null);
  const [selectedTokenB, setSelectedTokenB] = useState<TokenInfo | null>(null);
  const [showTokenASelector, setShowTokenASelector] = useState(false);
  const [showTokenBSelector, setShowTokenBSelector] = useState(false);
  const [amountA, setAmountA] = useState('');
  const [amountB, setAmountB] = useState('');
  const [loading, setLoading] = useState(false);
  const [isSubmittingLiquidity, setIsSubmittingLiquidity] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [liquidityResult, setLiquidityResult] = useState<any>(null);
  const [liquidityStatus, setLiquidityStatus] = useState<string | null>(null);
  const [removeSelectionCid, setRemoveSelectionCid] = useState('');
  const [removeAmount, setRemoveAmount] = useState('');
  const [removePreview, setRemovePreview] = useState<any>(null);
  const [removePreviewError, setRemovePreviewError] = useState<string | null>(null);
  const [removeStatus, setRemoveStatus] = useState<string | null>(null);
  const [removeResult, setRemoveResult] = useState<any>(null);
  const [removeLoading, setRemoveLoading] = useState(false);
  const [showLpSelector, setShowLpSelector] = useState(false);
  const [rawPools, setRawPools] = useState<PoolInfo[]>([]);
  const loopRequestStateRef = useRef({ lastCallAt: 0 });
  const { balances: loopBalances, reload: reloadLoopBalances } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
    paused: walletType === 'loop' && isSubmittingLiquidity,
  });
  const { balances: utxoBalances, reload: reloadUtxoBalances } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });
  const LP_BASELINES_STORAGE_KEY = 'clearportx:lp-baselines:v1';
  const [lpBaselines, setLpBaselines] = useState<Record<string, number>>(() => {
    if (typeof window === 'undefined') {
      return {};
    }
    try {
      const stored = window.localStorage.getItem(LP_BASELINES_STORAGE_KEY);
      return stored ? JSON.parse(stored) : {};
    } catch {
      return {};
    }
  });
  const lpBaselinesRef = useRef(lpBaselines);
  useEffect(() => {
    lpBaselinesRef.current = lpBaselines;
  }, [lpBaselines]);
  const baselinesInitializedRef = useRef(Object.keys(lpBaselinesRef.current).length > 0);

  const persistLpBaselines = useCallback((next: Record<string, number>) => {
    if (typeof window !== 'undefined') {
      window.localStorage.setItem(LP_BASELINES_STORAGE_KEY, JSON.stringify(next));
    }
  }, []);

  const computePoolTotals = useCallback((positions: LpTokenInfo[]) => {
    const totals = new Map<string, number>();
    positions.forEach(pos => {
      if (!pos.poolId) {
        return;
      }
      totals.set(pos.poolId, (totals.get(pos.poolId) ?? 0) + (pos.amount || 0));
    });
    return totals;
  }, []);

  const seedBaselinesIfNeeded = useCallback((party: string, positions: LpTokenInfo[]) => {
    if (baselinesInitializedRef.current) {
      return lpBaselinesRef.current;
    }
    const totals = computePoolTotals(positions);
    const snapshot = { ...lpBaselinesRef.current };
    let changed = false;
    totals.forEach((sum, poolId) => {
      const key = `${party}::${poolId}`;
      if (snapshot[key] === undefined) {
        snapshot[key] = sum;
        changed = true;
      }
    });
    if (changed) {
      persistLpBaselines(snapshot);
      lpBaselinesRef.current = snapshot;
      setLpBaselines(snapshot);
    }
    baselinesInitializedRef.current = true;
    return snapshot;
  }, [computePoolTotals, persistLpBaselines]);

  const augmentPoolsWithUserLiquidity = useCallback((
    poolList: PoolInfo[],
    lpPositions: LpTokenInfo[],
    baselineMap: Record<string, number>,
    actingParty: string
  ) => {
    return poolList.map(pool => {
      const normalizedPoolId = pool.poolId || pool.contractId;
      const poolPositions = lpPositions.filter(position => position.poolId === normalizedPoolId);
      const totalHeld = poolPositions.reduce((sum, position) => sum + (position.amount || 0), 0);
      const baselineKey = `${actingParty}::${normalizedPoolId}`;
      const baseline = baselineMap[baselineKey] ?? 0;
      const userLiquidity = Math.max(0, totalHeld - baseline);
      const userShare = userLiquidity > 0 && pool.totalLiquidity > 0
        ? (userLiquidity / pool.totalLiquidity) * 100
        : 0;
      return {
        ...pool,
        poolId: normalizedPoolId,
        userLiquidity,
        userShare,
      };
    });
  }, []);

  const handleResetLiquidityView = useCallback(() => {
    if (!partyId) {
      toast.error('Connect a wallet to resync liquidity view');
      return;
    }
    const totals = computePoolTotals(lpTokens);
    const snapshot = { ...lpBaselinesRef.current };
    let changed = false;
    totals.forEach((sum, poolId) => {
      const key = `${partyId}::${poolId}`;
      if (snapshot[key] !== sum) {
        snapshot[key] = sum;
        changed = true;
      }
    });
    if (changed) {
      persistLpBaselines(snapshot);
      lpBaselinesRef.current = snapshot;
      setLpBaselines(snapshot);
      toast.success('Liquidity view resynced with ledger balances');
    } else {
      toast.success('Liquidity view already matches ledger balances');
    }
  }, [partyId, computePoolTotals, lpTokens, persistLpBaselines]);

  const instrumentIdToSymbol = (instrumentId?: string) => {
    const raw = instrumentId || '';
    const upper = raw.toUpperCase();
    if (upper === 'AMULET') return 'CC';
    return upper;
  };

  const decimalAddStrings = (a: string, b: string): string => {
    const sa = a ?? '0';
    const sb = b ?? '0';
    const [ia, fa = ''] = sa.split('.');
    const [ib, fb = ''] = sb.split('.');
    const fracLen = Math.max(fa.length, fb.length);
    const normFa = (fa + '0'.repeat(fracLen)).slice(0, fracLen);
    const normFb = (fb + '0'.repeat(fracLen)).slice(0, fracLen);
    const intA = BigInt(ia || '0');
    const intB = BigInt(ib || '0');
    const fracA = BigInt(normFa || '0');
    const fracB = BigInt(normFb || '0');
    const fracSum = fracA + fracB;
    const baseStr = '1' + '0'.repeat(fracLen || 0);
    const base = BigInt(baseStr);
    const carry = fracSum / base;
    const fracRes = fracSum % base;
    const intRes = intA + intB + carry;
    const fracStr = fracLen > 0 ? fracRes.toString().padStart(fracLen, '0').replace(/0+$/, '') : '';
    return fracStr.length > 0 ? `${intRes.toString()}.${fracStr}` : intRes.toString();
  };

  const tokenBOptions = useMemo(
    () => tokens.filter(token => token.symbol !== selectedTokenA?.symbol),
    [tokens, selectedTokenA]
  );

  const balancesBySymbol = useMemo(() => {
    if (walletType === 'loop') {
      const map: Record<string, { amount: string; decimals: number }> = {};
      Object.entries(loopBalances).forEach(([symbol, entry]) => {
        map[symbol.toUpperCase()] = { amount: entry.amount, decimals: entry.decimals };
      });
      return map;
    }
    const map: Record<string, { amount: string; decimals: number }> = {};
    Object.values(utxoBalances).forEach((entry) => {
      const symbol = instrumentIdToSymbol(entry.instrumentId);
      const existing = map[symbol];
      map[symbol] = {
        amount: existing ? decimalAddStrings(existing.amount, entry.amount) : entry.amount,
        decimals: existing?.decimals ?? entry.decimals,
      };
    });
    return map;
  }, [walletType, loopBalances, utxoBalances]);


  const getBalanceEntry = useCallback((symbol?: string) => {
    if (!symbol) {
      return undefined;
    }
    return balancesBySymbol[symbol.toUpperCase()];
  }, [balancesBySymbol]);

  const formatBalanceDisplay = useCallback((symbol?: string) => {
    const entry = getBalanceEntry(symbol);
    if (!entry) {
      return '0.0000';
    }
    const numeric = Number(entry.amount);
    if (!Number.isFinite(numeric)) {
      return entry.amount;
    }
    return numeric.toLocaleString('en-US', { maximumFractionDigits: 4 });
  }, [getBalanceEntry]);

  const formatLpBalance = useCallback((raw?: string) => {
    if (!raw) return '0';
    const numeric = Number(raw);
    if (!Number.isFinite(numeric)) {
      return raw;
    }
    return numeric.toLocaleString('en-US', { maximumFractionDigits: 6 });
  }, []);

  const formatShareBps = useCallback((bps?: number) => {
    if (bps === undefined || bps === null || Number.isNaN(bps)) {
      return '—';
    }
    return `${(bps / 100).toFixed(2)}%`;
  }, []);

  const truncateId = useCallback((value?: string) => {
    if (!value) return '—';
    const trimmed = value.trim();
    if (trimmed.length <= 16) {
      return trimmed;
    }
    return `${trimmed.slice(0, 8)}…${trimmed.slice(-4)}`;
  }, []);

  const resolvePoolLabel = useCallback((poolIdOrCid?: string) => {
    if (!poolIdOrCid) return 'Unknown pool';
    const match = rawPools.find(p => p.contractId === poolIdOrCid || p.poolId === poolIdOrCid);
    if (!match) {
      return truncateId(poolIdOrCid);
    }
    return `${match.tokenA.symbol}/${match.tokenB.symbol}`;
  }, [rawPools, truncateId]);

  const reloadBalances = useCallback(async () => {
    if (walletType === 'loop') {
      await reloadLoopBalances();
      return;
    }
    await reloadUtxoBalances();
  }, [walletType, reloadLoopBalances, reloadUtxoBalances]);

  const waitForLoopCooldown = useCallback(
    async (label: string, minGapMs: number = LOOP_MIN_GAP_MS) => {
      const now = Date.now();
      const elapsed = now - loopRequestStateRef.current.lastCallAt;
      if (elapsed < minGapMs) {
        const delayMs = minGapMs - elapsed;
        setLiquidityStatus(`Waiting ${Math.ceil(delayMs / 1000)}s to avoid Loop rate limits (${label})`);
        await sleep(delayMs);
      }
      loopRequestStateRef.current.lastCallAt = Date.now();
    },
    []
  );

  useEffect(() => {
    reloadBalances();
  }, [reloadBalances, partyId, walletType]);

  useEffect(() => {
    if (!partyId) {
      setLpPositions([]);
      return;
    }
    backendApi.getLpPositions(partyId)
      .then((positions) => {
        setLpPositions(positions);
      })
      .catch(() => {
        // lpPositions load failed — non-fatal
      });
  }, [partyId]);

  // Charger les tokens depuis les pools actifs + balances utilisateur
  useEffect(() => {
    const loadTokens = async () => {
      try {
        const poolsData = walletType
          ? await backendApi.getPoolsAuthenticated().catch(() => backendApi.getPools())
          : await backendApi.getPools();

        const uniqueTokensMap = new Map<string, TokenInfo>();
        poolsData.forEach(pool => {
          if (!uniqueTokensMap.has(pool.tokenA.symbol)) {
            uniqueTokensMap.set(pool.tokenA.symbol, { ...pool.tokenA, balance: 0 });
          }
          if (!uniqueTokensMap.has(pool.tokenB.symbol)) {
            uniqueTokensMap.set(pool.tokenB.symbol, { ...pool.tokenB, balance: 0 });
          }
        });

        let tokensWithBalances = Array.from(uniqueTokensMap.values()).map(token => ({ ...token, balance: 0 }));
        let userLpTokens: LpTokenInfo[] = [];

        if (partyId) {
          try {
            const [userTokens, lpPositions, lpLedgerPositions] = await Promise.all([
              backendApi.getWalletTokens(partyId),
              backendApi.getLpTokens(partyId),
              backendApi.getLpPositions(partyId),
            ]);
            userLpTokens = lpPositions;
            setLpPositions(lpLedgerPositions);
            tokensWithBalances = tokensWithBalances.map(token => {
              const userToken = userTokens.find(t => t.symbol === token.symbol);
              return {
                ...token,
                balance: userToken?.balance || 0,
              };
            });
          } catch {
            // wallet state load failed — non-fatal
          }
        }

        setTokens(tokensWithBalances);
        setLpTokens(userLpTokens);
        setRawPools(poolsData);
        setSelectedTokenA(prev => (prev ? tokensWithBalances.find(t => t.symbol === prev.symbol) || prev : null));
        setSelectedTokenB(prev => (prev ? tokensWithBalances.find(t => t.symbol === prev.symbol) || prev : null));

        if (partyId) {
          const baselineSnapshot = seedBaselinesIfNeeded(partyId, userLpTokens);
          const enhancedPools = augmentPoolsWithUserLiquidity(poolsData, userLpTokens, baselineSnapshot, partyId);
          setPools(enhancedPools);
        } else {
          setPools(poolsData.map(pool => ({ ...pool, userLiquidity: 0, userShare: 0 })));
        }

        const pool = location.state?.pool as PoolInfo | undefined;
        if (pool) {
          const presetA = tokensWithBalances.find(t => t.symbol === pool.tokenA.symbol) || { ...pool.tokenA, balance: 0 };
          const presetB = tokensWithBalances.find(t => t.symbol === pool.tokenB.symbol) || { ...pool.tokenB, balance: 0 };
          setSelectedTokenA(presetA);
          setSelectedTokenB(presetB);
        }

      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens');
      }
    };

    loadTokens();
  }, [partyId, location.state, setPools, augmentPoolsWithUserLiquidity, seedBaselinesIfNeeded]);

  const refreshWalletState = useCallback(async (): Promise<{
    walletTokens: TokenInfo[];
    lpPositions: LpTokenInfo[];
    lpLedgerPositions: LpPositionInfo[];
  }> => {
    if (!partyId) {
      return { walletTokens: [], lpPositions: [], lpLedgerPositions: [] };
    }
    try {
      const [walletTokens, lpPositions, lpLedgerPositions] = await Promise.all([
        backendApi.getWalletTokens(partyId),
        backendApi.getLpTokens(partyId),
        backendApi.getLpPositions(partyId),
      ]);
      setTokens(currentTokens => {
        const updated = currentTokens.map(token => {
          const match = walletTokens.find(t => t.symbol === token.symbol);
          return { ...token, balance: match?.balance || 0 };
        });
        setSelectedTokenA(prev => (prev ? updated.find(t => t.symbol === prev.symbol) || prev : null));
        setSelectedTokenB(prev => (prev ? updated.find(t => t.symbol === prev.symbol) || prev : null));
        return updated;
      });
      setLpTokens(lpPositions);
      setLpPositions(lpLedgerPositions);
      return { walletTokens, lpPositions, lpLedgerPositions };
    } catch (error) {
      console.error('Failed to refresh wallet balances:', error);
      return { walletTokens: [], lpPositions: [], lpLedgerPositions: [] };
    }
  }, [partyId]);

  useEffect(() => {
    if (!rawPools.length) {
      return;
    }
    if (!partyId) {
      setPools(rawPools.map(pool => ({ ...pool, userLiquidity: 0, userShare: 0 })));
      return;
    }
    const baselineSnapshot = lpBaselinesRef.current;
    const enhancedPools = augmentPoolsWithUserLiquidity(rawPools, lpTokens, baselineSnapshot, partyId);
    setPools(enhancedPools);
  }, [rawPools, lpTokens, lpBaselines, augmentPoolsWithUserLiquidity, setPools, partyId]);

  // Calculer automatiquement le montant B en fonction du ratio du pool
  useEffect(() => {
    if (!selectedTokenA || !selectedTokenB || !amountA || mode !== 'add') return;

    const calculateAmountB = () => {
      setCalculating(true);

      // Trouver le pool correspondant
      const pool = pools.find(p =>
        (p.tokenA.symbol === selectedTokenA.symbol && p.tokenB.symbol === selectedTokenB.symbol) ||
        (p.tokenA.symbol === selectedTokenB.symbol && p.tokenB.symbol === selectedTokenA.symbol)
      );

      if (pool) {
        const isTokenAFirst = pool.tokenA.symbol === selectedTokenA.symbol;
        const ratio = isTokenAFirst
          ? pool.reserveB / pool.reserveA
          : pool.reserveA / pool.reserveB;

        const calculatedAmountB = parseFloat(amountA) * ratio;
        setAmountB(calculatedAmountB.toFixed(6));
      } else {
        // Nouveau pool - ratio 1:1 par défaut
        setAmountB(amountA);
      }

      setCalculating(false);
    };

    const timeout = setTimeout(calculateAmountB, 300);
    return () => clearTimeout(timeout);
  }, [amountA, selectedTokenA, selectedTokenB, pools, mode]);

  const handleAddLiquidity = async () => {
    if (!selectedTokenA || !selectedTokenB || !amountA || !amountB) {
      toast.error('Please fill in all fields');
      return;
    }

    if (!partyId) {
      toast.error('Connect a wallet to add liquidity');
      return;
    }

    const amountANum = parseFloat(amountA);
    const amountBNum = parseFloat(amountB);

    if (isNaN(amountANum) || isNaN(amountBNum) || amountANum <= 0 || amountBNum <= 0) {
      toast.error('Please enter valid amounts');
      return;
    }

    try {
      setLoading(true);
      setLiquidityResult(null);
      setLiquidityStatus(null);

      const pool = pools.find(p =>
        (p.tokenA.symbol === selectedTokenA.symbol && p.tokenB.symbol === selectedTokenB.symbol) ||
        (p.tokenA.symbol === selectedTokenB.symbol && p.tokenB.symbol === selectedTokenA.symbol)
      );

      if (!pool) {
        toast.error('Pool not found for this token pair');
        return;
      }

      const targetPoolId = pool.poolId || pool.contractId;
      if (!targetPoolId) {
        toast.error('Pool identifier missing; refresh pools and try again.');
        return;
      }

      const poolCid =
        pool.contractId && (pool.poolId === pool.contractId || pool.contractId.startsWith('00'))
          ? pool.contractId
          : await backendApi.resolvePoolCid(targetPoolId);
      if (!poolCid) {
        toast.error('Unable to resolve pool CID. Refresh pools and try again.');
        return;
      }

      if (walletType !== 'loop') {
        const response = await backendApi.addLiquidityByCid({
          poolCid,
          poolId: targetPoolId,
          amountA: amountANum.toFixed(10),
          amountB: amountBNum.toFixed(10),
          minLPTokens: '0.0000000001',
        });

        const toastAmount = (() => {
          const raw = typeof response?.lpAmount === 'string' ? response.lpAmount.trim() : '';
          if (raw) {
            const numeric = Number(raw);
            if (Number.isFinite(numeric) && numeric > 0) {
              if (numeric >= 1) {
                return numeric.toLocaleString('en-US', { maximumFractionDigits: 4 });
              }
              return numeric.toPrecision(4);
            }
            return raw;
          }
          return estimatedLPTokens.toFixed(4);
        })();
        toast.success(`Liquidity added! LP tokens: ${toastAmount}`);
      } else {
        setIsSubmittingLiquidity(true);
        const connector = walletManager.getOrCreateLoopConnector();
        const status = await connector.ensureConnected(`liquidity-${Date.now()}`);
        if (!status.connected || !getLoopProvider()) {
          const message = status.error || 'Loop not connected';
          setLiquidityResult({ ok: false, error: { code: 'CONNECT', message } });
          toast.error(message);
          return;
        }

        const holdingPool = await backendApi.getHoldingPool(poolCid);
        const poolInstrumentA = holdingPool?.instrumentA;
        const poolInstrumentB = holdingPool?.instrumentB;
        if (!poolInstrumentA?.admin || !poolInstrumentA?.id || !poolInstrumentB?.admin || !poolInstrumentB?.id) {
          toast.error('Pool instruments missing. Refresh pools and try again.');
          return;
        }
        const instrumentA = {
          instrumentAdmin: poolInstrumentA.admin,
          instrumentId: poolInstrumentA.id,
        };
        const instrumentB = {
          instrumentAdmin: poolInstrumentB.admin,
          instrumentId: poolInstrumentB.id,
        };

        const poolSymbolA = pool.tokenA.symbol;
        const isSelectedAForPoolA = selectedTokenA.symbol === poolSymbolA;
        const amountForA = isSelectedAForPoolA ? amountANum : amountBNum;
        const amountForB = isSelectedAForPoolA ? amountBNum : amountANum;

        const requestId = `liq-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
        const deadline = new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString();
        const amountForAStr = amountForA.toFixed(10);
        const amountForBStr = amountForB.toFixed(10);
        const baseMemo = {
          v: 1,
          kind: 'addLiquidity',
          requestId,
          poolCid,
          receiverParty: OPERATOR_PARTY,
          deadline,
        };
        const memoA = JSON.stringify({
          ...baseMemo,
          leg: 'A',
          instrumentAdmin: instrumentA.instrumentAdmin,
          instrumentId: instrumentA.instrumentId,
          amount: amountForAStr,
        });
        const memoB = JSON.stringify({
          ...baseMemo,
          leg: 'B',
          instrumentAdmin: instrumentB.instrumentAdmin,
          instrumentId: instrumentB.instrumentId,
          amount: amountForBStr,
        });

        // Use SDK 0.11.x provider.transfer() which combines prepareTransfer + submitTransaction
        // This avoids the 503 from Loop's backend on consecutive prepareTransfer calls
        const provider = getLoopProvider();
        const transferFn = (provider as any)?.transfer;
        if (typeof transferFn !== 'function') {
          // Fallback: try wallet.transfer if provider.transfer is not directly available
          const walletTransfer = (provider as any)?.wallet?.transfer;
          if (typeof walletTransfer !== 'function') {
            setLiquidityResult({ ok: false, error: { code: 'SDK', message: 'Loop SDK does not support provider.transfer(). Upgrade to @fivenorth/loop-sdk >= 0.11.0' } });
            toast.error('Loop SDK version too old. Please refresh the page.');
            return;
          }
        }

        const doTransfer = async (
          label: string,
          amount: string,
          instrument: { instrumentAdmin: string; instrumentId: string },
          memo: string
        ) => {
          const p = getLoopProvider();
          const fn = (p as any)?.transfer;
          if (typeof fn !== 'function') {
            throw new Error(`Loop provider.transfer() not available for ${label}`);
          }
          return fn.call(p, OPERATOR_PARTY, amount, {
            instrument_admin: instrument.instrumentAdmin,
            instrument_id: instrument.instrumentId,
          }, {
            memo,
            requestedAt: new Date().toISOString(),
            executeBefore: deadline,
            executionMode: 'wait',
            requestTimeout: 120000,
          });
        };

        setLiquidityStatus('Submitting transfer A via Loop wallet...');
        let transferAResult: any;
        try {
          transferAResult = await withLoopRateLimitRetry(
            'Transfer A',
            () => doTransfer('Transfer A', amountForAStr, instrumentA, memoA),
            {
              onRetry: (delayMs) =>
                setLiquidityStatus(`Transfer A rate limited. Retrying in ${Math.ceil(delayMs / 1000)}s`),
              beforeAttempt: () => waitForLoopCooldown('transfer A'),
            }
          );
        } catch (errA: any) {
          const cancelled = isUserCancelled(errA);
          const message = cancelled ? 'Transfer A was cancelled.' : (errA?.message || 'Transfer A failed');
          setLiquidityResult({ ok: false, error: { code: 'TRANSFER_A', message, details: errA } });
          toast.error(message);
          return;
        }

        const statusA = String(transferAResult?.status ?? '').toLowerCase();
        if (statusA.includes('fail') || transferAResult?.error) {
          setLiquidityResult({ ok: false, error: { code: 'TRANSFER_A', message: transferAResult?.error?.error_message || 'Transfer A failed', details: transferAResult } });
          toast.error('Inbound transfer A failed');
          return;
        }
        setLiquidityStatus('Transfer A succeeded. Waiting before transfer B...');
        await sleep(6000);

        setLiquidityStatus('Submitting transfer B via Loop wallet...');
        let transferBResult: any;
        // Transfer A leaves a pending Loop network fee ("gas"). Until it is paid, the Loop
        // backend rejects transfer B with HTTP 402 (PaymentRequiredError). The wallet prompts
        // to pay that fee, but settling it can be slow — and we used to abort the whole add on
        // the first 402, so transfer B never landed (nobody could add liquidity on MainNet,
        // which charges fees; DevNet has none, so this path is MainNet-only). Instead: detect
        // the pending-fee rejection and retry transfer B, giving the user time to approve the
        // fee in their wallet. The backend LiquidityPoller is the safety net if B lands after
        // we stop retrying.
        const PENDING_FEE_MAX_RETRIES = 6;
        const PENDING_FEE_RETRY_DELAY_MS = 12000;
        // Loop signals an unpaid network-gas fee in two ways: it can THROW (handled in the
        // catch below) OR it can RESOLVE provider.transfer() with a reject-result payload
        // ({ type: 'reject_request', payload: { code: 'PENDING_FEE_UNPAID', ... } }). The
        // resolved-reject path falls straight through the `break` below if we don't inspect
        // the result, so the pending-fee retry never fires. Detect it shape-agnostically.
        const isPendingFeeResult = (r: any): boolean => {
          if (!r) return false;
          try { return /pending_fee_unpaid|still unpaid|pending network gas/i.test(JSON.stringify(r)); }
          catch { return false; }
        };
        for (let feeAttempt = 0; ; feeAttempt += 1) {
          try {
            transferBResult = await withLoopRateLimitRetry(
              'Transfer B',
              () => doTransfer('Transfer B', amountForBStr, instrumentB, memoB),
              {
                onRetry: (delayMs) =>
                  setLiquidityStatus(`Transfer B rate limited. Retrying in ${Math.ceil(delayMs / 1000)}s`),
                beforeAttempt: () => waitForLoopCooldown('transfer B'),
              }
            );
            if (isPendingFeeResult(transferBResult)) {
              if (feeAttempt < PENDING_FEE_MAX_RETRIES) {
                setLiquidityStatus(`Pending network fee from transfer A — approve it in your Loop wallet. Retrying transfer B (${feeAttempt + 1}/${PENDING_FEE_MAX_RETRIES}) in ${Math.ceil(PENDING_FEE_RETRY_DELAY_MS / 1000)}s...`);
                await sleep(PENDING_FEE_RETRY_DELAY_MS);
                continue;
              }
              // Retries exhausted — mirror the throw path instead of break->consume.
              setLiquidityResult({ ok: false, error: { code: 'TRANSFER_B', message: 'Transfer B is still blocked by an unpaid network fee from transfer A. Once the fee settles the add can still complete (the poller will finish it).', details: transferBResult } });
              toast.error('Transfer B is still blocked by an unpaid network fee from transfer A.');
              return;
            }
            break;
          } catch (errB: any) {
            if (isUserCancelled(errB)) {
              setLiquidityResult({ ok: false, error: { code: 'TRANSFER_B', message: 'Transfer B was cancelled. Transfer A was already sent.', details: errB } });
              toast.error('Transfer B was cancelled. Transfer A was already sent.');
              return;
            }
            const isPendingFee =
              errB?.trackingId != null ||
              errB?.gasAmount != null ||
              Number(errB?.status ?? errB?.response?.status) === 402 ||
              /payment required|pending fee|unpaid|pending gas|pending_fee_unpaid|pending network gas/i.test(String(errB?.message ?? errB?.payload?.message ?? '')) ||
              /payment|pending.?fee|pending_fee_unpaid/i.test(String(errB?.code ?? errB?.payload?.code ?? ''));
            if (isPendingFee && feeAttempt < PENDING_FEE_MAX_RETRIES) {
              setLiquidityStatus(`Pending network fee from transfer A — approve it in your Loop wallet. Retrying transfer B (${feeAttempt + 1}/${PENDING_FEE_MAX_RETRIES}) in ${Math.ceil(PENDING_FEE_RETRY_DELAY_MS / 1000)}s...`);
              await sleep(PENDING_FEE_RETRY_DELAY_MS);
              continue;
            }
            const message = isPendingFee
              ? 'Transfer B is still blocked by an unpaid network fee from transfer A. Once the fee settles the add can still complete (the poller will finish it).'
              : (errB?.message || 'Transfer B failed');
            setLiquidityResult({ ok: false, error: { code: 'TRANSFER_B', message, details: errB } });
            toast.error(message);
            return;
          }
        }

        const statusB = String(transferBResult?.status ?? '').toLowerCase();
        const transferBSucceeded = !statusB.includes('fail') && !transferBResult?.error;
        let consumeResult: any = null;
        if (!transferBSucceeded) {
          setLiquidityResult({ ok: false, error: { code: 'TRANSFER_B', message: transferBResult?.error?.error_message || 'Transfer B failed', details: transferBResult } });
          toast.error('Inbound transfer B failed');
        } else {
          setLiquidityStatus('Consuming liquidity (backend)');
          await sleep(4000);
          consumeResult = await backendApi.consumeDevnetLiquidity({ requestId, poolCid });
          if (consumeResult?.ok) {
            setLiquidityStatus('Liquidity added. LP minted.');
            toast.success('Liquidity added successfully');
            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('clearportx:transactions:refresh', {
                detail: { source: 'liquidity:add', requestId },
              }));
            }
          } else {
            if (consumeResult?.error?.code === 'MISSING_INBOUND_TIS_FOR_POOL_INSTRUMENT') {
              try {
                await backendApi.inspectDevnetLiquidity(requestId, poolCid);
              } catch {
                // inspect failed — non-fatal
              }
            }
            toast.error(consumeResult?.error?.message || 'Liquidity consume failed');
          }
        }

        setLiquidityResult({
          transferA: transferAResult,
          transferB: transferBResult,
          consume: consumeResult,
        });
      }

      const updatedPools = await backendApi.getPoolsAuthenticated().catch(() => backendApi.getPools());
      setRawPools(updatedPools);
      await refreshWalletState();

      setAmountA('');
      setAmountB('');

      setTimeout(() => {
        navigate('/pools');
      }, 1500);
    } catch (error: any) {
      console.error('Error adding liquidity:', error);
      console.error('Error response:', error.response?.data);

      const backendMessage = error.response?.data?.message;
      const backendDetails = error.response?.data?.details;
      const detailSuffix = backendDetails ? ` (${backendDetails})` : '';
      const composedMessage = backendMessage ? `${backendMessage}${detailSuffix}` : null;

      if (composedMessage) {
        toast.error(`Failed: ${composedMessage}`);
      } else if (error.response?.status === 422) {
        toast.error('Invalid amounts. Check your token balances.');
      } else if (error.response?.status === 429) {
        toast.error('Rate limit exceeded. Please wait and try again.');
      } else if (error.response?.status === 401) {
        toast.error('Authentication required. Please connect your wallet.');
      } else if (error.response?.status === 404) {
        toast.error('Pool not found. Please select a valid pool.');
      } else {
        toast.error('An error occurred while adding liquidity');
      }
    } finally {
      setIsSubmittingLiquidity(false);
      setLoading(false);
    }
  };

  const handleRemoveLiquidity = async () => {
    if (!partyId) {
      toast.error('Connect a wallet to remove liquidity');
      return;
    }
    if (!selectedLpToken) {
      toast.error('Select an LP position first');
      return;
    }
    const burnAmountNum = parseFloat(removeAmount);
    if (!Number.isFinite(burnAmountNum) || burnAmountNum <= 0) {
      toast.error('Enter a valid LP amount to burn');
      return;
    }
    if (burnAmountNum > selectedLpToken.amount) {
      toast.error('LP burn amount exceeds your balance');
      return;
    }

    const requestId = `remove-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    const deadlineIso = new Date(Date.now() + 2 * 60 * 60 * 1000).toISOString();

    try {
      setRemoveLoading(true);
      setRemoveResult(null);
      setRemoveStatus(null);

      const consumePayload: any = {
        requestId,
        lpCid: selectedLpToken.contractId,
        receiverParty: partyId,
        lpBurnAmount: burnAmountNum.toFixed(10),
        minOutA: '0',
        minOutB: '0',
        deadlineIso,
      };
      if (resolvedRemovePoolCid) {
        consumePayload.poolCid = resolvedRemovePoolCid;
      }
      const consumeResult = await backendApi.consumeDevnetLiquidityRemove(consumePayload);

      setRemoveResult(consumeResult);
      if (consumeResult?.ok) {
        setRemoveStatus('Remove liquidity submitted.');
        await refreshWalletState();
        const updatedPools = await backendApi.getPoolsAuthenticated().catch(() => backendApi.getPools());
        setRawPools(updatedPools);
      } else {
        if (consumeResult?.error?.code === 'LEGACY_LP_POOL_NOT_FOUND') {
          setRemoveStatus('This LP references an old pool contract. Please migrate your LP with the operator.');
          toast.error('Legacy LP detected. Please migrate your LP.');
        } else {
          setRemoveStatus(consumeResult?.error?.message || 'Remove liquidity failed.');
        }
      }
    } catch (error: any) {
      console.error('Error removing liquidity:', error);
      const legacyCode = error?.response?.data?.error?.code;
      if (legacyCode === 'LEGACY_LP_POOL_NOT_FOUND') {
        toast.error('This LP references an old pool contract. Please migrate your LP with the operator.');
      } else {
        toast.error('An error occurred while removing liquidity');
      }
      setRemoveStatus('Remove liquidity failed.');
    } finally {
      setRemoveLoading(false);
    }
  };

  const existingPool = useMemo(() => (
    pools.find(p =>
      (p.tokenA.symbol === selectedTokenA?.symbol && p.tokenB.symbol === selectedTokenB?.symbol) ||
      (p.tokenA.symbol === selectedTokenB?.symbol && p.tokenB.symbol === selectedTokenA?.symbol)
    ) || null
  ), [pools, selectedTokenA, selectedTokenB]);

  const estimatedLPTokens = useMemo(() => {
    const valueA = parseFloat(amountA);
    const valueB = parseFloat(amountB);
    if (!Number.isFinite(valueA) || !Number.isFinite(valueB) || valueA <= 0 || valueB <= 0) {
      return 0;
    }
    if (!existingPool || existingPool.totalLiquidity <= 0 || existingPool.reserveA <= 0 || existingPool.reserveB <= 0) {
      return Math.sqrt(valueA * valueB);
    }
    const shareA = (valueA * existingPool.totalLiquidity) / existingPool.reserveA;
    const shareB = (valueB * existingPool.totalLiquidity) / existingPool.reserveB;
    return Math.min(shareA, shareB);
  }, [amountA, amountB, existingPool]);

  const poolShare = existingPool && estimatedLPTokens
    ? (estimatedLPTokens / (existingPool.totalLiquidity + estimatedLPTokens)) * 100
    : 100;

  const activePoolIds = useMemo(
    () => new Set(rawPools.map(p => p.poolId)),
    [rawPools]
  );
  const removePositions = useMemo(
    () => lpTokens.filter(position => position.amount && position.amount > 0 && activePoolIds.has(position.poolId)),
    [lpTokens, activePoolIds]
  );
  const selectedLpToken = useMemo(
    () => removePositions.find(position => position.contractId === removeSelectionCid) || null,
    [removePositions, removeSelectionCid]
  );
  const resolvedRemovePoolCid = useMemo(() => {
    if (!selectedLpToken?.poolId) {
      return null;
    }
    const poolId = selectedLpToken.poolId;
    const match = rawPools.find(p => p.poolId === poolId);
    if (match?.contractId) {
      return match.contractId;
    }
    if (poolId.startsWith('00') && poolId.length > 20) {
      return poolId;
    }
    return null;
  }, [rawPools, selectedLpToken]);

  const removePool = useMemo(() => {
    if (!selectedLpToken) {
      return null;
    }
    const poolId = selectedLpToken.poolId;
    return rawPools.find(pool =>
      pool.poolId === poolId || pool.contractId === resolvedRemovePoolCid
    ) || null;
  }, [rawPools, selectedLpToken, resolvedRemovePoolCid]);

  useEffect(() => {
    if (mode !== 'remove') {
      return;
    }
    if (!removePositions.length) {
      setRemoveSelectionCid('');
      return;
    }
    if (!selectedLpToken) {
      setRemoveSelectionCid(removePositions[0].contractId);
    }
  }, [mode, removePositions, selectedLpToken]);

  useEffect(() => {
    if (mode !== 'remove') {
      return;
    }
    setRemoveResult(null);
    setRemoveStatus(null);
  }, [mode, removeSelectionCid, removeAmount]);

  useEffect(() => {
    if (mode !== 'remove') {
      return;
    }
    if (!partyId || !selectedLpToken) {
      setRemovePreview(null);
      setRemovePreviewError(null);
      return;
    }
    if (!resolvedRemovePoolCid) {
      setRemovePreview(null);
      setRemovePreviewError('Pool details unavailable. You can still submit removal.');
      return;
    }
    const amountNum = parseFloat(removeAmount);
    if (!Number.isFinite(amountNum) || amountNum <= 0) {
      setRemovePreview(null);
      setRemovePreviewError(null);
      return;
    }
    if (amountNum > selectedLpToken.amount) {
      setRemovePreview(null);
      setRemovePreviewError('LP burn amount exceeds balance.');
      return;
    }
    const requestId = `preview-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`;
    const timeout = setTimeout(() => {
      const inspectParams: any = {
        requestId,
        lpCid: selectedLpToken.contractId,
        receiverParty: partyId,
        lpBurnAmount: amountNum.toFixed(10),
      };
      if (resolvedRemovePoolCid) {
        inspectParams.poolCid = resolvedRemovePoolCid;
      }
      backendApi.inspectDevnetLiquidityRemove(inspectParams).then((res) => {
        if (res?.ok) {
          setRemovePreview(res.result ?? res);
          setRemovePreviewError(null);
        } else {
          if (res?.error?.code === 'LEGACY_LP_POOL_NOT_FOUND') {
            setRemovePreview(null);
            setRemovePreviewError('Legacy LP detected. Please migrate your LP with the operator.');
          } else {
            setRemovePreview(null);
            setRemovePreviewError(res?.error?.message || 'Unable to preview removal.');
          }
        }
      }).catch((err) => {
        // remove liquidity preview failed — non-fatal
        setRemovePreview(null);
        setRemovePreviewError('Unable to preview removal.');
      });
    }, 300);
    return () => clearTimeout(timeout);
  }, [mode, partyId, selectedLpToken, removeAmount, resolvedRemovePoolCid]);

  const poolPositions = useMemo(
    () => pools.filter(p => p.userLiquidity && p.userLiquidity > 0),
    [pools]
  );
  const filteredLpPositions = useMemo(
    () => lpPositions.filter(p => p.poolId && activePoolIds.has(p.poolId)),
    [lpPositions, activePoolIds]
  );
  const hasAnyPositions = poolPositions.length > 0 || filteredLpPositions.length > 0;

  return (
    <div className="fu" style={{ maxWidth: "var(--liq-max-w)", margin: "0 auto" }}>
      {/* Header */}
      <div style={{ marginBottom: "1.5rem" }}>
        <button className="btn-ghost" style={{ marginBottom: "1rem", gap: "0.4rem" }} onClick={() => navigate('/pools')}>
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M7 1L3 5L7 9" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
          Back to Pools
        </button>
        <div style={{ fontFamily: "var(--display)", fontSize: "1.6rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)", marginBottom: "0.3rem" }}>Manage Liquidity</div>
        <p style={{ fontFamily: "var(--serif)", fontSize: "0.85rem", color: "var(--text-3)" }}>Add or remove liquidity from pools</p>
      </div>

      <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)", marginBottom: "2rem" }}>
        {/* Tab toggle */}
        <div style={{ display: "flex", borderBottom: "1px solid var(--rule)" }}>
          <button className={`tab-btn${mode === "add" ? " active" : ""}`} onClick={() => setMode("add")} style={{ flex: 1, fontWeight: 500 }}>Add Liquidity</button>
          <button className={`tab-btn${mode === "remove" ? " active" : ""}`} onClick={() => setMode("remove")} style={{ flex: 1, fontWeight: 500 }}>Remove Liquidity</button>
        </div>

        {mode === 'add' ? (
          /* ADD LIQUIDITY */
          <div style={{ padding: "1.4rem var(--form-px)" }}>
            {/* Token A */}
            <div style={{ marginBottom: "1rem" }}>
              <div className="label-caps" style={{ marginBottom: "0.5rem" }}>Token A</div>
              <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
                <button className="token-select-btn" onClick={() => setShowTokenASelector(true)} style={{ height: 40 }}>
                  <SwissCross size={7} />
                  {selectedTokenA?.symbol || 'Select'}
                  <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
                </button>
                <input
                  value={amountA} onChange={e => setAmountA(e.target.value)} placeholder="0.0"
                  style={{ flex: 1, background: "var(--ink-4)", border: "1px solid var(--rule)", padding: "0.6rem 0.8rem", fontFamily: "var(--mono)", fontSize: "1rem", fontWeight: 300, color: "var(--text-1)", outline: "none", height: 40 }}
                />
              </div>
              <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)", marginTop: "0.3rem" }}>
                Balance: {formatBalanceDisplay(selectedTokenA?.symbol)} {selectedTokenA?.symbol || ''}
              </div>
            </div>

            {/* Separator */}
            <div style={{ display: "flex", justifyContent: "center", padding: "0.3rem 0" }}>
              <div style={{ width: 28, height: 28, background: "var(--ink-4)", border: "1px solid var(--rule-gold)", display: "flex", alignItems: "center", justifyContent: "center" }}>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.9rem", color: "var(--gold)", lineHeight: 1 }}>+</span>
              </div>
            </div>

            {/* Token B */}
            <div style={{ marginBottom: "1.2rem" }}>
              <div className="label-caps" style={{ marginBottom: "0.5rem" }}>Token B</div>
              <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
                <button className="token-select-btn" onClick={() => setShowTokenBSelector(true)} style={{ height: 40 }}>
                  <SwissCross size={7} />
                  {selectedTokenB?.symbol || 'Select'}
                  <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
                </button>
                <input
                  value={calculating ? '...' : amountB} onChange={e => setAmountB(e.target.value)} placeholder="0.0" disabled={calculating}
                  style={{ flex: 1, background: "var(--ink-4)", border: "1px solid var(--rule)", padding: "0.6rem 0.8rem", fontFamily: "var(--mono)", fontSize: "1rem", fontWeight: 300, color: "var(--text-1)", outline: "none", height: 40 }}
                />
              </div>
              <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)", marginTop: "0.3rem" }}>
                Balance: {formatBalanceDisplay(selectedTokenB?.symbol)} {selectedTokenB?.symbol || ''}
              </div>
            </div>

            {/* Pool Info */}
            {existingPool && (
              <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)", padding: "0.8rem 1rem", marginBottom: "1rem" }}>
                {[
                  ["Current Liquidity", `${existingPool.reserveA.toFixed(2)} ${existingPool.tokenA.symbol} / ${existingPool.reserveB.toFixed(2)} ${existingPool.tokenB.symbol}`],
                  ["Total LP Tokens", existingPool.totalLiquidity.toFixed(4)],
                  ["APR", `${existingPool.apr.toFixed(2)}%`],
                ].map(([k, v]) => (
                  <div key={k} style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
                    <span style={{ color: "var(--text-3)" }}>{k}</span>
                    <span style={{ color: "var(--text-2)" }}>{v}</span>
                  </div>
                ))}
              </div>
            )}

            {/* Preview */}
            {amountA && amountB && selectedTokenA && selectedTokenB && (
              <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.8rem 1rem", marginBottom: "1rem" }}>
                <div style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
                  <span style={{ color: "var(--text-3)" }}>LP Tokens</span>
                  <span style={{ color: "var(--gold)" }}>{estimatedLPTokens.toFixed(4)}</span>
                </div>
                <div style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
                  <span style={{ color: "var(--text-3)" }}>Pool Share</span>
                  <span style={{ color: "var(--text-2)" }}>{poolShare.toFixed(2)}%</span>
                </div>
              </div>
            )}

            <button className="btn-primary" disabled={loading || !partyId || !selectedTokenA || !selectedTokenB || !amountA || !amountB} onClick={handleAddLiquidity} style={{ width: "100%", justifyContent: "center", height: 44 }}>
              <SwissCross size={9} color="var(--ink)" />
              {loading ? 'Adding Liquidity...' : !partyId ? 'Connect Wallet' : 'Add Liquidity'}
            </button>

            {liquidityStatus && (
              <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-2)", marginTop: "0.8rem" }}>{liquidityStatus}</div>
            )}

            {liquidityResult?.consume?.ok && (
              <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.8rem 1rem", marginTop: "0.8rem" }}>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--green)", marginBottom: "0.3rem" }}>Liquidity added successfully.</div>
                {[
                  ["LP Minted", liquidityResult.consume.result?.lpMinted],
                  ["New Reserve A", liquidityResult.consume.result?.newReserveA],
                  ["New Reserve B", liquidityResult.consume.result?.newReserveB],
                ].map(([k, v]) => (
                  <div key={k} style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.64rem", marginBottom: "0.2rem" }}>
                    <span style={{ color: "var(--text-3)" }}>{k}</span>
                    <span style={{ color: "var(--text-1)" }}>{v}</span>
                  </div>
                ))}
              </div>
            )}

            {liquidityResult?.consume && !liquidityResult.consume.ok && (
              <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--red-muted)", marginTop: "0.5rem" }}>
                {liquidityResult.consume.error?.message || 'Liquidity consume failed.'}
              </div>
            )}
          </div>
        ) : (
          /* REMOVE LIQUIDITY */
          <div style={{ padding: "1.4rem var(--form-px)" }}>
            {!partyId ? (
              <div style={{ padding: "2rem 0", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
                Connect your wallet to remove liquidity.
              </div>
            ) : removePositions.length === 0 ? (
              <div style={{ padding: "2rem 0", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
                No LP positions available to remove.
              </div>
            ) : (
              <>
                {/* LP Position selector */}
                <div style={{ marginBottom: "1rem" }}>
                  <div className="label-caps" style={{ marginBottom: "0.5rem" }}>LP Position</div>
                  <button className="token-select-btn" onClick={() => setShowLpSelector(true)} style={{ width: "100%" }}>
                    {selectedLpToken ? (
                      <>
                        <span style={{ display: "inline-flex", alignItems: "center", flexShrink: 0 }}>
                          <SwissCross size={7} />
                          <span style={{ marginLeft: -8, display: "inline-flex" }}><SwissCross size={7} /></span>
                        </span>
                        {(() => {
                          const pool = rawPools.find(p => p.poolId === selectedLpToken.poolId);
                          return pool ? `${pool.tokenA.symbol} / ${pool.tokenB.symbol}` : resolvePoolLabel(selectedLpToken.poolId);
                        })()}
                      </>
                    ) : (
                      'Select LP Position'
                    )}
                    <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
                  </button>
                  {selectedLpToken && (
                    <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)", marginTop: "0.3rem" }}>
                      Balance: {formatLpBalance(String(selectedLpToken.amount))} LP
                    </div>
                  )}
                </div>

                {/* Burn amount */}
                <div style={{ marginBottom: "0.8rem" }}>
                  <div className="label-caps" style={{ marginBottom: "0.5rem" }}>LP to Burn</div>
                  <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
                    <input
                      value={removeAmount} onChange={e => setRemoveAmount(e.target.value)} placeholder="0.0"
                      style={{ flex: 1, background: "var(--ink-4)", border: "1px solid var(--rule)", padding: "0.6rem 0.8rem", fontFamily: "var(--mono)", fontSize: "1rem", fontWeight: 300, color: "var(--text-1)", outline: "none", height: 40 }}
                    />
                    {selectedLpToken && (
                      <button className="btn-ghost" style={{ height: 40, fontSize: "0.68rem" }} onClick={() => setRemoveAmount(String(selectedLpToken.amount))}>
                        Max
                      </button>
                    )}
                  </div>
                </div>

                {removePreviewError && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--red-muted)", marginBottom: "0.5rem" }}>{removePreviewError}</div>
                )}

                {removePreview && (
                  <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.7rem 1rem", marginBottom: "0.8rem" }}>
                    <div className="label-caps" style={{ marginBottom: "0.4rem" }}>Estimated Payout</div>
                    <div style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.72rem", marginBottom: "0.2rem" }}>
                      <span style={{ color: "var(--text-3)" }}>{removePool?.tokenA?.symbol || 'Token A'}</span>
                      <span style={{ color: "var(--text-1)" }}>{formatLpBalance(removePreview.outAmountA)}</span>
                    </div>
                    <div style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
                      <span style={{ color: "var(--text-3)" }}>{removePool?.tokenB?.symbol || 'Token B'}</span>
                      <span style={{ color: "var(--text-1)" }}>{formatLpBalance(removePreview.outAmountB)}</span>
                    </div>
                  </div>
                )}


                <button className="btn-primary" disabled={removeLoading || !removeSelectionCid || !removeAmount} onClick={handleRemoveLiquidity} style={{ width: "100%", justifyContent: "center", height: 44 }}>
                  <SwissCross size={9} color="var(--ink)" />
                  {removeLoading ? 'Submitting...' : 'Remove Liquidity'}
                </button>

                {removeStatus && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-2)", marginTop: "0.8rem" }}>{removeStatus}</div>
                )}

                {removeResult?.ok && removeResult?.result && (
                  <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.8rem 1rem", marginTop: "0.8rem" }}>
                    <div style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--green)", marginBottom: "0.3rem" }}>Removal submitted.</div>
                    <div style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.64rem", marginBottom: "0.2rem" }}>
                      <span style={{ color: "var(--text-3)" }}>Payout A</span>
                      <span style={{ color: "var(--text-1)" }}>{removeResult.result.payoutStatusA === 'COMPLETED' ? 'Completed' : `Created (${removeResult.result.payoutCidA || 'pending'})`}</span>
                    </div>
                    <div style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.64rem" }}>
                      <span style={{ color: "var(--text-3)" }}>Payout B</span>
                      <span style={{ color: "var(--text-1)" }}>{removeResult.result.payoutStatusB === 'COMPLETED' ? 'Completed' : `Created (${removeResult.result.payoutCidB || 'pending'})`}</span>
                    </div>
                  </div>
                )}

                {removeResult?.ok === false && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--red-muted)", marginTop: "0.5rem" }}>
                    {removeResult?.error?.code === 'LEGACY_LP_POOL_NOT_FOUND'
                      ? 'This LP references an old pool contract. Please migrate your LP with the operator.'
                      : (removeResult?.error?.message || 'Remove liquidity failed.')}
                  </div>
                )}
              </>
            )}
          </div>
        )}
      </div>

      {/* Your Liquidity Positions */}
      <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)" }}>
        <div style={{ padding: "1rem var(--table-cell-px)", borderBottom: "1px solid var(--rule)", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontFamily: "var(--display)", fontSize: "0.95rem", fontStyle: "italic", color: "var(--text-1)" }}>Your Liquidity Positions</span>
          <button className="btn-ghost" style={{ height: 28, fontSize: "0.64rem", padding: "0 0.6rem", gap: "0.3rem" }} onClick={handleResetLiquidityView}>
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M5 1V9M1 5A4 4 0 009 5A4 4 0 001 5" stroke="var(--text-3)" strokeWidth="1" /></svg>
            Re-sync view
          </button>
        </div>

        {!hasAnyPositions ? (
          <div style={{ padding: "2rem var(--table-cell-px)", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
            No liquidity positions yet
          </div>
        ) : (
          <>
            {/* Table header */}
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 0.8fr", padding: "0.5rem var(--table-cell-px)", borderBottom: "1px solid var(--rule)" }}>
              {["Pair", "LP Balance", "Updated"].map(h => (
                <span key={h} style={{ fontFamily: "var(--mono)", fontSize: "0.5rem", letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--text-3)" }}>{h}</span>
              ))}
            </div>

            {poolPositions.map(pool => (
              <div key={pool.contractId} className="rule-hover" style={{ padding: "1rem var(--table-cell-px)", borderBottom: "1px solid var(--rule)", display: "grid", gridTemplateColumns: "1fr 1fr 0.8fr", alignItems: "center" }}>
                <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                  <SwissCross size={7} />
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>{pool.tokenA.symbol}/{pool.tokenB.symbol}</span>
                </div>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-2)" }}>{pool.userLiquidity?.toFixed(6)} LP</span>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)" }}>-</span>
              </div>
            ))}

            {filteredLpPositions.length > 0 && (
              <div style={{ padding: "0.5rem var(--table-cell-px)", borderBottom: "1px solid var(--rule)" }}>
                <span className="label-caps">LP (ledger)</span>
              </div>
            )}

            {filteredLpPositions.map(position => {
              const stablePoolId = position.poolId;
              const activePool = stablePoolId
                ? rawPools.find(pool => pool.poolId === stablePoolId)
                : null;
              const activePoolCid = activePool?.contractId || position.poolCid;
              const label = activePool
                ? `${activePool.tokenA.symbol}/${activePool.tokenB.symbol}`
                : resolvePoolLabel(stablePoolId || activePoolCid);
              return (
                <div
                  key={`${stablePoolId || activePoolCid || 'lp'}-${position.lpBalance}`}
                  className="rule-hover"
                  style={{ padding: "1rem var(--table-cell-px)", borderBottom: "1px solid var(--rule)", display: "grid", gridTemplateColumns: "1fr 1fr 0.8fr", alignItems: "center" }}
                >
                  <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                    <SwissCross size={7} />
                    <span style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)" }}>{label}</span>
                  </div>
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.82rem", color: "var(--text-2)" }}>{formatLpBalance(position.lpBalance)} LP</span>
                  <span style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)" }}>
                    {position.updatedAt ? new Date(position.updatedAt).toLocaleTimeString('en-US') : '—'}
                  </span>
                </div>
              );
            })}
          </>
        )}
      </div>

      <TokenSelector
        isOpen={showTokenASelector}
        onClose={() => setShowTokenASelector(false)}
        tokens={tokens}
        selectedToken={selectedTokenA}
        type="from"
        balances={balancesBySymbol}
        onSelect={(token) => {
          setSelectedTokenA(token);
          if (token.symbol === selectedTokenB?.symbol) {
            setSelectedTokenB(null);
          }
          setShowTokenASelector(false);
        }}
      />

      <TokenSelector
        isOpen={showTokenBSelector}
        onClose={() => setShowTokenBSelector(false)}
        tokens={tokenBOptions}
        selectedToken={selectedTokenB}
        type="to"
        balances={balancesBySymbol}
        onSelect={(token) => {
          setSelectedTokenB(token);
          setShowTokenBSelector(false);
        }}
      />

      {/* LP Position Selector Modal */}
      {showLpSelector && createPortal(
        <div style={{
          position: "fixed", inset: 0, zIndex: 9999,
          display: "flex", alignItems: "center", justifyContent: "center",
          background: "rgba(11,11,13,0.7)", backdropFilter: "blur(6px)",
        }} onClick={() => setShowLpSelector(false)}>
          <div
            onClick={(e) => e.stopPropagation()}
            style={{
              background: "var(--ink-2)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)",
              width: "100%", maxWidth: "var(--modal-max-w)", maxHeight: "85vh", overflowY: "auto",
            }}
          >
            {/* Header */}
            <div style={{ padding: "0.8rem 1rem", borderBottom: "1px solid var(--rule)", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
              <span className="label-caps" style={{ color: "var(--gold)" }}>Select LP Position</span>
              <div onClick={() => setShowLpSelector(false)} style={{ cursor: "pointer", padding: "0.2rem" }}>
                <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M1 1L9 9M9 1L1 9" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
              </div>
            </div>

            {/* Position List */}
            {removePositions.length === 0 ? (
              <div style={{ padding: "2rem 1rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
                No LP positions found
              </div>
            ) : (
              removePositions.map((position) => {
                const pool = rawPools.find(p => p.poolId === position.poolId);
                const pairLabel = pool ? `${pool.tokenA.symbol} / ${pool.tokenB.symbol}` : resolvePoolLabel(position.poolId);
                return (
                  <div
                    key={position.contractId}
                    className="rule-hover"
                    onClick={() => {
                      setRemoveSelectionCid(position.contractId);
                      setShowLpSelector(false);
                    }}
                    style={{
                      padding: "0.8rem 1rem", borderBottom: "1px solid var(--rule)",
                      display: "flex", justifyContent: "space-between", alignItems: "center",
                      cursor: "pointer",
                      background: position.contractId === removeSelectionCid ? "var(--gold-pale)" : "transparent",
                    }}
                  >
                    <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                      <span style={{ display: "inline-flex", alignItems: "center" }}>
                        <SwissCross size={8} />
                        <span style={{ marginLeft: -8, display: "inline-flex" }}><SwissCross size={8} /></span>
                      </span>
                      <div>
                        <div style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)", letterSpacing: "0.06em" }}>{pairLabel}</div>
                        <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-2)" }}>{formatLpBalance(String(position.amount))} LP</div>
                      </div>
                    </div>
                    <span style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-1)" }}>{formatLpBalance(String(position.amount))}</span>
                  </div>
                );
              })
            )}
          </div>
        </div>,
        document.body
      )}
    </div>
  );
};

type PreparedTransfer = Record<string, any>;

type InstrumentRef = {
  instrumentAdmin: string;
  instrumentId: string;
};

async function prepareLoopTransfer(
  provider: any,
  params: {
    recipient: string;
    amount: string;
    instrument: InstrumentRef;
    memo?: string;
    requestedAt?: string;
    executeBefore?: string;
  }
): Promise<PreparedTransfer> {
  if (!provider) {
    throw new Error('Loop provider is not connected');
  }
  const authToken = provider.auth_token ?? provider.authToken;
  const connection = provider.connection ?? provider?.sdk?.connection;
  const prepareTransfer = connection?.prepareTransfer;
  if (!authToken || typeof prepareTransfer !== 'function') {
    throw new Error('Loop provider does not expose transfer preparation');
  }
  const payload = {
    recipient: params.recipient,
    amount: params.amount,
    instrument: {
      instrument_admin: params.instrument.instrumentAdmin,
      instrument_id: params.instrument.instrumentId,
    },
    requested_at: params.requestedAt ?? new Date().toISOString(),
    execute_before: params.executeBefore,
    memo: params.memo,
  };
  return prepareTransfer.call(connection, authToken, payload);
}

const LOOP_RATE_LIMIT_RETRY_DELAYS_MS = [4000, 8000, 12000, 18000, 25000];
const LOOP_MIN_GAP_MS = 6000;

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function isLoopRateLimitError(err: any): boolean {
  const status =
    err?.response?.status ??
    err?.status ??
    err?.error?.response?.status ??
    err?.details?.response?.status;
  if (status === 429 || status === 503) {
    return true;
  }
  const message = String(
    err?.message ??
      err?.error?.message ??
      err?.details?.message ??
      err?.details?.toString?.() ??
      ''
  ).toLowerCase();
  return message.includes('429') || message.includes('rate limit') || message.includes('503') || message.includes('service unavailable') || message.includes('failed to prepare transfer');
}

function isUserCancelled(err: any): boolean {
  if (!err) {
    return false;
  }
  const code = String(err.code ?? err.name ?? '').toLowerCase();
  const message = String(err.message ?? '').toLowerCase();
  return (
    code.includes('cancel') ||
    code.includes('reject') ||
    message.includes('cancel') ||
    message.includes('rejected') ||
    message.includes('user rejected')
  );
}

function extractRetryAfterMs(err: any): number | null {
  const headers = err?.response?.headers ?? err?.headers ?? err?.error?.response?.headers ?? err?.details?.response?.headers;
  if (!headers) return null;
  const retryAfter = headers['retry-after'] ?? headers['Retry-After'] ?? headers['RETRY-AFTER'];
  if (!retryAfter) return null;
  if (typeof retryAfter === 'number') {
    return Math.max(0, retryAfter * 1000);
  }
  const retryAfterStr = String(retryAfter).trim();
  const asNumber = Number(retryAfterStr);
  if (Number.isFinite(asNumber)) {
    return Math.max(0, asNumber * 1000);
  }
  const asDate = Date.parse(retryAfterStr);
  if (!Number.isNaN(asDate)) {
    return Math.max(0, asDate - Date.now());
  }
  return null;
}

function resolveRateLimitDelayMs(err: any, fallbackMs: number): number {
  const retryAfterMs = extractRetryAfterMs(err);
  return retryAfterMs && Number.isFinite(retryAfterMs) && retryAfterMs > 0 ? retryAfterMs : fallbackMs;
}

async function withLoopRateLimitRetry<T>(
  label: string,
  task: () => Promise<T>,
  options?: {
    onRetry?: (delayMs: number) => void;
    beforeAttempt?: () => Promise<void>;
  }
): Promise<T> {
  for (let attempt = 0; attempt <= LOOP_RATE_LIMIT_RETRY_DELAYS_MS.length; attempt += 1) {
    try {
      if (options?.beforeAttempt) {
        await options.beforeAttempt();
      }
      return await task();
    } catch (err: any) {
      if (!isLoopRateLimitError(err) || attempt >= LOOP_RATE_LIMIT_RETRY_DELAYS_MS.length) {
        throw err;
      }
      const delayMs = resolveRateLimitDelayMs(err, LOOP_RATE_LIMIT_RETRY_DELAYS_MS[attempt]);
      console.warn(`[loop] ${label} rate limited (429). Retrying in ${delayMs}ms.`);
      options?.onRetry?.(delayMs);
      await sleep(delayMs);
    }
  }
  throw new Error(`[loop] ${label} failed after rate limit retries`);
}

async function submitTxWithRateLimitRetry(
  input: Parameters<typeof submitTx>[0],
  label: string,
  options?: {
    onRetry?: (delayMs: number) => void;
    beforeAttempt?: () => Promise<void>;
  }
): Promise<ReturnType<typeof submitTx>> {
  for (let attempt = 0; attempt <= LOOP_RATE_LIMIT_RETRY_DELAYS_MS.length; attempt += 1) {
    if (options?.beforeAttempt) {
      await options.beforeAttempt();
    }
    const result = await submitTx(input);
    if (result.ok || !isLoopRateLimitError(result.error)) {
      return result;
    }
    if (attempt >= LOOP_RATE_LIMIT_RETRY_DELAYS_MS.length) {
      return result;
    }
    const delayMs = resolveRateLimitDelayMs(result.error?.details ?? result.error, LOOP_RATE_LIMIT_RETRY_DELAYS_MS[attempt]);
    console.warn(`[loop] ${label} rate limited (429). Retrying in ${delayMs}ms.`);
    options?.onRetry?.(delayMs);
    await sleep(delayMs);
  }
  return submitTx(input);
}

function extractPreparedCommands(prepared: PreparedTransfer): any[] {
  const commands =
    prepared?.commands ??
    prepared?.payload?.commands ??
    prepared?.commandPayload?.commands ??
    prepared?.command_payload?.commands ??
    [];
  return Array.isArray(commands) ? commands : [];
}

function extractPreparedDisclosedContracts(prepared: PreparedTransfer): any[] | undefined {
  const disclosed =
    prepared?.disclosedContracts ??
    prepared?.disclosed_contracts ??
    prepared?.extraArgs?.disclosedContracts ??
    prepared?.extra_args?.disclosed_contracts ??
    prepared?.payload?.disclosedContracts ??
    prepared?.payload?.disclosed_contracts;
  return Array.isArray(disclosed) ? disclosed : undefined;
}

function extractPreparedPackagePreference(prepared: PreparedTransfer): string[] | undefined {
  const pref =
    prepared?.packageIdSelectionPreference ??
    prepared?.package_id_selection_preference ??
    prepared?.packageIdSelection ??
    prepared?.package_id_selection ??
    prepared?.payload?.packageIdSelectionPreference ??
    prepared?.payload?.package_id_selection_preference;
  return Array.isArray(pref) ? pref : undefined;
}

function extractPreparedSynchronizerId(prepared: PreparedTransfer): string | undefined {
  return prepared?.synchronizerId ?? prepared?.synchronizer_id ?? undefined;
}

function extractPreparedActAs(prepared: PreparedTransfer, fallback: string): string | string[] {
  const actAs = prepared?.actAs ?? prepared?.act_as ?? prepared?.actAsParties ?? prepared?.act_as_parties;
  if (Array.isArray(actAs) && actAs.length > 0) {
    return actAs;
  }
  if (typeof actAs === 'string' && actAs.length > 0) {
    return actAs;
  }
  return fallback;
}

function extractPreparedReadAs(prepared: PreparedTransfer, fallback: string): string | string[] | undefined {
  const readAs = prepared?.readAs ?? prepared?.read_as ?? prepared?.readAsParties ?? prepared?.read_as_parties;
  if (Array.isArray(readAs) && readAs.length > 0) {
    return readAs;
  }
  if (typeof readAs === 'string' && readAs.length > 0) {
    return readAs;
  }
  return fallback ? [fallback] : undefined;
}

export default LiquidityInterface;