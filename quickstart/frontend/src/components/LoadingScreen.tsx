import React from 'react';
import { LogoMark } from './HelvetAtoms';

const LoadingScreen: React.FC = () => {
  return (
    <div style={{
      minHeight: "100vh", display: "flex", flexDirection: "column",
      alignItems: "center", justifyContent: "center",
      background: "var(--ink)", gap: "1.5rem",
    }}>
      <LogoMark scale={1.2} />
      <div style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)", letterSpacing: "0.14em", textTransform: "uppercase" }}>
        Connecting to Canton Network...
      </div>
      <div className="shimmer-block" style={{ width: 120, height: 2 }} />
    </div>
  );
};

export default LoadingScreen;
