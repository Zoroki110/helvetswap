import { create } from 'zustand';
import { TokenInfo, PoolInfo } from '../types/canton';

interface AppState {
  // Theme
  theme: 'light' | 'dark';
  setTheme: (theme: 'light' | 'dark') => void;

  // Connection
  isConnected: boolean;
  setConnected: (connected: boolean) => void;
  currentParty: string | null;
  setCurrentParty: (party: string | null) => void;

  // Tokens
  selectedTokens: {
    from: TokenInfo | null;
    to: TokenInfo | null;
  };
  setSelectedTokens: (tokens: { from?: TokenInfo | null; to?: TokenInfo | null }) => void;
  swapTokens: () => void;

  // Settings
  slippage: number;
  setSlippage: (slippage: number) => void;

  // Pools
  pools: PoolInfo[];
  setPools: (pools: PoolInfo[]) => void;
}

export const useAppStore = create<AppState>((set, get) => ({
  // Theme
  theme: 'light',
  setTheme: (theme) => set({ theme }),

  // Connection
  isConnected: false,
  setConnected: (isConnected) => set({ isConnected }),
  currentParty: null,
  setCurrentParty: (currentParty) => set({ currentParty }),

  // Tokens
  selectedTokens: {
    from: null,
    to: null,
  },
  setSelectedTokens: (tokens) =>
    set((state) => ({
      selectedTokens: {
        ...state.selectedTokens,
        ...tokens,
      },
    })),
  swapTokens: () =>
    set((state) => ({
      selectedTokens: {
        from: state.selectedTokens.to,
        to: state.selectedTokens.from,
      },
    })),

  // Settings
  slippage: 0.5,
  setSlippage: (slippage) => set({ slippage }),

  // Pools
  pools: [],
  setPools: (pools) => set({ pools }),
}));