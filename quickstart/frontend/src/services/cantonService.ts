// Canton Service - Uses ClearportX Backend API
import { backendApi } from './backendApi';
import { TokenInfo, PoolInfo } from '../types/canton';

class CantonService {
  private initialized = false;

  initialize(config: any) {
    this.initialized = true;
    return this;
  }

  async isConnected(): Promise<boolean> {
    try {
      const health = await backendApi.healthCheck();
      return health.status === 'UP';
    } catch {
      return false;
    }
  }

  async getHealth(): Promise<boolean> {
    return this.isConnected();
  }

  async queryContracts(templateType: string): Promise<any[]> {
    try {
      if (templateType.includes('Pool')) {
        const pools = await backendApi.getPools();
        return pools.map(pool => ({
          contractId: pool.contractId || `pool-${pool.tokenA.symbol}-${pool.tokenB.symbol}`,
          payload: pool
        }));
      }
      return [];
    } catch (error) {
      console.error('Query contracts failed:', error);
      return [];
    }
  }

  async getTokens(party?: string): Promise<TokenInfo[]> {
    try {
      if (!party) {
        // Get tokens for current user
        const currentUser = localStorage.getItem('current_user') || 'alice@clearportx';
        return await backendApi.getTokens(currentUser);
      }
      return await backendApi.getTokens(party);
    } catch (error) {
      console.error('Get tokens failed:', error);
      return [];
    }
  }

  async getPools(): Promise<PoolInfo[]> {
    try {
      return await backendApi.getPools();
    } catch (error) {
      console.error('Get pools failed:', error);
      return [];
    }
  }
}

// Export singleton instance
export const cantonService = new CantonService();

// For compatibility with existing code
export const initializeCantonApi = (config: any) => {
  return cantonService.initialize(config);
};

export const cantonApi = {
  isConnected: () => cantonService.isConnected(),
  getHealth: () => cantonService.getHealth(),
  queryContracts: (templateType: string) => cantonService.queryContracts(templateType),
  getTokens: (party?: string) => cantonService.getTokens(party),
  getPools: () => cantonService.getPools(),
};

export default cantonService;
