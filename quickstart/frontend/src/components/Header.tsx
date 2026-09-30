import React, { useState, useEffect, useRef, useMemo } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useWalletAuth } from '../wallet';
import { ENABLE_MANUAL_WALLET } from '../wallet/walletConfig';
import { useLoopBalances, useUtxoBalances } from '../hooks';
import toast from 'react-hot-toast';
import { SwissCross, LogoMark } from './HelvetAtoms';
import { useIsMobile } from '../hooks/useIsMobile';

const Header: React.FC = () => {
  const location = useLocation();
  const {
    partyId,
    walletType,
    loading: walletLoading,
    error: walletError,
    authenticateWithDev,
    authenticateWithLoop,
    authenticateWithZoro,
    disconnect,
  } = useWalletAuth();
  const partyForBackend = walletType === 'loop' ? null : partyId || null;
  const { balances: loopBalances, loading: loopLoading } = useLoopBalances(partyId || null, walletType, {
    refreshIntervalMs: 15000,
  });
  const { balances: utxoBalances, loading: utxoLoading } = useUtxoBalances(partyForBackend, {
    ownerOnly: true,
    refreshIntervalMs: 15000,
  });
  const balancesLoading = walletType === 'loop' ? loopLoading : utxoLoading;
  const balanceEntries = useMemo(() => {
    if (walletType === 'loop') {
      return Object.entries(loopBalances)
        .map(([symbol, entry]) => ({
          symbol: symbol.toUpperCase(),
          amount: entry.amount,
          decimals: entry.decimals,
        }))
        .sort((a, b) => Number(b.amount) - Number(a.amount));
    }
    const acc = new Map<string, { symbol: string; amount: string; decimals: number }>();
    Object.values(utxoBalances).forEach((entry) => {
      const symbol = instrumentIdToSymbol(entry.instrumentId);
      const existing = acc.get(symbol);
      if (!existing) {
        acc.set(symbol, { symbol, amount: entry.amount, decimals: entry.decimals });
        return;
      }
      acc.set(symbol, {
        symbol,
        amount: decimalAddStrings(existing.amount, entry.amount),
        decimals: existing.decimals ?? entry.decimals,
      });
    });
    return Array.from(acc.values()).sort((a, b) => Number(b.amount) - Number(a.amount));
  }, [walletType, loopBalances, utxoBalances]);
  const isMobile = useIsMobile();
  const [menuOpen, setMenuOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    const handler = (event: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };

    if (menuOpen) {
      document.addEventListener('mousedown', handler);
    }

    return () => document.removeEventListener('mousedown', handler);
  }, [menuOpen]);

  const navigation = [
    { name: 'Swap', href: '/swap' },
    { name: 'Pools', href: '/pools' },
    { name: 'Liquidity', href: '/liquidity' },
    { name: 'History', href: '/history' },
  ];


  const baseWalletOptions: Array<{
    key: string;
    label: string;
    description: string;
    iconSrc: string;
    iconAlt: string;
    action: () => Promise<unknown>;
    disabled?: boolean;
    tag?: string;
  }> = [
    {
      key: 'loop',
      label: 'Loop Wallet',
      description: 'Connect with the official Loop SDK.',
      iconSrc: '/loop.svg',
      iconAlt: 'Loop Wallet logo',
      action: authenticateWithLoop,
    },
    {
      key: 'zoro',
      label: 'Zoro Wallet',
      description: 'Use the Zoro browser extension.',
      iconSrc: '/zoro.svg',
      iconAlt: 'Zoro Wallet logo',
      action: async () => {},
      disabled: true,
      tag: 'Coming Soon',
    },
  ];

  const walletOptions = ENABLE_MANUAL_WALLET
    ? [
        {
          key: 'party',
          label: 'Party ID (manual)',
          description: 'Enter a Canton party and sign the challenge via Dev Wallet.',
          iconSrc: '/helvet-logo.svg',
          iconAlt: 'Helvet Swap logo',
          action: authenticateWithDev,
        },
        ...baseWalletOptions,
      ]
    : baseWalletOptions;

  // Keep synchronous so wallet connect runs in the click stack (needed for Loop popup).
  const handleConnect = (action: () => Promise<unknown> | unknown) => {
    try {
      const res = action();
      if (res && typeof (res as Promise<unknown>).catch === "function") {
        (res as Promise<unknown>).catch((err) => {
          toast.error(err instanceof Error ? err.message : "Wallet connection failed");
          console.error("Wallet connection failed", err);
        });
      }
      setMenuOpen(false);
    } catch (err) {
      toast.error(err instanceof Error ? err.message : "Wallet connection failed");
      console.error("Wallet connection failed", err);
    }
  };

  const connected = Boolean(partyId);

  const walletPill = (
    <div style={{ position: "relative" }} ref={dropdownRef}>
      {connected ? (
        <div
          onClick={() => { setMenuOpen((prev) => !prev); setMobileNavOpen(false); }}
          style={{
            display: "flex", alignItems: "center", gap: "0.5rem",
            background: "var(--ink-3)", border: "1px solid var(--rule)",
            padding: "0 0.8rem", height: 36, cursor: "pointer",
            transition: "border-color 0.2s",
          }}
          onMouseOver={e => (e.currentTarget.style.borderColor = "var(--gold-dim)")}
          onMouseOut={e => (e.currentTarget.style.borderColor = "rgba(255,255,255,0.055)")}
        >
          <span style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", letterSpacing: "0.14em", color: "var(--text-3)", textTransform: "uppercase" }}>Wallet</span>
          <span style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-1)", letterSpacing: "0.02em" }}>{formatPartyId(partyId)}</span>
          {walletType && (
            <span style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", letterSpacing: "0.1em", color: "var(--gold)", background: "var(--gold-pale)", padding: "0.15rem 0.4rem", textTransform: "uppercase" }}>{walletType}</span>
          )}
          <svg width="8" height="8" viewBox="0 0 8 8" fill="none"><path d="M1 2.5L4 5.5L7 2.5" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
        </div>
      ) : (
        <button
          className="btn-ghost"
          style={{ color: "var(--gold)", borderColor: "var(--rule-gold)", gap: isMobile ? "0.3rem" : "0.4rem", padding: isMobile ? "0 0.5rem" : "0 1.2rem", height: isMobile ? 28 : 36, fontSize: isMobile ? "0.64rem" : undefined }}
          onClick={() => { setMenuOpen((prev) => !prev); setMobileNavOpen(false); }}
          disabled={walletLoading}
        >
          <SwissCross size={isMobile ? 6 : 8} />
          {isMobile ? "Connect" : "Connect Wallet"}
        </button>
      )}

      {menuOpen && (
        <div style={{
          position: "absolute", right: 0, marginTop: 4, zIndex: 50,
          background: "var(--ink-2)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)",
          minWidth: 300, maxHeight: 400, overflowY: "auto", padding: "0.8rem",
        }}>
          {connected ? (
            <>
              <div style={{ padding: "0.6rem 0.8rem", borderBottom: "1px solid var(--rule)", marginBottom: "0.5rem" }}>
                <div className="label-caps" style={{ color: "var(--gold)", marginBottom: "0.4rem" }}>Connected</div>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-1)", wordBreak: "break-all" }}>{partyId}</div>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--text-3)", marginTop: "0.2rem" }}>Wallet: {walletType ?? 'unknown'}</div>
              </div>

              <div style={{ padding: "0.4rem 0.8rem", marginBottom: "0.5rem" }}>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-2)", letterSpacing: "0.1em", textTransform: "uppercase", marginBottom: "0.4rem" }}>Balances</div>
                {balancesLoading && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)" }}>Loading...</div>
                )}
                {!balancesLoading && balanceEntries.length === 0 && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)" }}>No holdings visible</div>
                )}
                {!balancesLoading && balanceEntries.length > 0 &&
                  balanceEntries.slice(0, 5).map((h) => (
                    <div key={h.symbol} style={{ display: "flex", justifyContent: "space-between", fontFamily: "var(--mono)", fontSize: "0.72rem", marginBottom: "0.2rem" }}>
                      <span style={{ color: "var(--text-2)" }}>{h.symbol}</span>
                      <span style={{ color: "var(--text-1)" }}>{formatHoldingAmount(h.symbol, h.amount, h.decimals)}</span>
                    </div>
                  ))}
                {!balancesLoading && balanceEntries.length > 5 && (
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--text-3)", marginTop: "0.2rem" }}>+ {balanceEntries.length - 5} more</div>
                )}
              </div>

              <button
                className="btn-ghost"
                style={{ width: "100%", justifyContent: "center", marginTop: "0.3rem" }}
                onClick={() => { disconnect(); setMenuOpen(false); }}
              >
                Disconnect
              </button>
            </>
          ) : (
            <>
              <div style={{ padding: "0.4rem 0.6rem 0.6rem", borderBottom: "1px solid var(--rule)", marginBottom: "0.5rem" }}>
                <span className="label-caps" style={{ color: "var(--gold)" }}>Select Wallet</span>
              </div>
              {walletOptions.map((option) => {
                const isDisabled = option.disabled || walletLoading;
                return (
                  <div
                    key={option.key}
                    className={option.disabled ? undefined : "rule-hover"}
                    onClick={isDisabled ? undefined : () => handleConnect(option.action)}
                    style={{
                      padding: "0.7rem 0.8rem", borderBottom: "1px solid var(--rule)",
                      cursor: isDisabled ? "not-allowed" : "pointer",
                      opacity: option.disabled ? 0.5 : walletLoading ? 0.6 : 1,
                      pointerEvents: option.disabled ? "none" : undefined,
                      display: "flex", alignItems: "center", gap: "0.6rem",
                    }}
                  >
                    <div style={{ width: 28, height: 28, borderRadius: "50%", border: "1px solid var(--rule)", display: "flex", alignItems: "center", justifyContent: "center", overflow: "hidden", background: "var(--ink-3)", flexShrink: 0 }}>
                      <img src={option.iconSrc} alt={option.iconAlt} style={{ width: 18, height: 18, objectFit: "contain" }} loading="lazy" />
                    </div>
                    <div style={{ flex: 1 }}>
                      <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
                        <span style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-1)", letterSpacing: "0.04em" }}>{option.label}</span>
                        {option.tag && (
                          <span style={{ fontFamily: "var(--mono)", fontSize: "0.52rem", letterSpacing: "0.1em", textTransform: "uppercase", color: "var(--text-3)", background: "var(--ink-4)", padding: "0.12rem 0.4rem", borderRadius: 2 }}>{option.tag}</span>
                        )}
                      </div>
                      <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--text-3)", marginTop: "0.1rem" }}>{option.description}</div>
                    </div>
                  </div>
                );
              })}
              {walletError && (
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--red-muted)", padding: "0.5rem 0.8rem" }}>{walletError}</div>
              )}
              {walletLoading && (
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--text-3)", padding: "0.4rem 0.8rem" }}>Connecting...</div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );

  const connectionDot = (
    <span style={{ display: "flex", alignItems: "center", gap: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--green)", letterSpacing: "0.08em" }}>
      <span style={{ width: 5, height: 5, borderRadius: "50%", background: "var(--green)", animation: "pulse-dot 2s ease-in-out infinite", display: "inline-block" }} />
      Canton
    </span>
  );

  return (
    <>
      <nav style={{
        display: "flex", alignItems: "center", justifyContent: "space-between",
        padding: "0 var(--header-px)", height: 58, borderBottom: "1px solid var(--rule)",
        background: "rgba(11,11,13,0.92)", backdropFilter: "blur(10px)",
        WebkitBackdropFilter: "blur(10px)", position: "sticky", top: 0, zIndex: 100,
      }}>
        {isMobile ? (
          <>
            <Link to="/" style={{ textDecoration: "none" }}>
              <LogoMark scale={0.75} />
            </Link>
            <div style={{ display: "flex", gap: "0.75rem", alignItems: "center" }}>
              {walletPill}
              <button
                onClick={() => { setMobileNavOpen(!mobileNavOpen); setMenuOpen(false); }}
                style={{
                  background: "transparent",
                  border: "1px solid var(--rule)",
                  width: 36, height: 36,
                  display: "flex", flexDirection: "column",
                  alignItems: "center", justifyContent: "center",
                  gap: 4, cursor: "pointer",
                  padding: 8,
                }}
              >
                {[0, 1, 2].map(i => (
                  <div key={i} style={{
                    width: 16, height: 1.5,
                    background: mobileNavOpen ? "var(--gold)" : "var(--text-2)",
                    transition: "background 0.2s",
                  }} />
                ))}
              </button>
            </div>
          </>
        ) : (
          <>
            <div style={{ display: "flex", alignItems: "center", gap: "var(--header-logo-gap)" }}>
              <Link to="/" style={{ textDecoration: "none" }}>
                <LogoMark scale={1.05} />
              </Link>
              <div style={{ display: "flex", gap: "var(--nav-gap)", alignItems: "center" }}>
                {navigation.map((item) => {
                  const isActive = location.pathname === item.href ||
                    (location.pathname === '/' && item.href === '/swap');
                  return (
                    <Link
                      key={item.name}
                      to={item.href}
                      className={`nav-link${isActive ? " active" : ""}`}
                    >{item.name}</Link>
                  );
                })}
              </div>
            </div>
            <div style={{ display: "flex", gap: "0.75rem", alignItems: "center" }}>
              {connectionDot}
              {walletPill}
            </div>
          </>
        )}
      </nav>
      {isMobile && mobileNavOpen && (
        <div style={{
          position: "sticky", top: 58, zIndex: 99,
          background: "var(--ink-2)",
          borderBottom: "1px solid var(--rule)",
        }}>
          {navigation.map((item) => {
            const isActive = location.pathname === item.href ||
              (location.pathname === '/' && item.href === '/swap');
            return (
              <Link
                key={item.name}
                to={item.href}
                className={`nav-link${isActive ? " active" : ""}`}
                onClick={() => setMobileNavOpen(false)}
                style={{
                  display: "block",
                  padding: "1rem var(--header-px)",
                  borderBottom: "1px solid var(--rule)",
                  textDecoration: "none",
                }}
              >{item.name}</Link>
            );
          })}
        </div>
      )}
    </>
  );
};

// Sanity: formatHoldingAmount("CC", "188800", 10) => "188,800"
// Sanity: formatHoldingAmount("CBTC", "1.1", 8) => "1.1000"
function formatHoldingAmount(symbol: string, quantity: string, decimals?: number) {
  const num = Number(quantity);
  const isCc = symbol?.toUpperCase() === 'CC';
  const valid = Number.isFinite(num);
  const formatter = new Intl.NumberFormat('en-US', {
    useGrouping: true,
    minimumFractionDigits: isCc ? 0 : 0,
    maximumFractionDigits: isCc ? 0 : 4,
  });
  const formatted = formatter.format(valid ? num : 0);
  // For non-CC, drop trailing zeros after the decimal point.
  if (!isCc && formatted.includes('.')) {
    return formatted.replace(/\.0+$/, '').replace(/(\.\d*?)0+$/, '$1');
  }
  return formatted;
}

function instrumentIdToSymbol(instrumentId?: string) {
  const raw = instrumentId || '';
  const upper = raw.toUpperCase();
  if (upper === 'AMULET') {
    return 'CC';
  }
  return upper;
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

function formatPartyId(party?: string | null) {
  if (!party) return 'Not connected';
  if (party.length <= 22) {
    return party;
  }
  return `${party.slice(0, 10)}...${party.slice(-6)}`;
}

export default Header;
