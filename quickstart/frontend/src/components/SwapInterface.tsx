import React, { useState, useEffect, useMemo, useCallback, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAppStore } from '../stores';
import { useContractStore } from '../stores/useContractStore';
import { backendApi } from '../services/backendApi';
import { TokenInfo, SwapQuote, PoolInfo, LpPositionInfo } from '../types/canton';
import TokenSelector from './TokenSelector';
import SlippageSettings from './SlippageSettings';
import toast from 'react-hot-toast';
import { SwissCross } from './HelvetAtoms';
import { useWalletAuth, walletManager } from '../wallet';
import { useLoopBalances, useUtxoBalances } from '../hooks';
import { submitTx } from '../loop/submitTx';
import { getLoopProvider } from '../loop/loopProvider';
import { calculateSwapQuoteFromPool } from '../utils/poolMath';

const AMULET_ADMIN =
  process.env.REACT_APP_AMULET_ADMIN ||
  'DSO::1220b1431ef217342db44d516bb9befde802be7d8899637d290895fa58880f19accc';
const AMULET_ID = 'Amulet';
const CBTC_ADMIN =
  process.env.REACT_APP_CBTC_ADMIN ||
  'cbtc-network::12205af3b949a04776fc48cdcc05a060f6bda2e470632935f375d1049a8546a3b262';
const CBTC_ID = 'CBTC';
const OPERATOR_PARTY =
  process.env.REACT_APP_OPERATOR_PARTY ||
  process.env.REACT_APP_OPERATOR_PARTY_ID ||
  'helvetswap-validator-1::12206d3b89fef3da6b1e40abcc7d2cbe174abae6062be451950095ca32397e84bf8b';
const SWAP_DEADLINE_MINUTES_RAW = Number(process.env.REACT_APP_SWAP_DEADLINE_MINUTES ?? 30);
const SWAP_DEADLINE_MINUTES =
  Number.isFinite(SWAP_DEADLINE_MINUTES_RAW) && SWAP_DEADLINE_MINUTES_RAW > 0
    ? SWAP_DEADLINE_MINUTES_RAW
    : 30;

const SwapInterface: React.FC = () => {
  const navigate = useNavigate();
  const { selectedTokens, setSelectedTokens, swapTokens: swapSelectedTokens, slippage } = useAppStore();
  const { partyId, walletType } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;
  const { balances: loopBalances, reload: reloadLoopBalances } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
  });
  const { balances: utxoBalances, reload: reloadUtxoBalances } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });
  const reloadBalances = useCallback(async () => {
    if (walletType === 'loop') {
      await reloadLoopBalances();
      return;
    }
    await reloadUtxoBalances();
  }, [walletType, reloadLoopBalances, reloadUtxoBalances]);
  const [inputAmount, setInputAmount] = useState('');
  const [outputAmount, setOutputAmount] = useState('');
  const [tokens, setTokens] = useState<TokenInfo[]>([]);
  const [quote, setQuote] = useState<SwapQuote | null>(null);
  const [loading, setLoading] = useState(false);
  const [calculating, setCalculating] = useState(false);
  const [showFromSelector, setShowFromSelector] = useState(false);
  const [showToSelector, setShowToSelector] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [swapResult, setSwapResult] = useState<any>(null);
  const [swapStatus, setSwapStatus] = useState<string | null>(null);
  const [swapSuccess, setSwapSuccess] = useState<SwapSuccessSummary | null>(null);
  const [devnetConsumeAvailable, setDevnetConsumeAvailable] = useState(false);
  const [pools, setPools] = useState<PoolInfo[]>([]);
  const [volume24h, setVolume24h] = useState<number>(0);
  const [lpPositions, setLpPositions] = useState<LpPositionInfo[]>([]);
  const lastConnectedParty = useRef<string | null>(null);
  const loopRequestStateRef = useRef({ lastCallAt: 0 });

  const waitForLoopCooldown = useCallback(
    async (label: string, minGapMs: number = LOOP_MIN_GAP_MS) => {
      const now = Date.now();
      const elapsed = now - loopRequestStateRef.current.lastCallAt;
      if (elapsed < minGapMs) {
        const delayMs = minGapMs - elapsed;
        setSwapStatus('Executing swap...');
        await sleep(delayMs);
      }
      loopRequestStateRef.current.lastCallAt = Date.now();
    },
    []
  );

  const resolvedPoolId = useMemo(() => {
    if (!selectedTokens.from || !selectedTokens.to || pools.length === 0) return null;
    const fromSymbol = selectedTokens.from.symbol.toUpperCase();
    const toSymbol = selectedTokens.to.symbol.toUpperCase();
    const match = pools.find(pool => {
      const symbolA = pool.tokenA.symbol.toUpperCase();
      const symbolB = pool.tokenB.symbol.toUpperCase();
      return (
        (symbolA === fromSymbol && symbolB === toSymbol) ||
        (symbolA === toSymbol && symbolB === fromSymbol)
      );
    });
    return match?.poolId ?? null;
  }, [pools, selectedTokens.from, selectedTokens.to]);

  const activePool = useMemo(() => {
    if (!resolvedPoolId) {
      return null;
    }
    return pools.find(pool => pool.contractId === resolvedPoolId || pool.poolId === resolvedPoolId) ?? null;
  }, [pools, resolvedPoolId]);

  const formatCurrency = (value: number) => {
    if (!isFinite(value) || value <= 0) return '$0';
    if (value >= 1_000_000) return `$${(value / 1_000_000).toFixed(1)}M`;
    if (value >= 1_000) return `$${(value / 1_000).toFixed(1)}K`;
    return `$${value.toFixed(0)}`;
  };

  const formatContractId = (value?: string | null) => {
    if (!value) return 'none (completed)';
    const trimmed = value.trim();
    if (trimmed.length <= 16) return trimmed;
    return `${trimmed.slice(0, 8)}…${trimmed.slice(-4)}`;
  };

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

  const getNumericBalance = useCallback((symbol?: string) => {
    const entry = getBalanceEntry(symbol);
    if (!entry) {
      return 0;
    }
    const numeric = Number(entry.amount);
    return Number.isFinite(numeric) ? numeric : 0;
  }, [getBalanceEntry]);


  const parsedInputAmount = parseFloat(inputAmount || '0');
  const normalizedInputAmount = Number.isFinite(parsedInputAmount) ? parsedInputAmount : 0;
  const insufficientBalance = Boolean(
    partyId && selectedTokens.from && normalizedInputAmount > getNumericBalance(selectedTokens.from.symbol)
  );
  const disableSwap = loading || !partyId || !selectedTokens.from || !selectedTokens.to || !inputAmount || !quote || insufficientBalance;
  const consumePayload = swapResult?.consume?.result;
  const payoutStatus = consumePayload?.payoutStatus;
  const payoutCid = consumePayload?.payoutCid;
  const payoutStatusLabel = payoutStatus ? String(payoutStatus).toUpperCase() : 'CREATED';
  const showPayoutCreatedHeader = payoutStatusLabel === 'CREATED';

  useEffect(() => {
    const previous = lastConnectedParty.current;
    if (partyId && partyId !== previous) {
      reloadBalances();
    }
    if (!partyId) {
      lastConnectedParty.current = null;
      return;
    }
    lastConnectedParty.current = partyId;
  }, [partyId, reloadBalances]);

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

  useEffect(() => {
    if (typeof window === 'undefined') {
      return;
    }
    const handleWalletConnected = () => {
      reloadBalances();
    };
    window.addEventListener('clearportx:wallet:connected', handleWalletConnected as EventListener);
    return () => {
      window.removeEventListener('clearportx:wallet:connected', handleWalletConnected as EventListener);
    };
  }, [reloadBalances]);

  // Charger les tokens depuis les pools actifs + balances utilisateur
  useEffect(() => {
    const loadTokens = async () => {
      try {
        const pools = await backendApi.getPools();
        setPools(pools);

        const uniqueTokensMap = new Map<string, TokenInfo>();
        pools.forEach(pool => {
          if (!uniqueTokensMap.has(pool.tokenA.symbol)) {
            uniqueTokensMap.set(pool.tokenA.symbol, { ...pool.tokenA, balance: 0 });
          }
          if (!uniqueTokensMap.has(pool.tokenB.symbol)) {
            uniqueTokensMap.set(pool.tokenB.symbol, { ...pool.tokenB, balance: 0 });
          }
        });
        const tokensList = Array.from(uniqueTokensMap.values()).map(token => ({ ...token, balance: 0 }));
        setTokens(tokensList);
        useContractStore.getState().setTokens(tokensList);
      } catch (error) {
        console.error('Error loading tokens:', error);
        toast.error('Failed to load tokens from backend');
      }
    };

    loadTokens();
  }, []);

  useEffect(() => {
    let cancelled = false;
    const probeDevnetSwap = async () => {
      const res = await backendApi.inspectDevnetSwap('ping');
      if (cancelled) return;
      setDevnetConsumeAvailable(isDevnetSwapApiResponse(res));
    };
    probeDevnetSwap();
    return () => {
      cancelled = true;
    };
  }, []);

  // Compute 24h volume for selected pair from current pools
  useEffect(() => {
    if (!selectedTokens.from || !selectedTokens.to || pools.length === 0) {
      setVolume24h(0);
      return;
    }
    const a = selectedTokens.from.symbol.toUpperCase();
    const b = selectedTokens.to.symbol.toUpperCase();
    const match = pools.find(p => {
      const pa = p.tokenA.symbol.toUpperCase();
      const pb = p.tokenB.symbol.toUpperCase();
      return (pa === a && pb === b) || (pa === b && pb === a);
    });
    setVolume24h(match?.volume24h || 0);
  }, [selectedTokens.from, selectedTokens.to, pools]);

  // Calculer le quote quand le montant change
  useEffect(() => {
    if (!selectedTokens.from || !selectedTokens.to || !inputAmount || !activePool) {
      setOutputAmount('');
      setQuote(null);
      return;
    }

    const timeout = setTimeout(() => {
      setCalculating(true);
      try {
        const amount = parseFloat(inputAmount);
        if (!Number.isFinite(amount) || amount <= 0) {
          setOutputAmount('');
          setQuote(null);
          return;
        }

        const estimatedQuote = calculateSwapQuoteFromPool(
          activePool,
          selectedTokens.from!.symbol,
          selectedTokens.to!.symbol,
          amount
        );

        if (!estimatedQuote) {
          setOutputAmount('');
          setQuote(null);
          return;
        }

        setQuote(estimatedQuote);
        setOutputAmount(estimatedQuote.outputAmount.toFixed(6));
      } finally {
        setCalculating(false);
      }
    }, 250);

    return () => clearTimeout(timeout);
  }, [inputAmount, selectedTokens.from, selectedTokens.to, activePool]);

  const handleSwap = async () => {
    if (!selectedTokens.from || !selectedTokens.to || !inputAmount || !quote) {
      toast.error('Please fill in all fields');
      return;
    }

    if (!partyId) {
      toast.error('Connect a wallet to swap tokens');
      return;
    }

    if (walletType && walletType !== 'loop') {
      toast.error('Loop wallet required to submit swaps');
      return;
    }

    const amount = parseFloat(inputAmount);
    if (isNaN(amount) || amount <= 0) {
      toast.error('Please enter a valid amount');
      return;
    }

    const availableBalance = getNumericBalance(selectedTokens.from.symbol);
    if (amount > availableBalance) {
      toast.error('Insufficient balance for this swap');
      return;
    }

    if (!resolvedPoolId) {
      toast.error('No liquidity pool available for this pair');
      return;
    }

    try {
      setLoading(true);
      setSwapResult(null);
      setSwapStatus(null);
      setSwapSuccess(null);

      const instrument = resolveInstrumentForSymbol(selectedTokens.from.symbol);
      if (!instrument) {
        toast.error('Unsupported token for swap');
        return;
      }

      const minOutput = quote.outputAmount * (1 - slippage / 100);
      const minOutputStr = minOutput.toFixed(10);

      let freshPools: PoolInfo[] | null = null;
      try {
        freshPools = await backendApi.getPoolsAuthenticated();
        if (freshPools.length > 0) {
          setPools(freshPools);
        }
      } catch {
        // non-fatal: we still have activePool
      }

      const latestPool =
        (freshPools && freshPools.find(pool => pool.poolId === resolvedPoolId)) ||
        activePool;
      if (!latestPool) {
        toast.error('No liquidity pool available for this pair');
        return;
      }

      let poolCid: string | null = latestPool.contractId || null;
      if (!poolCid && latestPool.poolId) {
        try {
          poolCid = await backendApi.resolvePoolCid(latestPool.poolId);
        } catch {
          // fall through to error
        }
      }
      if (!poolCid) {
        toast.error('Unable to resolve latest pool contract for this pair');
        return;
      }

      const direction = resolveSwapDirection(latestPool, selectedTokens.from.symbol, selectedTokens.to.symbol);
      if (!direction) {
        toast.error('Token selection does not match active pool');
        return;
      }

      const deadline = new Date(Date.now() + SWAP_DEADLINE_MINUTES * 60 * 1000).toISOString();
      const requestId = `swap-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
      const memoPayload = {
        v: 1,
        requestId,
        poolCid,
        direction,
        minOut: minOutputStr,
        receiverParty: partyId,
        deadline,
      };
      const memo = JSON.stringify(memoPayload);

      const connector = walletManager.getOrCreateLoopConnector();
      const status = await connector.ensureConnected(`swap-${Date.now()}`);
      if (!status.connected || !getLoopProvider()) {
        const message = status.error || 'Loop not connected';
        setSwapResult({ ok: false, error: { code: 'CONNECT', message } });
        toast.error(message);
        return;
      }

      setSwapStatus('Preparing inbound transfer');
      const prepared = await withLoopRateLimitRetry(
        'Swap preparation',
        () =>
          prepareLoopTransfer(getLoopProvider(), {
            recipient: OPERATOR_PARTY,
            amount: amount.toFixed(10),
            instrument,
            memo,
            requestedAt: new Date().toISOString(),
            executeBefore: deadline,
          }),
        {
          onRetry: (delayMs) =>
            setSwapStatus(`Swap preparation rate limited. Retrying in ${Math.ceil(delayMs / 1000)}s`),
          beforeAttempt: () => waitForLoopCooldown('prepare swap'),
        }
      );

      const commands = extractPreparedCommands(prepared);
      const disclosedContracts = extractPreparedDisclosedContracts(prepared);
      const packagePreference = extractPreparedPackagePreference(prepared);
      const synchronizerId = extractPreparedSynchronizerId(prepared);
      const exerciseContractId = extractExerciseContractId(commands);
      const debugInfo = {
        disclosedContractsCount: disclosedContracts?.length ?? 0,
        contractId: exerciseContractId,
        synchronizerId,
        requestId,
      };
      if (commands.length === 0) {
        setSwapResult({
          ok: false,
          error: { code: 'PREPARE', message: 'Transfer preparation returned no commands', details: prepared },
        });
        toast.error('Transfer preparation failed');
        return;
      }

      const combinedCommands = commands;

      setSwapStatus('Submitting inbound transfer');
      const transferResult = await submitTxWithRateLimitRetry(
        {
          commands: combinedCommands,
          actAs: extractPreparedActAs(prepared, partyId),
          readAs: extractPreparedReadAs(prepared, partyId),
          deduplicationKey: requestId,
          memo,
          mode: 'WAIT',
          disclosedContracts,
          packageIdSelectionPreference: packagePreference,
          synchronizerId,
        },
        'Swap submission',
        {
          onRetry: (delayMs) =>
            setSwapStatus(`Swap submission rate limited. Retrying in ${Math.ceil(delayMs / 1000)}s`),
          beforeAttempt: () => waitForLoopCooldown('submit swap'),
        }
      );
      let consumeResult: any = null;
      if (transferResult.ok && transferResult.value.txStatus === 'SUCCEEDED') {
        const shouldConsume = devnetConsumeAvailable;
        if (shouldConsume) {
          setSwapStatus('Executing swap...');
          try {
            consumeResult = await backendApi.consumeDevnetSwap({ requestId });
          } catch {
            consumeResult = { ok: false };
          }

          // If consume failed, the poller may have already processed it — poll inspect
          if (!consumeResult?.ok) {
            setSwapStatus('Executing swap...');
            for (let i = 0; i < 20; i++) {
              await new Promise(r => setTimeout(r, 3000));
              setSwapStatus('Executing swap...');
              try {
                const inspectResult = await backendApi.inspectDevnetSwap(requestId);
                const inspectData = inspectResult?.data || inspectResult;
                if (inspectData?.swap?.status === 'completed' || inspectData?.consume?.ok) {
                  consumeResult = { ok: true, result: inspectData?.consume || inspectData };
                  break;
                }
              } catch {
                // Keep polling
              }
            }
          }

          if (consumeResult?.ok) {
            const payoutStatus = consumeResult?.result?.payoutStatus;
            const payoutCid = consumeResult?.result?.payoutCid;
            if (payoutStatus === 'COMPLETED' || !payoutCid) {
              setSwapStatus('Payout completed automatically. No Loop accept required.');
            } else {
              setSwapStatus('Payout created. Accept in Loop wallet.');
            }

            // Build the success recap summary. Prefer actual output amount from
            // the backend when present (inspect/consume response), otherwise
            // fall back to the pre-trade quote's outputAmount.
            const observedOut =
              consumeResult?.result?.amountOut ??
              consumeResult?.result?.payoutAmount ??
              quote.outputAmount;
            const inAmt = Number(amount);
            const outAmt = Number(observedOut);
            const rateStr =
              Number.isFinite(inAmt) && inAmt > 0 && Number.isFinite(outAmt) && outAmt > 0
                ? `1 ${selectedTokens.to!.symbol} ≈ ${(inAmt / outAmt).toLocaleString(undefined, { maximumFractionDigits: 6 })} ${selectedTokens.from!.symbol}`
                : '—';
            setSwapSuccess({
              requestId,
              amountIn: inAmt.toString(),
              amountOut: outAmt.toString(),
              inSymbol: selectedTokens.from!.symbol,
              outSymbol: selectedTokens.to!.symbol,
              rate: rateStr,
              feeAmount: Number.isFinite(quote.fee) ? quote.fee.toString() : '0',
              priceImpact: Number.isFinite(quote.priceImpact) ? quote.priceImpact : 0,
              timestamp: new Date().toISOString(),
            });

            if (typeof window !== 'undefined') {
              window.dispatchEvent(new CustomEvent('clearportx:transactions:refresh', {
                detail: { source: 'swap', requestId },
              }));
            }
          }
        } else {
          consumeResult = {
            ok: false,
            error: { code: 'UNAVAILABLE', message: 'Swap processing is temporarily unavailable' },
          };
        }
      }

      const resultWithDebug = {
        transfer: transferResult,
        consume: consumeResult,
        debug: debugInfo,
      };
      setSwapResult(resultWithDebug);

      if (consumeResult?.ok) {
        // Success recap card handles the visual feedback — no toast needed
        setInputAmount('');
        setOutputAmount('');
        setQuote(null);
      } else if (transferResult.ok && transferResult.value.txStatus === 'SUCCEEDED') {
        // Swap submitted and being processed by the backend poller
        setInputAmount('');
        setOutputAmount('');
        setQuote(null);
      } else if (transferResult.ok) {
        toast.error('Swap transfer failed');
      } else {
        toast.error(transferResult.error.message);
      }
    } catch (error: any) {
      console.error('Error executing swap:', error);
      const message: string =
        error?.message ??
        error?.response?.data?.message ??
        error?.response?.data?.error ??
        'Unknown error';
      setSwapResult({ ok: false, error: { code: 'ERROR', message, details: error } });
      toast.error(`Swap failed: ${message}`);
    } finally {
      setLoading(false);
      await reloadBalances();
    }
  };

  const handleTokenSwap = () => {
    const prevInput = inputAmount;
    const prevOutput = outputAmount;
    swapSelectedTokens();
    setInputAmount(prevOutput);
    setOutputAmount(prevInput);
    setQuote(null);
  };

  return (
    <div style={{ maxWidth: "var(--swap-max-w)", margin: "0 auto" }}>
      <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)" }}>
        {/* Header */}
        <div style={{ padding: "1.2rem var(--card-px)", borderBottom: "1px solid var(--rule)", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontFamily: "var(--display)", fontSize: "1.45rem", fontStyle: "italic", color: "var(--text-1)" }}>Instant Exchange</span>
          <button
            onClick={() => setShowSettings(!showSettings)}
            className="btn-ghost"
            style={{ height: 28, fontSize: "0.64rem", padding: "0 0.6rem", gap: "0.3rem" }}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="var(--text-3)" strokeWidth="1.5"><path d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
            Settings
          </button>
        </div>

        {/* Settings Panel */}
        {showSettings && (
          <div style={{ padding: "0.8rem var(--card-px)", borderBottom: "1px solid var(--rule)" }}>
            <SlippageSettings onClose={() => setShowSettings(false)} />
          </div>
        )}

        {/* Token A input — You Pay */}
        <div style={{ padding: "1.2rem var(--card-px) 0.6rem" }}>
          <div className="label-caps" style={{ marginBottom: "0.6rem" }}>You Pay</div>
          <div className="token-input-wrap" style={{ background: "var(--ink-4)", border: "1px solid var(--rule)", padding: "0.8rem 0.9rem", display: "flex", alignItems: "center", gap: "0.6rem" }}>
            <input
              type="text"
              value={inputAmount}
              onChange={(e) => setInputAmount(e.target.value)}
              placeholder="0.0000"
              style={{ flex: 1, background: "transparent", border: "none", outline: "none", fontFamily: "var(--mono)", fontSize: "var(--input-font-size)", fontWeight: 300, color: "var(--text-1)", letterSpacing: "-0.02em", width: "100%" }}
            />
            <button className="token-select-btn" onClick={() => setShowFromSelector(true)}>
              <SwissCross size={7} />
              {selectedTokens.from?.symbol || 'Select'}
              <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
            </button>
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", marginTop: "0.4rem", fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)" }}>
            <span>Balance: {formatBalanceDisplay(selectedTokens.from?.symbol)} {selectedTokens.from?.symbol || ''}</span>
            <span
              style={{ color: "var(--gold-dim)", cursor: "pointer" }}
              onClick={() => {
                const bal = getNumericBalance(selectedTokens.from?.symbol);
                if (bal > 0) setInputAmount(String(bal));
              }}
            >MAX</span>
          </div>
        </div>

        {/* Flip button */}
        <div style={{ display: "flex", justifyContent: "center", padding: "0.2rem 0", position: "relative" }}>
          <div style={{ position: "absolute", left: "var(--card-px)", right: "var(--card-px)", top: "50%", height: 1, background: "var(--rule)", transform: "translateY(-50%)" }} />
          <button className="spin-btn" onClick={handleTokenSwap} style={{ width: 32, height: 32, background: "var(--ink-4)", border: "1px solid var(--rule-gold)", cursor: "pointer", display: "flex", alignItems: "center", justifyContent: "center", position: "relative", zIndex: 1 }}>
            <svg width="12" height="12" viewBox="0 0 12 12" fill="none"><path d="M6 1V11M6 1L3 4M6 1L9 4M6 11L3 8M6 11L9 8" stroke="var(--gold)" strokeWidth="1.2" /></svg>
          </button>
        </div>

        {/* Token B output — You Receive */}
        <div style={{ padding: "0.6rem var(--card-px) 1.2rem" }}>
          <div className="label-caps" style={{ marginBottom: "0.6rem" }}>You Receive</div>
          <div className="token-input-wrap" style={{ background: "var(--ink-4)", border: "1px solid var(--rule)", padding: "0.8rem 0.9rem", display: "flex", alignItems: "center", gap: "0.6rem" }}>
            <div style={{ flex: 1, fontFamily: "var(--mono)", fontSize: "var(--input-font-size)", fontWeight: 300, color: "var(--text-2)", letterSpacing: "-0.02em" }}>
              {calculating ? '...' : outputAmount || '0.0000'}
            </div>
            <button className="token-select-btn" onClick={() => setShowToSelector(true)}>
              <SwissCross size={7} />
              {selectedTokens.to?.symbol || 'Select'}
              <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
            </button>
          </div>
          <div style={{ display: "flex", justifyContent: "space-between", marginTop: "0.4rem", fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)" }}>
            <span>Balance: {formatBalanceDisplay(selectedTokens.to?.symbol)} {selectedTokens.to?.symbol || ''}</span>
          </div>
        </div>

        {/* Quote Details */}
        {quote && (
          <div style={{ padding: "0 var(--card-px) 1rem" }}>
            <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)" }}>
              <div style={{ padding: "0.7rem 1rem" }}>
                <span style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)", letterSpacing: "0.04em" }}>
                  1 {selectedTokens.from?.symbol} = {(quote.outputAmount / quote.inputAmount).toFixed(6)} {selectedTokens.to?.symbol}
                </span>
              </div>
              <div style={{ padding: "0 1rem 0.8rem" }}>
                {[
                  ["Rate", `1 ${selectedTokens.from?.symbol} = ${(quote.outputAmount / quote.inputAmount).toFixed(6)} ${selectedTokens.to?.symbol}`],
                  ["Fee", `${quote.fee.toFixed(6)} ${selectedTokens.from?.symbol}`],
                  ["Price Impact", `${quote.priceImpact.toFixed(3)}%`],
                  ["Slippage Tolerance", `${slippage}%`],
                  ["Minimum Received", `${(quote.outputAmount * (1 - slippage / 100)).toFixed(6)} ${selectedTokens.to?.symbol}`],
                ].map(([k, v]) => (
                  <div key={k} style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
                    <span style={{ color: "var(--text-3)" }}>{k}</span>
                    <span style={{ color: "var(--text-2)" }}>{v}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>
        )}

        {/* Execute button */}
        <div style={{ padding: "0 var(--card-px) 1.4rem" }}>
          <button
            className="btn-primary"
            disabled={disableSwap}
            onClick={handleSwap}
            style={{ width: "100%", justifyContent: "center", height: 44 }}
          >
            {loading ? (
              <span>Submitting...</span>
            ) : !partyId ? (
              <>
                <SwissCross size={9} color="var(--ink)" />
                Connect Wallet
              </>
            ) : insufficientBalance ? (
              'Insufficient Balance'
            ) : (
              <>
                <SwissCross size={9} color="var(--ink)" />
                {inputAmount ? 'Execute Swap' : 'Enter Amount'}
              </>
            )}
          </button>
        </div>

        {/* Swap success recap card (shown after completion, replaces status text) */}
        {swapSuccess && (
          <div style={{ padding: "0 var(--card-px) 1rem" }}>
            <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.9rem 1rem" }}>
              <div style={{ fontFamily: "var(--display)", fontSize: "0.95rem", fontWeight: 400, color: "var(--text-1)", marginBottom: "0.6rem" }}>
                Swap complete
              </div>
              <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-2)", display: "grid", gridTemplateColumns: "auto 1fr", columnGap: "0.6rem", rowGap: "0.25rem" }}>
                <span style={{ color: "var(--text-3)" }}>You sent</span>
                <span style={{ color: "var(--text-1)" }}>{swapSuccess.amountIn} {swapSuccess.inSymbol}</span>
                <span style={{ color: "var(--text-3)" }}>You received</span>
                <span style={{ color: "var(--text-1)" }}>{swapSuccess.amountOut} {swapSuccess.outSymbol}</span>
                <span style={{ color: "var(--text-3)" }}>Rate</span>
                <span>{swapSuccess.rate}</span>
                <span style={{ color: "var(--text-3)" }}>Fee</span>
                <span>{swapSuccess.feeAmount} {swapSuccess.inSymbol}</span>
                <span style={{ color: "var(--text-3)" }}>Price impact</span>
                <span>{swapSuccess.priceImpact.toFixed(4)}%</span>
                <span style={{ color: "var(--text-3)" }}>Request ID</span>
                <span style={{ fontSize: "0.6rem" }}>{swapSuccess.requestId}</span>
              </div>
              <div style={{ display: "flex", gap: "0.6rem", marginTop: "0.8rem" }}>
                <button
                  className="btn-ghost"
                  style={{ fontFamily: "var(--mono)", fontSize: "0.68rem" }}
                  onClick={() => {
                    setSwapSuccess(null);
                    setSwapStatus(null);
                    setSwapResult(null);
                    setInputAmount('');
                    setOutputAmount('');
                    setQuote(null);
                  }}
                >
                  New swap
                </button>
                <button
                  className="btn-ghost"
                  style={{ fontFamily: "var(--mono)", fontSize: "0.68rem" }}
                  onClick={() => navigate('/history')}
                >
                  View in transaction history
                </button>
              </div>
            </div>
          </div>
        )}

        {/* Swap in-flight status (hidden once success card renders) */}
        {!swapSuccess && (swapStatus || swapResult) && (
          <div style={{ padding: "0 var(--card-px) 1rem" }}>
            <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", padding: "0.8rem 1rem" }}>
              {swapStatus && (
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-1)", marginBottom: "0.3rem" }}>{swapStatus}</div>
              )}
              {consumePayload && (
                <>
                  {showPayoutCreatedHeader && (
                    <div style={{ marginTop: "0.3rem" }}>
                      <div style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-1)" }}>Payout created.</div>
                    </div>
                  )}
                  <div style={{ marginTop: "0.4rem", fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)" }}>
                    <div>Status: {payoutStatusLabel}</div>
                    <div>Amount out: {consumePayload?.amountOut ?? '-'}</div>
                    <div>Execute before: {consumePayload?.payoutExecuteBefore ?? '-'}</div>
                    <div>Payout CID: {formatContractId(payoutCid)}</div>
                  </div>
                </>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Token Selectors */}
      <TokenSelector
        isOpen={showFromSelector}
        onClose={() => setShowFromSelector(false)}
        balances={balancesBySymbol}
        onSelect={(token) => {
          setSelectedTokens({ from: token });
          setShowFromSelector(false);
        }}
        tokens={tokens}
        selectedToken={selectedTokens.from}
        type="from"
      />

      <TokenSelector
        isOpen={showToSelector}
        onClose={() => setShowToSelector(false)}
        balances={balancesBySymbol}
        onSelect={(token) => {
          setSelectedTokens({ to: token });
          setShowToSelector(false);
        }}
        tokens={tokens}
        selectedToken={selectedTokens.to}
        type="to"
      />
    </div>
  );
};

type SwapSuccessSummary = {
  requestId: string;
  amountIn: string;
  amountOut: string;
  inSymbol: string;
  outSymbol: string;
  rate: string;
  feeAmount: string;
  priceImpact: number;
  timestamp: string;
};

type PreparedTransfer = Record<string, any>;

type InstrumentRef = {
  instrumentAdmin: string;
  instrumentId: string;
};

type PoolInstruments = {
  instrumentA: InstrumentRef;
  instrumentB: InstrumentRef;
};

function instrumentIdToSymbol(instrumentId?: string): string {
  const raw = instrumentId || '';
  const upper = raw.toUpperCase();
  if (upper === 'AMULET') return 'CC';
  return upper;
}

function resolveInstrumentForSymbol(symbol?: string): InstrumentRef | null {
  if (!symbol) return null;
  const upper = symbol.toUpperCase();
  if (upper === 'CC' || upper === 'AMULET') {
    return { instrumentAdmin: AMULET_ADMIN, instrumentId: AMULET_ID };
  }
  if (upper === 'CBTC') {
    return { instrumentAdmin: CBTC_ADMIN, instrumentId: CBTC_ID };
  }
  return null;
}

function resolvePoolInstruments(pool: PoolInfo | null): PoolInstruments | null {
  if (!pool) return null;
  const instA = resolveInstrumentForSymbol(pool.tokenA?.symbol);
  const instB = resolveInstrumentForSymbol(pool.tokenB?.symbol);
  if (!instA || !instB) return null;
  return { instrumentA: instA, instrumentB: instB };
}

function isDevnetSwapApiResponse(value: any, expectedRequestId = 'ping'): boolean {
  return Boolean(
    value &&
    typeof value === 'object' &&
    value.requestId === expectedRequestId &&
    typeof value.ok === 'boolean'
  );
}

function resolveSwapDirection(
  pool: PoolInfo | null,
  fromSymbol: string,
  toSymbol: string
): 'A2B' | 'B2A' | null {
  if (!pool) return null;
  const a = pool.tokenA.symbol.toUpperCase();
  const b = pool.tokenB.symbol.toUpperCase();
  const from = fromSymbol.toUpperCase();
  const to = toSymbol.toUpperCase();
  if (from === a && to === b) return 'A2B';
  if (from === b && to === a) return 'B2A';
  return null;
}

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

const LOOP_RATE_LIMIT_RETRY_DELAYS_MS = [2000, 5000, 9000, 14000, 20000];
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
  if (status === 429) {
    return true;
  }
  const message = String(
    err?.message ??
      err?.error?.message ??
      err?.details?.message ??
      err?.details?.toString?.() ??
      ''
  ).toLowerCase();
  return message.includes('429') || message.includes('rate limit');
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
  throw new Error(`[loop] ${label} failed after rate limit retries`);
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

function extractPreparedTransferInstructionCid(prepared: PreparedTransfer): string | undefined {
  return (
    prepared?.transferInstructionCid ??
    prepared?.transfer_instruction_cid ??
    prepared?.transferInstructionId ??
    prepared?.transfer_instruction_id ??
    prepared?.payload?.transferInstructionCid ??
    prepared?.payload?.transfer_instruction_cid ??
    prepared?.payload?.transferInstructionId ??
    prepared?.payload?.transfer_instruction_id
  );
}

function extractExerciseContractId(commands: any[]): string | undefined {
  for (const command of commands) {
    const exercise =
      command?.ExerciseCommand ??
      command?.exerciseCommand ??
      command?.exercise_command;
    const contractId = exercise?.contractId ?? exercise?.contract_id;
    if (contractId) {
      return contractId;
    }
  }
  return undefined;
}

function extractPreparedDisclosedContracts(prepared: PreparedTransfer): any[] | undefined {
  const disclosed =
    prepared?.disclosedContracts ??
    prepared?.disclosed_contracts ??
    prepared?.extraArgs?.disclosedContracts ??
    prepared?.extra_args?.disclosed_contracts ??
    [];
  return Array.isArray(disclosed) && disclosed.length > 0 ? disclosed : undefined;
}

function extractPreparedPackagePreference(prepared: PreparedTransfer): string[] | undefined {
  const pref =
    prepared?.packageIdSelectionPreference ??
    prepared?.package_id_selection_preference ??
    prepared?.packageIdSelection ??
    prepared?.package_id_selection ??
    undefined;
  return Array.isArray(pref) && pref.length > 0 ? pref : undefined;
}

function extractPreparedSynchronizerId(prepared: PreparedTransfer): string | undefined {
  return prepared?.synchronizerId ?? prepared?.synchronizer_id ?? undefined;
}

function extractPreparedActAs(prepared: PreparedTransfer, fallback: string): string | string[] {
  const actAs = prepared?.actAs ?? prepared?.act_as ?? prepared?.actAsParties ?? prepared?.act_as_parties;
  return actAs || fallback;
}

function extractPreparedReadAs(prepared: PreparedTransfer, fallback: string): string | string[] | undefined {
  const readAs = prepared?.readAs ?? prepared?.read_as ?? prepared?.readAsParties ?? prepared?.read_as_parties;
  return readAs || fallback;
}

function decimalAddStrings(a: string, b: string): string {
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
}

export default SwapInterface;