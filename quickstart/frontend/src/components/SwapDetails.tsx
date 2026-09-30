import React, { useState } from 'react';
import { SwapQuote, TokenInfo } from '../types/canton';
import { SwissCross } from './HelvetAtoms';

interface SwapDetailsProps {
  quote: SwapQuote;
  fromToken: TokenInfo;
  toToken: TokenInfo;
}

const SwapDetails: React.FC<SwapDetailsProps> = ({ quote, fromToken, toToken }) => {
  const exchangeRate = quote.outputAmount / quote.inputAmount;
  const [open, setOpen] = useState(true);

  return (
    <div style={{ background: "var(--ink)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)" }}>
      <div
        onClick={() => setOpen(!open)}
        style={{ padding: "0.7rem 1rem", display: "flex", justifyContent: "space-between", alignItems: "center", cursor: "pointer" }}
      >
        <span style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: "var(--text-3)", letterSpacing: "0.04em" }}>
          1 {fromToken.symbol} = {exchangeRate.toFixed(6)} {toToken.symbol}
        </span>
        <svg width="10" height="10" viewBox="0 0 10 10" fill="none" style={{ transform: open ? "rotate(180deg)" : "none", transition: "transform 0.2s" }}>
          <path d="M1 3L5 7L9 3" stroke="var(--text-3)" strokeWidth="1.2" />
        </svg>
      </div>
      {open && (
        <div style={{ padding: "0 1rem 0.8rem" }}>
          {[
            ["Rate", `1 ${fromToken.symbol} = ${exchangeRate.toFixed(6)} ${toToken.symbol}`],
            ["Fee", `${quote.fee.toFixed(6)} ${fromToken.symbol}`],
            ["Price Impact", `${quote.priceImpact.toFixed(3)}%`],
            ["Slippage Tolerance", `${quote.slippage}%`],
            ["Minimum Received", `${(quote.outputAmount * (1 - quote.slippage / 100)).toFixed(6)} ${toToken.symbol}`],
          ].map(([k, v]) => (
            <div key={k} style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
              <span style={{ color: "var(--text-3)" }}>{k}</span>
              <span style={{ color: "var(--text-2)" }}>{v}</span>
            </div>
          ))}
          {quote.route && quote.route.length > 0 && (
            <div style={{ display: "flex", justifyContent: "space-between", marginBottom: "0.35rem", fontFamily: "var(--mono)", fontSize: "0.72rem" }}>
              <span style={{ color: "var(--text-3)" }}>Route</span>
              <span style={{ color: "var(--text-2)" }}>{quote.route.join(' \u2192 ')}</span>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default SwapDetails;
