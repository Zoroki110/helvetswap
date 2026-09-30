// Canton Queries - Uses Backend API
import { useQuery, useMutation } from '@tanstack/react-query';
import { backendApi } from '../services/backendApi';
import { cantonApi } from '../services/cantonApi';

export const useCantonHealth = () => {
  return useQuery({
    queryKey: ['canton-health'],
    queryFn: () => cantonApi.getHealth(),
    refetchInterval: 5000,
  });
};

export const useTokens = (party?: string) => {
  return useQuery({
    queryKey: ['tokens', party],
    queryFn: () => backendApi.getTokens(party || 'alice@clearportx'),
  });
};

export const usePools = () => {
  return useQuery({
    queryKey: ['pools'],
    queryFn: () => backendApi.getPools(),
  });
};

export const useSwapQuote = (params: any) => {
  return useQuery({
    queryKey: ['swap-quote', params],
    queryFn: () => backendApi.calculateSwapQuote(params),
    enabled: !!params && !!params.poolId && !!params.inputAmount,
  });
};

export const useSwapMutation = () => {
  return useMutation({
    mutationFn: (params: any) => backendApi.executeAtomicSwap(params),
  });
};