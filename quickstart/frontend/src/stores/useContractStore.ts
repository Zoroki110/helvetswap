// Zustand store for caching Canton contract IDs and state
import { create } from 'zustand';
import { TokenInfo, PoolInfo } from '../types/canton';

export interface ContractCache {
  tokens: Map<string, TokenInfo>; // symbol -> TokenInfo with contractId
  pools: Map<string, PoolInfo>;   // poolKey (e.g., "USDC-ETH") -> PoolInfo with contractId
  lastUpdate: number;
}

interface ContractStoreState {
  // Contract cache
  cache: ContractCache;

  // Loading states
  isLoadingTokens: boolean;
  isLoadingPools: boolean;

  // Actions
  setTokens: (tokens: TokenInfo[]) => void;
  setPools: (pools: PoolInfo[]) => void;
  getTokenBySymbol: (symbol: string) => TokenInfo | undefined;
  getPoolByPair: (tokenASymbol: string, tokenBSymbol: string) => PoolInfo | undefined;
  clearCache: () => void;

  // Loading state setters
  setLoadingTokens: (loading: boolean) => void;
  setLoadingPools: (loading: boolean) => void;
}

// Helper function to create pool key
const createPoolKey = (tokenASymbol: string, tokenBSymbol: string): string => {
  const sorted = [tokenASymbol, tokenBSymbol].sort();
  return `${sorted[0]}-${sorted[1]}`;
};

export const useContractStore = create<ContractStoreState>((set, get) => ({
  // Initial state
  cache: {
    tokens: new Map(),
    pools: new Map(),
    lastUpdate: 0
  },
  isLoadingTokens: false,
  isLoadingPools: false,

  // Set tokens in cache
  setTokens: (tokens: TokenInfo[]) => {
    const tokenMap = new Map<string, TokenInfo>();
    tokens.forEach(token => {
      if (token.contractId) {
        tokenMap.set(token.symbol, token);
      }
    });

    set(state => ({
      cache: {
        ...state.cache,
        tokens: tokenMap,
        lastUpdate: Date.now()
      },
      isLoadingTokens: false
    }));
  },

  // Set pools in cache
  setPools: (pools: PoolInfo[]) => {
    const poolMap = new Map<string, PoolInfo>();
    pools.forEach(pool => {
      const key = createPoolKey(pool.tokenA.symbol, pool.tokenB.symbol);
      poolMap.set(key, pool);
    });

    set(state => ({
      cache: {
        ...state.cache,
        pools: poolMap,
        lastUpdate: Date.now()
      },
      isLoadingPools: false
    }));
  },

  // Get token by symbol
  getTokenBySymbol: (symbol: string) => {
    const { cache } = get();
    return cache.tokens.get(symbol);
  },

  // Get pool by token pair
  getPoolByPair: (tokenASymbol: string, tokenBSymbol: string) => {
    const { cache } = get();
    const key = createPoolKey(tokenASymbol, tokenBSymbol);
    return cache.pools.get(key);
  },

  // Clear entire cache
  clearCache: () => {
    set({
      cache: {
        tokens: new Map(),
        pools: new Map(),
        lastUpdate: 0
      }
    });
  },

  // Loading state setters
  setLoadingTokens: (loading: boolean) => {
    set({ isLoadingTokens: loading });
  },

  setLoadingPools: (loading: boolean) => {
    set({ isLoadingPools: loading });
  }
}));

// Hook to check if cache is stale (older than 30 seconds)
export const useCacheStatus = () => {
  const lastUpdate = useContractStore(state => state.cache.lastUpdate);
  const CACHE_TTL = 30000; // 30 seconds

  return {
    isStale: Date.now() - lastUpdate > CACHE_TTL,
    lastUpdate,
    age: Date.now() - lastUpdate
  };
};
