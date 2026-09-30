// OAuth Authentication Service for Canton Network DevNet
// Using Keycloak-js with PKCE flow (Authorization Code + PKCE)

import Keycloak from "keycloak-js";

const AUTH_ENABLED = (process.env.REACT_APP_AUTH_ENABLED ?? "false") === "true";

const keycloak = new Keycloak({
  url: process.env.REACT_APP_OAUTH_BASE_URL ?? "https://auth.canton.network",
  realm: process.env.REACT_APP_OAUTH_REALM ?? "AppProvider",
  clientId: process.env.REACT_APP_OAUTH_CLIENT_ID ?? "clearportx-web",
});

let initialized = false;

/**
 * Initialize Keycloak authentication
 * Should be called once at app startup
 */
export async function initAuth(): Promise<void> {
  if (!AUTH_ENABLED || initialized) {
    return;
  }

  try {
    initialized = true;
    const authenticated = await keycloak.init({
      onLoad: "check-sso",
      pkceMethod: "S256",
      checkLoginIframe: false,
      redirectUri: process.env.REACT_APP_OAUTH_REDIRECT_URI ?? window.location.origin + "/auth/callback",
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    });

    // Auto-refresh token every 30 seconds
    setInterval(async () => {
      if (keycloak.authenticated) {
        try {
          const refreshed = await keycloak.updateToken(60);
          // token refreshed silently
        } catch (error) {
          console.warn('[Auth] Failed to refresh token:', error);
        }
      }
    }, 30000);
  } catch (error) {
    console.error('[Auth] Failed to initialize Keycloak:', error);
    throw error;
  }
}

/**
 * Check if user is authenticated
 */
export function isAuthenticated(): boolean {
  return AUTH_ENABLED ? !!keycloak.authenticated : true;
}

/**
 * Initiate login flow
 */
export function login(): void {
  if (!AUTH_ENABLED) {
    return;
  }

  const redirectUri = process.env.REACT_APP_OAUTH_REDIRECT_URI ?? window.location.origin + "/auth/callback";
  keycloak.login({ redirectUri });
}

/**
 * Logout user
 */
export function logout(): void {
  if (!AUTH_ENABLED) {
    return;
  }

  keycloak.logout({ redirectUri: window.location.origin });
}

/**
 * Get current access token (JWT)
 */
export function getAccessToken(): string | null {
  if (!AUTH_ENABLED) {
    // On mainnet the wallet auth flow provides real JWTs — never return a mock token.
    if (process.env.REACT_APP_NETWORK === 'mainnet') {
      return null;
    }
    return "devnet-mock-token";
  }
  return keycloak.token ?? null;
}

/**
 * Get username from token
 */
export function getUsername(): string | null {
  if (!AUTH_ENABLED) {
    return "devnet-user";
  }

  const tokenParsed = keycloak.tokenParsed as Record<string, any> | undefined;
  return tokenParsed?.preferred_username ?? tokenParsed?.email ?? null;
}

/**
 * Get user's party ID from token (if available)
 */
export function getPartyId(): string | null {
  if (!AUTH_ENABLED) {
    return null;
  }

  const tokenParsed = keycloak.tokenParsed as Record<string, any> | undefined;
  // Check for custom party claim (you may need to adjust this based on your Keycloak setup)
  return tokenParsed?.party ?? tokenParsed?.sub ?? null;
}

/**
 * Get full token payload (for debugging)
 */
export function getTokenPayload(): Record<string, any> | null {
  if (!AUTH_ENABLED) {
    return { mock: true, username: "devnet-user" };
  }

  return (keycloak.tokenParsed as Record<string, any>) ?? null;
}

// Export raw keycloak instance for advanced usage
export { keycloak };
