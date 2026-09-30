// Canton Network Configuration for ClearportX
export const PACKAGE_ID = '7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1';

// Canton API Configuration - USES BACKEND API ONLY
export const CANTON_CONFIG = {
  // Use Backend API URL (no direct Canton API calls)
  apiUrl: process.env.REACT_APP_BACKEND_API_URL || 'http://localhost:8080',
  
  // Legacy local API URL (not used)
  localApiUrl: process.env.REACT_APP_BACKEND_API_URL || 'http://localhost:8080',

  // Package ID for clearportx-fees-1.0.0
  packageId: PACKAGE_ID,

  // Participant ID
  participantId: 'app-user',
};

// Template IDs for DAML contracts
export const TEMPLATE_IDS = {
  token: {
    packageId: PACKAGE_ID,
    moduleName: 'Token.Token',
    entityName: 'Token'
  },
  pool: {
    packageId: PACKAGE_ID,
    moduleName: 'AMM.Pool',
    entityName: 'Pool'
  },
  lpToken: {
    packageId: PACKAGE_ID,
    moduleName: 'LPToken.LPToken',
    entityName: 'LPToken'
  },
  swapRequest: {
    packageId: PACKAGE_ID,
    moduleName: 'AMM.SwapRequest',
    entityName: 'SwapRequest'
  },
  poolAnnouncement: {
    packageId: PACKAGE_ID,
    moduleName: 'AMM.PoolAnnouncement',
    entityName: 'PoolAnnouncement'
  },
  protocolFees: {
    packageId: PACKAGE_ID,
    moduleName: 'AMM.ProtocolFees',
    entityName: 'ProtocolFees'
  }
};

// Fee configuration (matching DAML implementation)
export const FEE_CONFIG = {
  // Trading fee: 0.3% (30 basis points)
  tradingFeeBps: 30,

  // Protocol fee split: 25% of trading fee (0.075% total)
  protocolFeeSplitBps: 2500, // 25% of fee

  // LP fee split: 75% of trading fee (0.225% total)
  lpFeeSplitBps: 7500, // 75% of fee

  // Max price impact: 50% (5000 basis points)
  maxPriceImpactBps: 5000,
};

// Network endpoints - BACKEND API ONLY
export const NETWORK_ENDPOINTS = {
  production: process.env.REACT_APP_BACKEND_API_URL || 'http://localhost:8080',
  development: process.env.REACT_APP_BACKEND_API_URL || 'http://localhost:8080',
};

// Determine which environment we're in
export const isProduction = process.env.NODE_ENV === 'production';
export const isDevelopment = process.env.NODE_ENV === 'development';

// Get active API URL - ALWAYS USE BACKEND
export const getApiUrl = (): string => {
  // Always use backend API URL, never direct Canton
  return process.env.REACT_APP_BACKEND_API_URL || 'http://localhost:8080';
};
