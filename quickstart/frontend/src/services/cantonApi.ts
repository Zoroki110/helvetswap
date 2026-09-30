// Canton API - Uses ClearportX Backend
import { backendApi } from './backendApi';
import { TokenInfo, PoolInfo } from '../types/canton';

export interface CantonConfig {
  ledgerApiUrl: string;
  adminApiUrl: string;
  participantId: string;
}

class CantonService {
  private initialized = false;
  private config?: CantonConfig;
  private readonly authDisabled: boolean = (process.env.REACT_APP_AUTH_ENABLED ?? 'false') !== 'true';

  initialize(config: CantonConfig) {
    this.initialized = true;
    this.config = config;
    return this;
  }

  async isConnected(): Promise<boolean> {
    try {
      if (this.authDisabled) return true; // DevNet/no-auth: always enable actions
      const health = await backendApi.healthCheck();
      // Treat OK/SYNCING as connected during DevNet
      return health.status === 'OK' || health.status === 'SYNCING' || health.synced === true || (health as any).poolsActive > 0;
    } catch {
      return false;
    }
  }

  async getHealth(): Promise<boolean> {
    return this.isConnected();
  }

  async queryContracts(templateType: string): Promise<any[]> {
    // This is called by the real Canton API, redirect to backend
    return [];
  }

  async getTokens(party?: string): Promise<TokenInfo[]> {
    try {
      if (!party) {
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

  async createContract(): Promise<any> {
    return { contractId: 'mock' };
  }

  async exerciseChoice(): Promise<any> {
    return { result: 'success' };
  }
}

// Export singleton instance
const cantonService = new CantonService();

export const initializeCantonApi = (config: CantonConfig) => {
  return cantonService.initialize(config);
};

export const cantonApi = {
  initialize: (config: CantonConfig) => cantonService.initialize(config),
  isConnected: () => cantonService.isConnected(),
  getHealth: () => cantonService.getHealth(),
  queryContracts: (templateType: string) => cantonService.queryContracts(templateType),
  getTokens: (party?: string) => cantonService.getTokens(party),
  getPools: () => cantonService.getPools(),
  createContract: () => cantonService.createContract(),
  exerciseChoice: () => cantonService.exerciseChoice(),
};

export default cantonApi;