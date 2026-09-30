// Helvet Swap Login Modal
// DEPRECATED: Now using OAuth redirect flow with Keycloak (see Header.tsx)
// This file is kept only for backward compatibility but is not used anymore

import React from 'react';
import { SwissCross } from './HelvetAtoms';

interface LoginModalProps {
  onClose: () => void;
}

const LoginModal: React.FC<LoginModalProps> = ({ onClose }) => {
  return (
    <div style={{
      position: "fixed", inset: 0, zIndex: 9999,
      display: "flex", alignItems: "center", justifyContent: "center",
      background: "rgba(11,11,13,0.7)", backdropFilter: "blur(6px)",
    }}>
      <div style={{ background: "var(--ink-2)", border: "1px solid var(--rule)", borderTop: "1px solid var(--rule-gold)", padding: "var(--card-px)", maxWidth: "var(--login-max-w)", width: "100%" }}>
        <div style={{ fontFamily: "var(--display)", fontSize: "1.2rem", fontWeight: 300, fontStyle: "italic", color: "var(--text-1)", marginBottom: "0.8rem" }}>
          Login Deprecated
        </div>
        <p style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)", marginBottom: "1.2rem", lineHeight: 1.6 }}>
          This modal is no longer used. Please use the "Connect Wallet" button in the header.
        </p>
        <button className="btn-primary" onClick={onClose} style={{ width: "100%", justifyContent: "center", height: 40 }}>
          <SwissCross size={9} color="var(--ink)" />
          Close
        </button>
      </div>
    </div>
  );
};

export default LoginModal;
