import React, { useEffect } from 'react';
import { BrowserRouter as Router, Routes, Route } from 'react-router-dom';
import { Toaster } from 'react-hot-toast';
import { initializeCantonApi } from './services';
import { useAppStore } from './stores';
import { useCantonHealth } from './hooks';
import { BUILD_INFO } from './config/build-info';
import { SwissCross } from './components/HelvetAtoms';
import { useIsMobile } from './hooks/useIsMobile';

// Components
import {
  Header,
  SwapInterface,
  PoolsInterface,
  LiquidityInterface,
  TransactionHistory,
  ConnectionStatus,
  LoadingScreen,
  DevNetCbtcAccept,
  DevNetLoopTxTester,
} from './components';
import AuthCallback from './components/AuthCallback';
import ErrorBoundary from './components/ErrorBoundary';

const CANTON_CONFIG = {
  ledgerApiUrl: 'http://localhost:8080',
  adminApiUrl: 'http://localhost:8080',
  participantId: 'app_user_participant',
};

function App() {
  const isMobile = useIsMobile();
  const { setConnected, theme } = useAppStore();
  const { data: isHealthy, isLoading: healthLoading } = useCantonHealth();
  useEffect(() => {
    try {
      initializeCantonApi(CANTON_CONFIG);
    } catch (error) {
      console.error('Failed to initialize Canton API:', error);
    }
  }, []);

  useEffect(() => {
    if (isHealthy !== undefined) {
      setConnected(isHealthy);
    }
  }, [isHealthy, setConnected]);

  useEffect(() => {
    document.documentElement.classList.toggle('dark', theme === 'dark');
  }, [theme]);

  if (healthLoading) {
    return <LoadingScreen />;
  }

  return (
    <ErrorBoundary>
    <Router>
      <div style={{ position: "relative", width: "100%", minHeight: "100vh", background: "#0B0B0D", overflow: "hidden", color: "var(--text-1)", display: "flex", flexDirection: "column" }}>

        {/* LAYER 1: Cross field pattern at 4% opacity */}
        <svg width="100%" height="100%" style={{ position: "fixed", inset: 0, opacity: 0.04, pointerEvents: "none" }}>
          <defs>
            <pattern id="crossGrid-app" x="0" y="0" width="60" height="60" patternUnits="userSpaceOnUse">
              <rect x="25" y="10" width="10" height="40" fill="#C4A264" />
              <rect x="10" y="25" width="40" height="10" fill="#C4A264" />
            </pattern>
          </defs>
          <rect width="100%" height="100%" fill="url(#crossGrid-app)" />
        </svg>

        {/* LAYER 2: Radial gold glow */}
        <div style={{ position: "fixed", top: "15%", left: "50%", transform: "translate(-50%, -50%)", width: "var(--glow-w)", height: "var(--glow-h)", background: "radial-gradient(ellipse, rgba(196,162,100,0.06) 0%, transparent 70%)", pointerEvents: "none" }} />

        {/* LAYER 3: Film grain */}
        <div style={{ position: "fixed", inset: 0, backgroundImage: 'url("data:image/svg+xml,%3Csvg viewBox=\'0 0 200 200\' xmlns=\'http://www.w3.org/2000/svg\'%3E%3Cfilter id=\'n\'%3E%3CfeTurbulence type=\'fractalNoise\' baseFrequency=\'0.9\' numOctaves=\'4\' stitchTiles=\'stitch\'/%3E%3C/filter%3E%3Crect width=\'100%25\' height=\'100%25\' filter=\'url(%23n)\' opacity=\'1\'/%3E%3C/svg%3E")', opacity: 0.011, pointerEvents: "none", zIndex: 999 }} />

        {/* Content above layers */}
        <div style={{ position: "relative", zIndex: 1, display: "flex", flexDirection: "column", minHeight: "100vh" }}>

          {/* DevNet Badge */}
          {process.env.NODE_ENV === 'development' && (
            <div style={{
              position: "fixed", bottom: 8, right: 8, zIndex: 50,
              background: "var(--ink-3)", border: "1px solid var(--rule)",
              fontFamily: "var(--mono)", fontSize: "0.6rem", letterSpacing: "0.14em",
              textTransform: "uppercase", color: "var(--text-3)",
              padding: "0.15rem 0.5rem", opacity: 0.6,
            }}>
              DevNet
            </div>
          )}

          <Header />

          <ConnectionStatus />

          <main style={{ maxWidth: "var(--main-max-w)", margin: "0 auto", padding: "var(--main-py) var(--main-px)", flex: 1, width: "100%" }}>
            <Routes>
              <Route path="/" element={<SwapInterface />} />
              <Route path="/swap" element={<SwapInterface />} />
              <Route path="/pools" element={<PoolsInterface />} />
              <Route path="/liquidity" element={<LiquidityInterface />} />
              <Route path="/history" element={<TransactionHistory />} />
              <Route path="/devnet/cbtc" element={<DevNetCbtcAccept />} />
              <Route path="/devnet/loop-tx" element={<DevNetLoopTxTester />} />
              <Route path="/auth/callback" element={<AuthCallback />} />
            </Routes>
          </main>

        {/* Footer */}
        <footer style={{ borderTop: "1px solid var(--rule)", padding: `1.5rem var(--footer-px)`, display: "flex", flexDirection: isMobile ? "column" : "row", justifyContent: "space-between", alignItems: "center", background: "var(--ink-2)", marginTop: "auto", gap: isMobile ? "1rem" : undefined }}>
          <div style={{ display: "flex", alignItems: "center", gap: "0.5rem" }}>
            <SwissCross size={8} color="var(--text-3)" />
            <span style={{ fontFamily: "var(--mono)", fontSize: "0.68rem", color: "var(--text-3)", letterSpacing: "0.14em", textTransform: "uppercase" }}>
              Helvet Swap {"\u00B7"} Built on Canton Network
            </span>
          </div>
          <div style={{ display: "flex", gap: "var(--footer-stats-gap)" }}>
            {([["Network", "Canton"], ["Version", "v1.0"], ["Status", "\u25CF Live"]] as const).map(([l, v]) => (
              <div key={l}>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.6rem", color: "var(--text-3)", letterSpacing: "0.14em", textTransform: "uppercase" }}>{l}</div>
                <div style={{ fontFamily: "var(--mono)", fontSize: "0.72rem", color: v.includes("\u25CF") ? "var(--green)" : "var(--text-2)", marginTop: "0.15rem" }}>{v}</div>
              </div>
            ))}
          </div>
        </footer>

        {/* Toast Notifications */}
        <Toaster
          position="top-right"
          toastOptions={{
            duration: 4000,
            style: {
              background: 'var(--ink-2)',
              color: 'var(--text-1)',
              border: '1px solid var(--rule)',
              borderRadius: '0',
              fontFamily: 'var(--mono)',
              fontSize: '0.82rem',
            },
            success: {
              iconTheme: {
                primary: '#4A8C6F',
                secondary: '#0B0B0D',
              },
            },
            error: {
              iconTheme: {
                primary: '#8C4A4A',
                secondary: '#0B0B0D',
              },
            },
          }}
        />
        </div>
      </div>
    </Router>
    </ErrorBoundary>
  );
}

export default App;