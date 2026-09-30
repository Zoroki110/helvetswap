import React, { Component, ErrorInfo } from 'react';
import { SwissCross } from './HelvetAtoms';

interface Props {
  children: React.ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    console.error('[ErrorBoundary] Uncaught error:', error, errorInfo);

    // Sentry integration (conditional — only if initialized)
    try {
      const Sentry = require('@sentry/react');
      if (Sentry && Sentry.captureException) {
        Sentry.captureException(error, { extra: { componentStack: errorInfo.componentStack } });
      }
    } catch {
      // Sentry not installed or not initialized — silent
    }
  }

  handleReload = () => {
    window.location.reload();
  };

  render() {
    if (this.state.hasError) {
      return (
        <div style={{
          minHeight: '100vh',
          background: 'var(--ink, #0B0B0D)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          padding: '2rem',
        }}>
          <SwissCross size={48} color="var(--gold, #C4A264)" />

          <h1 style={{
            fontFamily: "'Playfair Display', serif",
            fontSize: '1.8rem',
            color: 'var(--text-1, #EDE9E0)',
            marginTop: '1.5rem',
            marginBottom: '1rem',
            textAlign: 'center',
          }}>
            Something went wrong
          </h1>

          <p style={{
            fontFamily: "'DM Mono', monospace",
            fontSize: '0.82rem',
            color: 'var(--text-3, #6B6660)',
            maxWidth: '480px',
            textAlign: 'center',
            lineHeight: 1.6,
            marginBottom: '2rem',
          }}>
            {this.state.error?.message || 'An unexpected error occurred'}
          </p>

          <button
            onClick={this.handleReload}
            style={{
              fontFamily: "'DM Mono', monospace",
              fontSize: '0.82rem',
              letterSpacing: '0.14em',
              textTransform: 'uppercase',
              color: 'var(--ink, #0B0B0D)',
              background: 'var(--gold, #C4A264)',
              border: 'none',
              padding: '0.75rem 2rem',
              cursor: 'pointer',
            }}
          >
            Reload
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}

export default ErrorBoundary;
