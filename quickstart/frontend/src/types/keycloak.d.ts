// Type declarations for keycloak-js
// This file fixes TypeScript compilation issues with keycloak-js

declare module 'keycloak-js' {
  interface KeycloakConfig {
    url: string;
    realm: string;
    clientId: string;
  }

  interface KeycloakInitOptions {
    onLoad?: 'login-required' | 'check-sso';
    token?: string;
    refreshToken?: string;
    idToken?: string;
    timeSkew?: number;
    checkLoginIframe?: boolean;
    checkLoginIframeInterval?: number;
    responseMode?: 'query' | 'fragment';
    redirectUri?: string;
    silentCheckSsoRedirectUri?: string;
    silentCheckSsoFallback?: boolean;
    pkceMethod?: 'S256';
    flow?: 'standard' | 'implicit' | 'hybrid';
    enableLogging?: boolean;
  }

  interface KeycloakLoginOptions {
    redirectUri?: string;
    prompt?: 'none' | 'login';
    maxAge?: number;
    loginHint?: string;
    scope?: string;
    idpHint?: string;
    action?: string;
    locale?: string;
    cordovaOptions?: { [key: string]: string };
  }

  interface KeycloakLogoutOptions {
    redirectUri?: string;
  }

  class Keycloak {
    constructor(config: KeycloakConfig | string);

    authenticated?: boolean;
    token?: string;
    tokenParsed?: {
      exp?: number;
      iat?: number;
      sub?: string;
      preferred_username?: string;
      email?: string;
      party?: string;
      [key: string]: any;
    };
    subject?: string;
    idToken?: string;
    idTokenParsed?: { [key: string]: any };
    refreshToken?: string;
    refreshTokenParsed?: { [key: string]: any };
    timeSkew?: number;
    responseMode?: string;
    flow?: string;
    responseType?: string;
    hasRealmRole?: (role: string) => boolean;
    hasResourceRole?: (role: string, resource?: string) => boolean;
    loadUserProfile?: () => Promise<any>;

    init(options: KeycloakInitOptions): Promise<boolean>;
    login(options?: KeycloakLoginOptions): Promise<void>;
    logout(options?: KeycloakLogoutOptions): Promise<void>;
    register(options?: KeycloakLoginOptions): Promise<void>;
    accountManagement(): Promise<void>;
    createLoginUrl(options?: KeycloakLoginOptions): string;
    createLogoutUrl(options?: KeycloakLogoutOptions): string;
    createRegisterUrl(options?: KeycloakLoginOptions): string;
    createAccountUrl(): string;
    isTokenExpired(minValidity?: number): boolean;
    updateToken(minValidity: number): Promise<boolean>;
    clearToken(): void;
  }

  export default Keycloak;
}
