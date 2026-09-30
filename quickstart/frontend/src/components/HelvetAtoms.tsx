import React from 'react';

export function SwissCross({ size = 12, color = "var(--gold)" }: { size?: number; color?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 12 12" fill="none" style={{ flexShrink: 0 }}>
      <rect x="4.5" y="0" width="3" height="12" fill={color} />
      <rect x="0" y="4.5" width="12" height="3" fill={color} />
    </svg>
  );
}

export function LogoMark({ scale = 1 }: { scale?: number }) {
  const imgSize = Math.round(28 * scale);
  return (
    <div style={{ display: "flex", alignItems: "center", gap: `${0.55 * scale}rem` }}>
      <img src="/helvet-logo.svg" alt="Helvet Swap" style={{ width: imgSize, height: imgSize, objectFit: "contain" }} />
      <span style={{ fontFamily: "var(--display)", fontSize: `${1.35 * scale}rem`, fontWeight: 300, letterSpacing: "0.28em", color: "var(--text-1)", textTransform: "uppercase" as const }}>HELVET</span>
      <span style={{ fontFamily: "var(--mono)", fontSize: `${0.64 * scale}rem`, letterSpacing: "0.18em", color: "var(--gold)", borderLeft: "1px solid var(--rule-gold)", paddingLeft: `${0.6 * scale}rem`, textTransform: "uppercase" as const, paddingTop: "1px" }}>Swap</span>
    </div>
  );
}
