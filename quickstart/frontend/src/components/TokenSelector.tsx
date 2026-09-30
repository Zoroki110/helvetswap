import React, { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';
import { TokenInfo } from '../types/canton';
import { SwissCross } from './HelvetAtoms';

type BalanceEntry = {
  amount: string;
  decimals: number;
};

interface TokenSelectorProps {
  isOpen: boolean;
  onClose: () => void;
  onSelect: (token: TokenInfo) => void;
  tokens: TokenInfo[];
  selectedToken: TokenInfo | null;
  type: 'from' | 'to';
  balances?: Record<string, BalanceEntry>;
}

const TokenSelector: React.FC<TokenSelectorProps> = ({
  isOpen,
  onClose,
  onSelect,
  tokens,
  selectedToken,
  type,
  balances,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;
    const handler = (e: MouseEvent) => {
      if (ref.current && !ref.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [isOpen, onClose]);

  const filteredTokens = tokens.filter((token) =>
    token.symbol.toLowerCase().includes(searchQuery.toLowerCase()) ||
    token.name.toLowerCase().includes(searchQuery.toLowerCase())
  );

  const formatBalance = (symbol: string) => {
    const entry = balances?.[symbol.toUpperCase()];
    if (!entry) return '0.0000';
    const numeric = Number(entry.amount);
    return Number.isFinite(numeric) ? numeric.toLocaleString('en-US', { maximumFractionDigits: 4 }) : entry.amount;
  };

  if (!isOpen) return null;

  const content = (
    <div style={{
      position: "fixed", inset: 0, zIndex: 9999,
      display: "flex", alignItems: "center", justifyContent: "center",
      background: "rgba(11,11,13,0.7)", backdropFilter: "blur(6px)",
    }} onClick={onClose}>
      <div
        ref={ref}
        onClick={(e) => e.stopPropagation()}
        style={{
          background: "var(--ink-2)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)",
          width: "100%", maxWidth: "var(--modal-max-w)", maxHeight: "85vh", overflowY: "auto",
        }}
      >
        {/* Header */}
        <div style={{ padding: "0.8rem 1rem", borderBottom: "1px solid var(--rule)", display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span className="label-caps" style={{ color: "var(--gold)" }}>Select Token</span>
          <div onClick={onClose} style={{ cursor: "pointer", padding: "0.2rem" }}>
            <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M1 1L9 9M9 1L1 9" stroke="var(--text-3)" strokeWidth="1.2" /></svg>
          </div>
        </div>

        {/* Search */}
        <div style={{ padding: "0.6rem 1rem", borderBottom: "1px solid var(--rule)" }}>
          <input
            type="text"
            placeholder="Search tokens..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            style={{
              width: "100%", background: "var(--ink-4)", border: "1px solid var(--rule)",
              padding: "0.5rem 0.7rem", fontFamily: "var(--mono)", fontSize: "0.78rem",
              color: "var(--text-1)", outline: "none", letterSpacing: "0.04em",
            }}
          />
        </div>

        {/* Token List */}
        {filteredTokens.length === 0 ? (
          <div style={{ padding: "2rem 1rem", textAlign: "center", fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)" }}>
            No tokens found
          </div>
        ) : (
          filteredTokens.map((token) => (
            <div
              key={token.symbol}
              className="rule-hover"
              onClick={() => onSelect(token)}
              style={{
                padding: "0.8rem 1rem", borderBottom: "1px solid var(--rule)",
                display: "flex", justifyContent: "space-between", alignItems: "center",
                cursor: "pointer",
                background: token.symbol === selectedToken?.symbol ? "var(--gold-pale)" : "transparent",
              }}
            >
              <div style={{ display: "flex", alignItems: "center", gap: "0.6rem" }}>
                <SwissCross size={8} />
                <div>
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.85rem", color: "var(--text-1)", letterSpacing: "0.06em" }}>{token.symbol}</div>
                  <div style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)" }}>{token.name}</div>
                </div>
              </div>
              <span style={{ fontFamily: "var(--mono)", fontSize: "0.78rem", color: "var(--text-2)" }}>{formatBalance(token.symbol)}</span>
            </div>
          ))
        )}
      </div>
    </div>
  );

  if (typeof document === 'undefined') return content;
  return createPortal(content, document.body);
};

export default TokenSelector;
