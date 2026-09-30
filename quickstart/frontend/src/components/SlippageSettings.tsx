import React, { useState } from 'react';
import { useAppStore } from '../stores/useAppStore';

interface SlippageSettingsProps {
  onClose?: () => void;
}

const SlippageSettings: React.FC<SlippageSettingsProps> = ({ onClose }) => {
  const { slippage, setSlippage } = useAppStore();
  const [customSlippage, setCustomSlippage] = useState('');

  const presetSlippages = [0.1, 0.5, 1.0];

  const handleSlippageChange = (value: number) => {
    setSlippage(value);
    setCustomSlippage('');
  };

  const handleCustomSlippageChange = (value: string) => {
    setCustomSlippage(value);
    const numValue = parseFloat(value);
    if (!isNaN(numValue) && numValue >= 0 && numValue <= 50) {
      setSlippage(numValue);
    }
  };

  return (
    <div>
      <div style={{ display: "flex", alignItems: "center", gap: "8px" }}>
        {presetSlippages.map((s) => (
          <button
            key={s}
            onClick={() => handleSlippageChange(s)}
            style={{
              fontFamily: "var(--mono)", fontSize: "0.68rem", letterSpacing: "0.08em",
              background: slippage === s && !customSlippage ? "var(--gold-pale)" : "transparent",
              border: `1px solid ${slippage === s && !customSlippage ? "var(--rule-gold)" : "var(--rule)"}`,
              color: slippage === s && !customSlippage ? "var(--gold)" : "var(--text-3)",
              padding: "0.2rem 0.45rem", cursor: "pointer",
            }}
          >{s}%</button>
        ))}
        <input
          type="number"
          placeholder="Custom"
          value={customSlippage}
          onChange={(e) => handleCustomSlippageChange(e.target.value)}
          style={{
            width: 80, fontFamily: "var(--mono)", fontSize: "0.68rem",
            background: "transparent", border: "1px solid var(--rule)",
            color: "var(--text-1)", padding: "0.2rem 0.4rem",
            outline: "none", letterSpacing: "0.04em",
          }}
          min="0"
          max="50"
          step="0.1"
        />
        <span style={{ fontFamily: "var(--mono)", fontSize: "0.64rem", color: "var(--text-3)", whiteSpace: "nowrap" }}>slippage</span>
      </div>
      {slippage > 5 && (
        <span style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--red-muted)", marginTop: "0.4rem", display: "inline-block" }}>
          High slippage
        </span>
      )}
    </div>
  );
};

export default SlippageSettings;
