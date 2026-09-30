// ClearportX Backend API Service
// Connects to Spring Boot backend at http://localhost:8080
import axios, { AxiosInstance, AxiosError, AxiosRequestConfig, AxiosResponse } from 'axios';
import { TokenInfo, PoolInfo, SwapQuote, TransactionHistoryEntry, LpTokenInfo, LpPositionInfo } from '../types/canton';
import { getAccessToken, getPartyId } from './auth';
import { BUILD_INFO } from '../config/build-info';
import { getActiveWalletParty, getAuthToken } from '../api/client';

type DomainError = {
  code: string;
  message: string;
  retryAfterMs?: number;
  httpStatus?: number;
};
const runtimeBackendUrl = (typeof window !== 'undefined' && (window as any).__BACKEND_URL__) || undefined;
const BACKEND_URL =
  process.env.REACT_APP_BACKEND_API_URL ||
  BUILD_INFO?.features?.backendUrl ||
  runtimeBackendUrl ||
  'http://localhost:8080';

/**
 * Map frontend party names to Canton ledger party IDs
 * This allows using friendly names like "alice" in the frontend
 * while using the real Canton party IDs in backend calls
 */
const DEVNET_PARTY = process.env.REACT_APP_PARTY_ID
  || 'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';

const PARTY_MAPPING: Record<string, string> = {
  'alice@clearportx': DEVNET_PARTY,
  'alice': DEVNET_PARTY,
  'bob': DEVNET_PARTY,
  'AppProvider': DEVNET_PARTY,
  'app-provider': DEVNET_PARTY,
};

const FALLBACK_TRANSACTIONS: TransactionHistoryEntry[] = [];

/**
 * Map frontend party name to Canton party ID
 */
function mapPartyToBackend(frontendParty: string): string {
  return PARTY_MAPPING[frontendParty] || frontendParty;
}

export interface SwapParams {
  poolId?: string; // Optional - backend auto-discovers by token pair
  inputSymbol: string;
  outputSymbol: string;
  inputAmount: string; // BigDecimal string "0.1000000000"
  minOutput: string;
  maxPriceImpactBps: number; // e.g., 200 for 2%
}

export interface AtomicSwapResponse {
  receiptCid: string;
  trader: string;
  inputSymbol: string;
  outputSymbol: string;
  amountIn: string;
  amountOut: string;
  timestamp: string;
}

export interface AddLiquidityParams {
  poolId: string;
  amountA: string;
  amountB: string;
  minLPTokens: string;
}

export interface RemoveLiquidityParams {
  poolId: string;
  lpTokenAmount: string;
  minAmountA: string;
  minAmountB: string;
}

export interface HealthResponse {
  status: string;
  env: string;
  darVersion: string;
  atomicSwapAvailable: boolean;
  poolsActive: number;
  synced: boolean;
  pqsOffset?: number;
  clearportxContractCount?: number;
}

export class BackendApiService {
  private client: AxiosInstance;

  constructor() {
    this.client = axios.create({
      baseURL: BACKEND_URL,
      timeout: 30000,
      headers: {
        'Content-Type': 'application/json',
      },
    });

    // JWT and X-Party interceptor
    this.client.interceptors.request.use((config) => {
      const walletToken = getAuthToken();
      const token = walletToken || getAccessToken();

      const walletPartyId = getActiveWalletParty();
      const party = walletPartyId || getPartyId() || DEVNET_PARTY;

      const publicEndpoints = [
        '/api/pools',
        '/api/holding-pools/public',
        '/api/swap/inspect',
        '/api/health',
        '/api/health/ledger',
        '/actuator/health',
        '/api/debug/',
        '/api/clearportx/debug/',
      ];
      const url = config.url ?? '';
      const isPublicEndpoint = publicEndpoints.some(endpoint => url.includes(endpoint));

      if (isPublicEndpoint) {
        // Force public calls to stay unauthenticated to avoid 401s when logged out.
        delete (config.headers as any).Authorization;
      } else if (token) {
        config.headers.Authorization = `Bearer ${token}`;
      }

      if (party) {
        config.headers['X-Party'] = party;
      }

      return config;
    });

    // Retry on 429 (rate limit) only; 409 handled by request() wrapper
    this.client.interceptors.response.use(
      (response) => response,
      async (error: AxiosError) => {
        if (error.response?.status === 429) {
          const retryAfter = parseInt((error.response.headers['retry-after'] as string) || '3', 10);
          const waitMs = isNaN(retryAfter) ? 3000 : retryAfter * 1000;
          console.warn(`Rate limited, retrying after ${Math.round(waitMs / 1000)}s...`);
          await new Promise(resolve => setTimeout(resolve, waitMs));
          // Retry the original request
          return this.client.request(error.config!);
        }
        throw error;
      }
    );
  }

  private hasJwt(): boolean {
    const walletToken = getAuthToken();
    if (walletToken) {
      return true;
    }
    const token = getAccessToken();
    return !!token && token !== 'devnet-mock-token';
  }

  private async requestWithFallback<T = any>(
    pathAgnostic: string,
    pathDevnet: string,
    config: AxiosRequestConfig
  ) {
    try {
      return await this.client.request<T>({ ...config, url: pathAgnostic });
    } catch (error: any) {
      const axiosError = error as AxiosError;
      const status = axiosError?.response?.status;
      if (status === 404) {
        // A 404 carrying a structured ApiResponse body ({"ok":false,...}) means
        // the endpoint exists and is intentionally reporting "not found by id".
        // Return that response instead of masking it by hitting the devnet fallback.
        const data = axiosError?.response?.data as any;
        if (data && typeof data === 'object' && 'ok' in data) {
          return axiosError.response as AxiosResponse<T>;
        }
        return await this.client.request<T>({ ...config, url: pathDevnet });
      }
      throw error;
    }
  }

  // Resolve a fresh pool CID for a given poolId by choosing a pool whose canonical token CIDs are alive for poolParty
  private async resolveFreshPoolCidById(poolId: string): Promise<string | null> {
    try {
      const appParty = this.currentParty();
      // Resolve poolParty from poolId
      const parties = await this.client.post('/api/debug/pool-parties', { poolId }).then(r => r.data?.parties).catch(() => null);
      const poolParty = parties?.poolParty;
      if (!poolParty) return null;
      // Fetch all pools visible to appParty
      const cidsRes = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': appParty },
      });
      const poolCids: string[] = cidsRes.data?.cids || [];
      // Fetch poolParty tokens once
      const poolPartyTokensRes = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'Token.Token.Token' },
        headers: { 'X-Party': poolParty },
      }).catch(() => null);
      const poolPartyTokenCidSet: Set<string> =
        new Set<string>((poolPartyTokensRes?.data?.cids as string[] | undefined) || []);
      const emptyPools: string[] = [];
      const aliveCanonPools: string[] = [];
      for (const cid of poolCids) {
        try {
          const meta = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': appParty },
          }).then(r => r.data);
          if (!meta?.success) continue;
          if (meta.poolId !== poolId) continue;
          const ca = meta.tokenACid as string | null;
          const cb = meta.tokenBCid as string | null;
          if (!ca && !cb) emptyPools.push(cid);
          else if (ca && cb && poolPartyTokenCidSet.has(ca) && poolPartyTokenCidSet.has(cb)) aliveCanonPools.push(cid);
        } catch {
          // ignore and continue
        }
      }
      if (emptyPools.length > 0) return emptyPools[0];
      if (aliveCanonPools.length > 0) return aliveCanonPools[0];
      return null;
    } catch {
      return null;
    }
  }

  // Sleep helper for pacing (DevNet-friendly)
  private async sleep(ms: number) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  private currentParty(): string {
    return getActiveWalletParty() || getPartyId() || DEVNET_PARTY;
  }

  // Expose current acting party to UI (for consistent token refresh)
  getCurrentParty(): string {
    return this.currentParty();
  }

  // Always resolve a fresh, party-visible CID for a poolId (server guarantees visibility)
  private async resolveAndGrant(poolId: string, party: string): Promise<{ poolCid: string; poolId: string; packageId?: string }> {
    const res = await this.request<any>(() =>
      this.client.post('/api/debug/resolve-and-grant', { poolId, party }, { headers: { 'X-Party': party } })
    );
    if (!res?.success || !res?.poolCid) {
      throw new Error(`Resolver failed for ${poolId}`);
    }
    let cid: string = res.poolCid as string;
    const resolvedPkg: string | undefined = res.packageId as (string | undefined);
    // Prefer latest AMM package when multiple pool instances exist
    const preferredPkgPrefix =
      (process.env.REACT_APP_AMM_POOL_PACKAGE_ID || '').trim() ||
      (BUILD_INFO?.features?.ammPackageId || '').trim() ||
      '';
    try {
      if (preferredPkgPrefix && (!resolvedPkg || !resolvedPkg?.toLowerCase().startsWith(preferredPkgPrefix.toLowerCase()))) {
        // Scan party-visible pools for the same poolId and pick the one with preferred packageId
        const rows = await this.fetchPoolsForParty(party);
        const sameId = rows.filter(r => r.poolId === poolId);
        for (const row of sameId) {
          try {
            const ti = await this.request<any>(() =>
              this.client.get('/api/debug/pool/template-id', {
                params: { cid: row.poolCid }, headers: { 'X-Party': party }
              })
            );
            const pkg: string = ti?.packageId || '';
            if (pkg && pkg.toLowerCase().startsWith(preferredPkgPrefix.toLowerCase())) {
              cid = row.poolCid;
              break;
            }
          } catch {
            // ignore and continue
          }
        }
      }
    } catch {
      // best-effort preference; keep resolver selection if anything fails
    }
    return { poolCid: cid, poolId: res.poolId as string, packageId: resolvedPkg };
  }

  // Centralized request wrapper with single paced 409 retry
  private async request<T>(call: () => Promise<{ data: T }>): Promise<T> {
    try {
      const res = await call();
      return res.data;
    } catch (e: any) {
      const err = e as AxiosError<any>;
      const status = err.response?.status;
      const body = err.response?.data || {};
      if (status === 409) {
        const retryMs = typeof body.retry_after_ms === 'number' && body.retry_after_ms > 0
          ? body.retry_after_ms
          : 3500;
        console.warn(`Stale response, retrying after ${Math.round(retryMs / 1000)}s...`);
        await this.sleep(retryMs);
        const res2 = await call();
        return res2.data;
      }
      const domain: DomainError = {
        code: body.code || 'INTERNAL',
        message: body.message || body.error || err.message || 'Internal error',
        retryAfterMs: body.retry_after_ms,
        httpStatus: status,
      };
      throw domain;
    }
  }

  // Use backend summary of pools visible to a party (includes poolCid and reserves)
  private async fetchPoolsForParty(party: string): Promise<Array<{ poolId: string; poolCid: string; reserveA: string; reserveB: string; symbolA: string; symbolB: string }>> {
    try {
      // Prefer POST to match backend behavior; fallback to GET for compatibility
      let list: any[] = [];
      try {
        const resPost = await this.client.post('/api/debug/pools-for-party', { party });
        list = Array.isArray(resPost.data) ? resPost.data : [];
      } catch {
        const resGet = await this.client.get('/api/debug/pools-for-party', { params: { party } });
        list = Array.isArray(resGet.data) ? resGet.data : [];
      }
      return list as any[];
    } catch {
      return [];
    }
  }

  // Directory mapping (poolId -> latest poolCid); maintained by backend on updates
  private async getDirectoryLatestCid(poolId: string): Promise<string | null> {
    try {
      const res = await this.client.get('/api/clearportx/debug/directory');
      const mapping = res.data?.mapping || {};
      const entry = mapping[poolId];
      const cid = entry?.poolCid || null;
      return cid;
    } catch {
      return null;
    }
  }

  // Ensure chosen poolCid is visible to acting party; grant if needed; return (possibly) updated CID
  private async ensurePoolCidVisible(poolCid: string, poolId: string, party: string): Promise<string> {
    try {
      const probe = await this.request<any>(() => this.client.get('/api/clearportx/debug/pool/fetch-cid', {
        params: { cid: poolCid },
        headers: { 'X-Party': party },
      }));
      if (probe?.visible === true) return poolCid;
    } catch {
      // fall through to grant
    }
    // Do NOT attempt grant over remote. Pick a party-visible CID for same poolId or by pair.
    try {
      const rows = await this.fetchPoolsForParty(party);
      const sameId = rows.find(r => r.poolId === poolId);
      if (sameId) return sameId.poolCid;
      const m = (poolId || '').toUpperCase().match(/([A-Z]+)-([A-Z]+)/);
      if (m && m.length >= 3) {
        const [, a, b] = m;
        const byPair = rows.filter(r =>
          (r.symbolA === a && r.symbolB === b) || (r.symbolA === b && r.symbolB === a)
        );
        if (byPair.length > 0) {
          const chosen = [...byPair].sort((x, y) =>
            (parseFloat(y.reserveA) * parseFloat(y.reserveB)) - (parseFloat(x.reserveA) * parseFloat(x.reserveB))
          )[0];
          return chosen.poolCid;
        }
      }
    } catch {
      // ignore
    }
    // Last resort: return original and let caller retry candidates
    return poolCid;
  }

  // Choose pool CID for a poolId: prefer empty pool (0/0) for first add; else pick highest TVL
  private async resolvePoolCidForIdPreferEmpty(poolId: string): Promise<string | null> {
    const party = this.currentParty();
    const pools = await this.fetchPoolsForParty(party);
    const forId = pools.filter(p => p.poolId === poolId);
    if (forId.length === 0) return null;
    const empties = forId.filter(p => parseFloat(p.reserveA) === 0 && parseFloat(p.reserveB) === 0);
    if (empties.length > 0) return empties[0].poolCid;
    // pick by TVL proxy (reserveA*reserveB) descending
    const sorted = [...forId].sort((a, b) => {
      const ta = parseFloat(a.reserveA) * parseFloat(a.reserveB);
      const tb = parseFloat(b.reserveA) * parseFloat(b.reserveB);
      return tb - ta;
    });
    return sorted[0].poolCid;
  }

  async resolvePoolCid(poolId: string): Promise<string | null> {
    try {
      const pools = await this.getPoolsAuthenticated();
      const match = pools.find(p => p.poolId === poolId);
      return match?.contractId || null;
    } catch {
      return null;
    }
  }

  /**
   * Health check - verify backend is running and synced
   */
  async healthCheck(): Promise<HealthResponse> {
    try {
      const res = await this.client.get('/api/health/ledger');
      return res.data;
    } catch (error) {
      console.error('Health check failed:', error);
      // Return a default response indicating the backend is down
      return {
        status: 'DOWN',
        env: 'unknown',
        darVersion: 'unknown',
        atomicSwapAvailable: false,
        poolsActive: 0,
        synced: false
      };
    }
  }

  /**
   * Get all active pools from holding-pools endpoint (single source of truth).
   */
  async getPools(): Promise<PoolInfo[]> {
    try {
      const res = await this.client.get('/api/holding-pools/public');
      const rows = Array.isArray(res.data) ? res.data : [];
      const pools = rows.map((row: any) => this.mapHoldingPool(row));
      return this.normalizePools(pools);
    } catch (err) {
      console.error('holding-pools fetch failed', err);
      return [];
    }
  }

  /**
   * Get pools WITH contractId. Requires authentication (wallet connected).
   * Used for swap and liquidity operations that need the contract ID.
   */
  async getPoolsAuthenticated(): Promise<PoolInfo[]> {
    try {
      const res = await this.client.get('/api/holding-pools');
      const rows = Array.isArray(res.data) ? res.data : [];
      const pools = rows.map((row: any) => this.mapHoldingPool(row));
      return this.normalizePools(pools);
    } catch (err) {
      console.error('authenticated holding-pools fetch failed', err);
      return [];
    }
  }

  async getHoldingPool(poolCid: string): Promise<any | null> {
    try {
      const res = await this.client.get(`/api/holding-pools/${poolCid}`);
      return res.data;
    } catch (err) {
      console.error('holding-pool fetch failed', err);
      return null;
    }
  }

  private async loadPartyScopedPools(): Promise<PoolInfo[]> {
    const party = this.currentParty();
    const partyRows = await this.fetchPoolsForParty(party);
    if (partyRows.length === 0) {
      return [];
    }

    let directoryMap: Record<string, { poolCid: string }> = {};
    try {
      const dirRes = await this.client.get('/api/clearportx/debug/directory');
      directoryMap = (dirRes.data?.mapping as Record<string, { poolCid: string }>) || {};
    } catch {
      directoryMap = {};
    }

    const preferredPkgPrefix =
      (process.env.REACT_APP_AMM_POOL_PACKAGE_ID || '').trim() ||
      ((BUILD_INFO?.features as any)?.ammPackageId || '').trim();

    const groups = new Map<string, any[]>();
    for (const row of partyRows) {
      const key = [String(row.symbolA), String(row.symbolB)].sort().join('/');
      const arr = groups.get(key) || [];
      arr.push(row);
      groups.set(key, arr);
    }

    const chosenRows: any[] = [];
    const groupedValues = Array.from(groups.values());
    for (const rows of groupedValues) {
      let picked: any | null = null;
      if (!picked && preferredPkgPrefix) {
        for (const r of rows) {
          try {
            const ti = await this.request<any>(() =>
              this.client.get('/api/debug/pool/template-id', {
                params: { cid: r.poolCid },
                headers: { 'X-Party': party },
              })
            );
            const pkg: string = ti?.packageId || '';
            if (pkg && pkg.toLowerCase().startsWith(String(preferredPkgPrefix).toLowerCase())) {
              picked = r;
              break;
            }
          } catch {
            // ignore and continue
          }
        }
      }
      if (!picked) {
        const latestMatches: any[] = [];
        for (const r of rows) {
          const latest = directoryMap[r.poolId]?.poolCid;
          if (latest && latest === r.poolCid) {
            latestMatches.push(r);
          }
        }
        if (latestMatches.length > 0) {
          picked = [...latestMatches].sort((a, b) =>
            (parseFloat(b.reserveA) * parseFloat(b.reserveB)) -
            (parseFloat(a.reserveA) * parseFloat(a.reserveB))
          )[0];
        }
      }
      if (!picked) {
        picked = [...rows].sort((a, b) =>
          (parseFloat(b.reserveA) * parseFloat(b.reserveB)) -
          (parseFloat(a.reserveA) * parseFloat(a.reserveB))
        )[0];
      }
      if (picked) {
        chosenRows.push(picked);
      }
    }

    return chosenRows.map((row: any) => this.mapPartyPool(row));
  }

  private normalizePools(pools: PoolInfo[]): PoolInfo[] {
    if (!Array.isArray(pools) || pools.length === 0) {
      return [];
    }

    const activePools = pools.filter((pool: PoolInfo) =>
      pool.reserveA > 0 && pool.reserveB > 0
    );
    if (activePools.length === 0) {
      return [...pools];
    }

    const pairToBest: Record<string, PoolInfo> = {};
    for (const pool of activePools) {
      const pairKey = [pool.tokenA.symbol, pool.tokenB.symbol].sort().join('/');
      const tvl = pool.reserveA * pool.reserveB;
      const current = pairToBest[pairKey];
      if (!current || tvl > current.reserveA * current.reserveB) {
        pairToBest[pairKey] = pool;
      }
    }

    return Object.values(pairToBest).sort((a, b) => {
      const tvlA = a.reserveA * a.reserveB;
      const tvlB = b.reserveA * b.reserveB;
      return tvlB - tvlA;
    });
  }

  /**
   * Get tokens owned by a party
   * Maps frontend party names (like 'alice') to Canton party IDs
   */
  async getTokens(party: string): Promise<TokenInfo[]> {
    const cantonParty = mapPartyToBackend(party);
    try {
      // Prefer authenticated wallet endpoint; fallback to debug or legacy public tokens
      let res;
      try {
        res = await this.client.get(`/api/wallet/tokens/${cantonParty}`);
      } catch (e: any) {
        const status = e?.response?.status;
        if (status === 401 || status === 403 || status === 404) {
          // Dev fallback: debug wallet tokens by query param
          try {
            res = await this.client.get(`/api/debug/wallet/tokens`, { params: { party: cantonParty } });
          } catch {
            // Last resort: legacy tokens (includes canonicals)
            res = await this.client.get(`/api/tokens/${cantonParty}`);
          }
        } else {
          throw e;
        }
      }
      
      // Ensure res.data is an array
      const tokenData = Array.isArray(res.data) ? res.data : [];

      // Map all tokens
      let allTokens = tokenData.map((data: any) => this.mapToken(data));
      // Dev-only heuristic: exclude giant faucet mints when unauthenticated
      if (!this.hasJwt()) {
        allTokens = allTokens.filter(t => (t.balance || 0) < 100000);
      }

      const aggregatedTokens = this.aggregateTokensBySymbol(allTokens).filter(token => (token.balance || 0) > 0);

      return aggregatedTokens;
    } catch (error) {
      console.error('Error loading tokens:', error);
      return [];
    }
  }

  /**
   * Wallet-only tokens (exclude pool canonical tokens)
   */
  async getWalletTokens(party: string): Promise<TokenInfo[]> {
    const cantonParty = mapPartyToBackend(party);
    try {
      const res = await this.client.get(`/api/wallet/tokens/${cantonParty}`);
      const tokenData = Array.isArray(res.data) ? res.data : [];
      const aggregated = this.aggregateTokensBySymbol(tokenData.map((data: any) => this.mapToken(data)));
      return aggregated;
    } catch (error) {
      console.error('Error loading wallet tokens:', error);
      return this.getTokens(party);
    }
  }

  async getLpTokens(party: string): Promise<LpTokenInfo[]> {
    const cantonParty = mapPartyToBackend(party);
    try {
      const res = await this.client.get(`/api/wallet/lp-tokens/${cantonParty}`);
      const rows = Array.isArray(res.data) ? res.data : [];
      const mapped = rows.map((row: any) => ({
        poolId: row.poolId || row.pool_id || '',
        amount: parseFloat(row.amount || row.balance || '0'),
        contractId: row.contractId || row.cid || '',
        owner: row.owner || row.party || undefined,
      })).filter(lp => lp.poolId && Number.isFinite(lp.amount) && lp.amount > 0);
      return mapped;
    } catch (error) {
      console.error('Error loading LP tokens:', error);
      return [];
    }
  }

  async getLpPositions(party: string, poolCid?: string): Promise<LpPositionInfo[]> {
    const cantonParty = mapPartyToBackend(party);
    try {
      const res = await this.client.get('/api/lp/positions', {
        params: poolCid ? { ownerParty: cantonParty, poolCid } : { ownerParty: cantonParty },
      });
      const rows = Array.isArray(res.data) ? res.data : [];
      const mapped: LpPositionInfo[] = rows.map((row: any) => ({
        poolId: row.poolId ?? row.pool_id ?? undefined,
        poolCid: row.poolCid ?? row.pool_cid ?? undefined,
        lpBalance: row.lpBalance ?? row.balance ?? '0',
        shareBps: typeof row.shareBps === 'number' ? row.shareBps : row.shareBps ? Number(row.shareBps) : undefined,
        reserveA: row.reserveA ?? undefined,
        reserveB: row.reserveB ?? undefined,
        updatedAt: row.updatedAt ?? undefined,
      })).filter((pos) => (pos.poolCid || pos.poolId) && pos.lpBalance);
      return mapped;
    } catch (error) {
      console.error('Error loading LP positions:', error);
      return [];
    }
  }

  /**
   * Get pool CID for a given pool by matching token symbols
   * Fetches pool CIDs from the backend and finds matching pool
   */
  private async getPoolCidBySymbols(tokenASymbol: string, tokenBSymbol: string): Promise<string | null> {
    try {
      // Get the party ID
      const party = getPartyId() || DEVNET_PARTY;

      // Fetch all pools visible to this party
      const res = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': party }
      });

      const poolCids = res.data?.cids || [];

      // For each pool CID, fetch details to find matching symbols
      for (const cid of poolCids) {
        try {
          const poolRes = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': party }
          });

          const poolData = poolRes.data;
          if (poolData.success &&
              ((poolData.symbolA === tokenASymbol && poolData.symbolB === tokenBSymbol) ||
               (poolData.symbolA === tokenBSymbol && poolData.symbolB === tokenASymbol))) {
            return cid;
          }
        } catch (error) {
          console.error(`Error fetching pool details for CID ${cid}:`, error);
        }
      }

      console.warn(`No pool found for ${tokenASymbol}/${tokenBSymbol}`);
      return null;
    } catch (error) {
      console.error('Error fetching pool CIDs:', error);
      return null;
    }
  }

  /**
   * Execute swap via debug endpoint (JWT-aware)
   */
  async executeAtomicSwap(params: SwapParams): Promise<AtomicSwapResponse> {
    const party = this.currentParty();
    const resolvedPoolId = params.poolId || `${params.inputSymbol}-${params.outputSymbol}`;
    const { poolCid } = await this.resolveAndGrant(resolvedPoolId, party);
    const minOutputStr = params.minOutput ?? '0';
    const body = {
      poolCid,
      poolId: resolvedPoolId,
      inputSymbol: params.inputSymbol,
      outputSymbol: params.outputSymbol,
      amountIn: params.inputAmount,
      minOutput: minOutputStr,
    };

    const swapResponse = await this.request<any>(() =>
      this.client.post('/api/clearportx/debug/swap-by-cid', body, { headers: { 'X-Party': party } })
    );

    const rawResolvedOutput = swapResponse?.resolvedOutput ?? swapResponse?.amountOut ?? swapResponse?.minOutput ?? '0';
    const numericResolved = Number(rawResolvedOutput);
    const amountOut = Number.isFinite(numericResolved)
      ? numericResolved.toString()
      : String(rawResolvedOutput ?? '0');

    return {
      receiptCid: swapResponse?.commandId ?? swapResponse?.outputTokenCid ?? '',
      trader: party,
      inputSymbol: params.inputSymbol,
      outputSymbol: params.outputSymbol,
      amountIn: params.inputAmount,
      amountOut,
      timestamp: new Date().toISOString(),
    };
  }

  /**
   * Get pool CID for a given pool ID
   * Fetches pool CIDs from the backend and finds matching pool by ID
   */
  private async getPoolCidById(poolId: string): Promise<string | null> {
    try {
      // Get the party ID
      const party = getPartyId() || DEVNET_PARTY;

      // Fetch all pools visible to this party
      const res = await this.client.get('/api/clearportx/debug/party-acs', {
        params: { template: 'AMM.Pool.Pool' },
        headers: { 'X-Party': party }
      });

      const poolCids = res.data?.cids || [];

      // For each pool CID, fetch details to find matching pool ID
      for (const cid of poolCids) {
        try {
          const poolRes = await this.client.get('/api/clearportx/debug/pool-by-cid', {
            params: { cid },
            headers: { 'X-Party': party }
          });

          const poolData = poolRes.data;
          // Check if pool ID matches (partial match since frontend may have simplified IDs)
          if (poolData.success &&
              (poolData.poolId === poolId ||
               poolData.poolId.includes(poolId) ||
               poolId.includes(poolData.poolId))) {
            return cid;
          }
        } catch (error) {
          console.error(`Error fetching pool details for CID ${cid}:`, error);
        }
      }

      // If not found by ID, try by symbols extracted from the pool ID
      // Pool IDs often have format like "ETH-USDC-01" or "p0-gv-eth-usdc-225946"
      const symbols = poolId.toUpperCase().match(/([A-Z]+)-([A-Z]+)/);
      if (symbols && symbols.length >= 3) {
        const symbolA = symbols[1];
        const symbolB = symbols[2];
        return this.getPoolCidBySymbols(symbolA, symbolB);
      }

      console.warn(`No pool found for poolId: ${poolId}`);
      return null;
    } catch (error) {
      console.error('Error fetching pool CID by ID:', error);
      return null;
    }
  }

  /**
   * Add liquidity to a pool
   */
  async addLiquidity(params: AddLiquidityParams): Promise<{ lpTokenCid: string; lpAmount: string; historyEntryId?: string }> {
    if (!this.hasJwt()) {
      const payload = {
        poolId: params.poolId,
        amountA: params.amountA.toString(),
        amountB: params.amountB.toString(),
        minLPTokens: params.minLPTokens.toString(),
      };
      const res = await this.request<any>(() =>
        this.client.post('/api/clearportx/debug/add-liquidity-by-cid', payload)
      );
      return {
        lpTokenCid: res?.lpTokenCid ?? '',
        lpAmount: res?.lpAmount ?? params.minLPTokens,
        historyEntryId: res?.historyEntryId,
      };
    }
    const res = await this.client.post('/api/liquidity/add', params);
    return res.data;
  }

  /**
   * Add liquidity by explicit pool CID (CID-first path)
   */
  async addLiquidityByCid(params: {
    poolCid: string;
    poolId?: string;
    amountA: string;
    amountB: string;
    minLPTokens: string;
  }): Promise<{ lpTokenCid: string; lpAmount: string; historyEntryId?: string }> {
    const party = this.currentParty();
    const poolId = params.poolId || '';
    const visibleCid = await this.ensurePoolCidVisible(params.poolCid, poolId, party);
    const body = {
      poolCid: visibleCid,
      poolId,
      amountA: params.amountA,
      amountB: params.amountB,
      minLPTokens: params.minLPTokens,
    };
    const res = await this.request<any>(() => this.client.post('/api/clearportx/debug/add-liquidity-by-cid', body));
    return {
      lpTokenCid: res?.lpTokenCid ?? '',
      lpAmount: res?.lpAmount ?? params.minLPTokens,
      historyEntryId: res?.historyEntryId,
    };
  }
  /**
   * Remove liquidity from a pool
   */
  async removeLiquidity(params: RemoveLiquidityParams): Promise<{
    tokenACid: string;
    tokenBCid: string;
    amountA: string;
    amountB: string
  }> {
    const res = await this.client.post('/api/liquidity/remove', params);
    return res.data;
  }

  /**
   * Calculate swap quote (off-chain estimation)
   * Use this to show estimated output before executing swap
   */
  async calculateSwapQuote(params: {
    poolId: string;
    inputSymbol: string;
    outputSymbol: string;
    inputAmount: string;
  }): Promise<SwapQuote> {
    // Bind the quote to the exact resolver-selected pool CID to avoid mismatch
    const party = this.currentParty();
    const resolvedPoolId = params.poolId || `${params.inputSymbol}-${params.outputSymbol}`;
    const { poolCid } = await this.resolveAndGrant(resolvedPoolId, party);
    const poolMeta = await this.request<any>(() =>
      this.client.get('/api/clearportx/debug/pool-by-cid', {
        params: { cid: poolCid }, headers: { 'X-Party': party }
      })
    );
    if (!poolMeta?.success) {
      throw new Error('Pool not visible for party');
    }
    const symbolA: string = poolMeta.symbolA;
    const symbolB: string = poolMeta.symbolB;
    const reserveA = parseFloat(poolMeta.reserveA);
    const reserveB = parseFloat(poolMeta.reserveB);
    const isAtoB = (params.inputSymbol === symbolA && params.outputSymbol === symbolB);
    const isBtoA = (params.inputSymbol === symbolB && params.outputSymbol === symbolA);
    if (!isAtoB && !isBtoA) {
      throw new Error('Symbol mismatch for selected pool');
    }
    const inputAmount = parseFloat(params.inputAmount);
    // Choose reserves based on trade direction (input -> output)
    const reserveIn = isAtoB ? reserveA : reserveB;
    const reserveOut = isAtoB ? reserveB : reserveA;
    const feeRate = 0.003; // 0.30%
    const feeBps = feeRate * 10000;
    const feeAmount = (inputAmount * feeBps) / 10000;
    const inputAfterFee = inputAmount - feeAmount;
    const outputAmount = (inputAfterFee * reserveOut) / (reserveIn + inputAfterFee);
    const priceBefore = reserveOut / reserveIn;
    const priceAfter = (reserveOut - outputAmount) / (reserveIn + inputAmount);
    const priceImpact = Math.abs((priceAfter - priceBefore) / priceBefore) * 100;
    return {
      inputAmount,
      outputAmount,
      priceImpact,
      fee: feeAmount,
      route: [params.inputSymbol, params.outputSymbol],
      slippage: 0.5,
    };
  }

  // Helper: Generate idempotency key for swap
  private generateIdempotencyKey(): string {
    return `swap-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;
  }

  // Helper: Map backend pool DTO to frontend PoolInfo
  private mapPool(data: any): PoolInfo {
    // Legacy mapper (kept for compatibility if called elsewhere)
    const tokenA = data.tokenA || { symbol: data.symbolA, name: data.symbolA, decimals: 10 };
    const tokenB = data.tokenB || { symbol: data.symbolB, name: data.symbolB, decimals: 10 };
    const displayA = this.displaySymbol(tokenA.symbol);
    const displayB = this.displaySymbol(tokenB.symbol);
    return {
      contractId: data.poolId || data.contractId || '',
      poolId: data.poolId || data.contractId || '',
      tokenA: {
        symbol: displayA,
        name: displayA,
        decimals: tokenA.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(displayA),
      },
      tokenB: {
        symbol: displayB,
        name: displayB,
        decimals: tokenB.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(displayB),
      },
      reserveA: parseFloat(data.reserveA ?? data.reserveAmountA ?? 0),
      reserveB: parseFloat(data.reserveB ?? data.reserveAmountB ?? 0),
      totalLiquidity: parseFloat(data.totalLPSupply ?? data.lpSupply ?? 0),
      feeRate: data.feeRate ?? (data.feeBps ? Number(data.feeBps) / 10000 : 0.003),
      apr: 0,
      volume24h: parseFloat(data.volume24h ?? 0),
    };
  }

  private mapHoldingPool(row: any): PoolInfo {
    const symA = this.displaySymbol(row.instrumentASymbol || row.instrumentA?.id || 'A');
    const symB = this.displaySymbol(row.instrumentBSymbol || row.instrumentB?.id || 'B');
    const tokenA = { symbol: symA, name: symA, decimals: 10 };
    const tokenB = { symbol: symB, name: symB, decimals: 10 };
    const feeRate = row.feeBps ? Number(row.feeBps) / 10000 : 0.003;
    return {
      contractId: row.contractId || '',
      poolId: row.poolId || row.pool_id || '',
      tokenA: {
        symbol: tokenA.symbol,
        name: tokenA.name,
        decimals: tokenA.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenA.symbol),
      },
      tokenB: {
        symbol: tokenB.symbol,
        name: tokenB.name,
        decimals: tokenB.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenB.symbol),
      },
      reserveA: parseFloat(row.reserveAmountA ?? 0),
      reserveB: parseFloat(row.reserveAmountB ?? 0),
      totalLiquidity: parseFloat(row.lpSupply ?? 0),
      feeRate,
      apr: 0,
      volume24h: 0,
    };
  }

  private displaySymbol(symbol: string): string {
    return symbol === 'Amulet' ? 'CC' : symbol;
  }

  // Map party-scoped row to PoolInfo (row contains poolId, poolCid, symbolA/B, reserveA/B)
  private mapPartyPool(row: any): PoolInfo {
    const tokenA = { symbol: row.symbolA, name: row.symbolA, decimals: 10 };
    const tokenB = { symbol: row.symbolB, name: row.symbolB, decimals: 10 };
    return {
      // contractId keeps poolId for routing consistency in UI
      contractId: row.poolId || '',
      poolId: row.poolId || '',
      tokenA: {
        symbol: tokenA.symbol,
        name: tokenA.name,
        decimals: tokenA.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenA.symbol),
      },
      tokenB: {
        symbol: tokenB.symbol,
        name: tokenB.name,
        decimals: tokenB.decimals,
        balance: 0,
        contractId: '',
        logoUrl: this.getTokenLogo(tokenB.symbol),
      },
      reserveA: parseFloat(row.reserveA || '0'),
      reserveB: parseFloat(row.reserveB || '0'),
      totalLiquidity: parseFloat(row.totalLPSupply || row.totalLiquidity || '0'),
      feeRate: 0.003,
      apr: 0,
      volume24h: 0,
    };
  }
  // Helper: Map backend token DTO to frontend TokenInfo
  private mapToken(data: any): TokenInfo {
    return {
      symbol: data.symbol,
      name: data.name || data.symbol,
      decimals: data.decimals || 10,
      balance: parseFloat(data.amount || data.quantity || '0'),
      contractId: data.contractId || '',
      logoUrl: this.getTokenLogo(data.symbol),
    };
  }

  private aggregateTokensBySymbol(tokens: TokenInfo[]): TokenInfo[] {
    const tokenMap = new Map<string, TokenInfo>();
    for (const token of tokens) {
      const existing = tokenMap.get(token.symbol);
      if (existing) {
        existing.balance = (existing.balance || 0) + (token.balance || 0);
      } else {
        tokenMap.set(token.symbol, { ...token });
      }
    }
    return Array.from(tokenMap.values()).sort((a, b) => (b.balance || 0) - (a.balance || 0));
  }

  // Helper: Get token logo URL
  private getTokenLogo(symbol: string): string {
    const upper = symbol?.toUpperCase?.() || symbol;
    const basePath = `${process.env.PUBLIC_URL ?? ''}/tokens`;
    const logos: Record<string, string> = {
      CBTC: `${basePath}/cbtc.png`,
      CC: `${basePath}/cc.svg`,
      CANTON: `${basePath}/cc.svg`,
      ETH: 'https://cryptologos.cc/logos/ethereum-eth-logo.png',
      USDC: 'https://cryptologos.cc/logos/usd-coin-usdc-logo.png',
      BTC: 'https://cryptologos.cc/logos/bitcoin-btc-logo.png',
      USDT: 'https://cryptologos.cc/logos/tether-usdt-logo.png',
    };
    return logos[upper] || '';
  }

  async getTransactionHistory(): Promise<TransactionHistoryEntry[]> {
    try {
      const res = await this.client.get('/api/transactions/recent', { params: { limit: 1000 } });
      const rows = Array.isArray(res.data) ? res.data : [];
      if (!rows.length) {
        return FALLBACK_TRANSACTIONS;
      }
      return rows.map((row: any) => this.normalizeTransactionHistoryEntry(row));
    } catch (error) {
      console.warn('Falling back to demo transaction history payload:', error);
      return FALLBACK_TRANSACTIONS;
    }
  }

  private normalizeTransactionHistoryEntry(entry: any): TransactionHistoryEntry {
    const fallbackId = entry?.id || entry?.transactionId || `tx-${Date.now()}`;
    return {
      id: fallbackId,
      title: entry?.title || this.inferTransactionTitle(entry?.type),
      type: entry?.type || 'UNKNOWN',
      status: entry?.status || 'pending',
      createdAt: entry?.createdAt || entry?.timestamp || new Date().toISOString(),
      expiresAt: entry?.expiresAt,
      tokenA: entry?.tokenA || entry?.symbolA || '',
      tokenB: entry?.tokenB || entry?.symbolB || '',
      amountADesired: entry?.amountADesired?.toString() ?? entry?.amountA?.toString() ?? '0',
      amountBDesired: entry?.amountBDesired?.toString() ?? entry?.amountB?.toString() ?? '0',
      minLpAmount: entry?.minLpAmount?.toString() ?? entry?.minAmountLp?.toString(),
      lpTokenSymbol: entry?.lpTokenSymbol,
      lpMintedAmount: entry?.lpMintedAmount?.toString?.() ?? entry?.lpMintedAmount,
      contractId: entry?.contractId || entry?.cid || '',
      eventTimeline: Array.isArray(entry?.eventTimeline) && entry?.eventTimeline.length > 0
        ? entry.eventTimeline.map((item: any, index: number) => ({
            id: item?.id || `${fallbackId}-evt-${index}`,
            title: item?.title || 'Ledger Event',
            description: item?.description || '',
            status: item?.status || 'completed',
            timestamp: item?.timestamp,
          }))
        : [
            {
              id: `${fallbackId}-evt-0`,
              title: 'Ledger Event',
              description: 'Event recorded on Canton ledger.',
              status: 'completed',
              timestamp: entry?.createdAt || new Date().toISOString(),
            },
          ],
    };
  }

  private inferTransactionTitle(type: TransactionHistoryEntry['type'] | undefined): string {
    switch (type) {
      case 'ADD_LIQUIDITY':
        return 'AddLiquidity Transaction';
      case 'SWAP':
        return 'Swap Transaction';
      case 'POOL_CREATION':
        return 'Pool Creation Transaction';
      case 'TOKEN_MINT':
        return 'Token Mint Transaction';
      default:
        return 'Ledger Transaction';
    }
  }

  /**
   * Select a CBTC/Token Standard holding with polling support.
   * Uses the backend's HoldingSelectorService for UTXO selection.
   *
   * @param params Selection criteria
   * @returns Selected holding info or null if not found within timeout
   */
  async selectHolding(params: {
    ownerParty: string;
    instrumentAdmin: string;
    instrumentId: string;
    minAmount?: string;
    timeoutSeconds?: number;
    pollIntervalMs?: number;
  }): Promise<{
    found: boolean;
    holdingCid: string | null;
    instrumentAdmin: string | null;
    instrumentId: string | null;
    amount: string | null;
    owner: string | null;
    attempts: number;
    elapsedMs: number;
    error: string | null;
  }> {
    try {
      const body = {
        ownerParty: params.ownerParty,
        instrumentAdmin: params.instrumentAdmin,
        instrumentId: params.instrumentId,
        minAmount: params.minAmount || "0",
        timeoutSeconds: params.timeoutSeconds ?? 30,
        pollIntervalMs: params.pollIntervalMs ?? 2000,
      };

      const res = await this.requestWithFallback('/api/holdings/select', '/api/devnet/holdings/select', {
        method: 'post',
        data: body,
      });
      const data = res.data;

      return {
        found: data.found === true,
        holdingCid: data.holdingCid || null,
        instrumentAdmin: data.instrumentAdmin || null,
        instrumentId: data.instrumentId || null,
        amount: data.amount?.toString() || null,
        owner: data.owner || null,
        attempts: data.attempts || 1,
        elapsedMs: data.elapsedMs || 0,
        error: data.error || null,
      };
    } catch (error: any) {
      const message = error?.response?.data?.message || error?.message || String(error);
      console.error("[BackendApi] Holding selection failed:", message);
      return {
        found: false,
        holdingCid: null,
        instrumentAdmin: null,
        instrumentId: null,
        amount: null,
        owner: null,
        attempts: 1,
        elapsedMs: 0,
        error: message,
      };
    }
  }

  /**
   * Get incoming CBTC TransferOffers for a receiver party.
   * These are offers that can be accepted via Loop SDK.
   */
  async getCbtcOffers(receiverParty: string): Promise<Array<{
    contractId: string;
    sender: string;
    receiver: string;
    amount: string;
    reason: string | null;
    executeBefore: string | null;
    instrumentId: string;
    instrumentAdmin: string;
    rawTemplateId: string;
    transferInstructionId?: string | null;
    packageId?: string | null;
  }>> {
    try {
      const res = await this.requestWithFallback('/api/cbtc/offers', '/api/devnet/cbtc/offers', {
        method: 'get',
        params: { receiverParty },
      });
      const offers = Array.isArray(res.data) ? res.data : [];
      return offers.map((o: any) => ({
        contractId: o.contractId || "",
        sender: o.sender || "",
        receiver: o.receiver || "",
        amount: o.amount?.toString() || "0",
        reason: o.reason || null,
        executeBefore: o.executeBefore || null,
        instrumentId: o.instrumentId || "",
        instrumentAdmin: o.instrumentAdmin || "",
        rawTemplateId: o.rawTemplateId || "",
        transferInstructionId: o.transferInstructionId || null,
        packageId: o.packageId || null,
      }));
    } catch (error) {
      console.error("[BackendApi] Failed to get CBTC offers:", error);
      return [];
    }
  }

  /**
   * Accept a CBTC offer via backend registry-powered acceptance.
   */
  async acceptCbtcOffer(offerCid: string, actAsParty?: string): Promise<{
    requestId: string;
    offerCid: string;
    transferInstructionId?: string | null;
    actAsParty: string;
    ok: boolean;
    classification?: string | null;
    rawError?: string | null;
    hint?: string | null;
    ledgerUpdateId?: string | null;
  }> {
    try {
      const res = await this.requestWithFallback(`/api/cbtc/offers/${offerCid}/accept`, `/api/devnet/cbtc/offers/${offerCid}/accept`, {
        method: 'post',
        data: { actAsParty },
      });
      return res.data;
    } catch (error: any) {
      const message = error?.response?.data?.message || error?.message || String(error);
      console.error("[BackendApi] CBTC accept failed:", message);
      return {
        requestId: "unknown",
        offerCid,
        transferInstructionId: null,
        actAsParty: actAsParty || "",
        ok: false,
        classification: "NETWORK",
        rawError: message,
        hint: "Backend accept endpoint failed",
        ledgerUpdateId: null,
      };
    }
  }

  /**
   * Get CBTC holdings (UTXOs) for a party.
   * Useful for discovering available CBTC before/after acceptance.
   */
  async getCbtcHoldings(party: string): Promise<Array<{
    contractId: string;
    instrumentAdmin: string;
    instrumentId: string;
    amount: string;
    owner: string;
  }>> {
    try {
      const encodedParty = encodeURIComponent(party);
      const res = await this.client.get(`/api/holdings/${encodedParty}/utxos`);
      const utxos = Array.isArray(res.data) ? res.data : [];

      // Filter for CBTC holdings (instrumentId = "CBTC")
      return utxos
        .filter((u: any) => u.instrumentId === "CBTC")
        .map((u: any) => ({
          contractId: u.contractId || "",
          instrumentAdmin: u.instrumentAdmin || "",
          instrumentId: u.instrumentId || "",
          amount: u.amount?.toString() || "0",
          owner: u.owner || "",
        }));
    } catch (error) {
      console.error("[BackendApi] Failed to get CBTC holdings:", error);
      return [];
    }
  }

  async createDevnetPayout(
    instrument: 'amulet' | 'cbtc',
    payload: {
      receiverParty: string;
      amount: string;
      executeBeforeSeconds?: number;
      memo?: string;
    }
  ): Promise<any> {
    try {
      const res = await this.requestWithFallback(`/api/payout/${instrument}`, `/api/devnet/payout/${instrument}`, {
        method: 'post',
        data: payload,
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async getHoldingUtxos(party: string, ownerOnly = true): Promise<any> {
    try {
      const encodedParty = encodeURIComponent(party);
      const res = await this.client.get(`/api/holdings/${encodedParty}/utxos`, {
        params: { ownerOnly },
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async getOutgoingTransferInstructions(params: {
    senderParty: string;
    instrumentAdmin: string;
    instrumentId: string;
  }): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/transfer-instructions/outgoing', '/api/devnet/transfer-instructions/outgoing', {
        method: 'get',
        params,
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async consumeDevnetSwap(payload: {
    requestId: string;
    maxAgeSeconds?: number;
  }): Promise<any> {
    const maxAttempts = 3;
    let lastResponse: any = null;

    const shouldRetry = (data: any) => {
      const error = data?.error;
      if (!error) return false;
      const code = error.code;
      const message = String(error.message || '');
      const field = error.details?.field;
      const memoIssue = /memo/i.test(message) || field === 'memo';
      const missingInbound = /inbound.*transferinstruction|no inbound ti|requestId/i.test(message);
      return (
        (code === 'NOT_FOUND' && missingInbound) ||
        (code === 'PRECONDITION_FAILED' && missingInbound) ||
        (code === 'VALIDATION' && memoIssue)
      );
    };

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      try {
        const res = await this.requestWithFallback('/api/swap/consume', '/api/devnet/swap/consume', {
          method: 'post',
          data: payload,
        });
        if (!shouldRetry(res.data)) {
          return res.data;
        }
        lastResponse = res.data;
      } catch (error: any) {
        const data = error?.response?.data;
        if (!shouldRetry(data)) {
          const message = data?.error?.message || error?.message || String(error);
          return data || { ok: false, error: { message } };
        }
        lastResponse = data;
      }

      if (attempt < maxAttempts) {
        await this.sleep(1200 * attempt);
      }
    }

    return lastResponse || { ok: false, error: { message: 'Swap consume retry exhausted' } };
  }

  async consumeDevnetLiquidity(payload: {
    requestId: string;
    poolCid: string;
    maxAgeSeconds?: number;
  }): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/liquidity/add/consume', '/api/devnet/liquidity/consume', {
        method: 'post',
        data: payload,
        timeout: 90000,
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async consumeDevnetLiquidityRemove(payload: {
    requestId: string;
    poolCid?: string;
    lpCid: string;
    receiverParty: string;
    lpBurnAmount: string;
    minOutA?: string;
    minOutB?: string;
    deadlineIso?: string;
  }): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/liquidity/remove/consume', '/api/devnet/liquidity/remove/consume', {
        method: 'post',
        data: payload,
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async inspectDevnetLiquidityRemove(params: {
    requestId: string;
    poolCid?: string;
    lpCid: string;
    receiverParty: string;
    lpBurnAmount?: string;
  }): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/liquidity/remove/inspect', '/api/devnet/liquidity/remove/inspect', {
        method: 'get',
        params,
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async inspectDevnetLiquidity(requestId: string, poolCid?: string): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/liquidity/add/inspect', '/api/devnet/liquidity/inspect', {
        method: 'get',
        params: poolCid ? { requestId, poolCid } : { requestId },
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async getDevnetSwapTransferInstruction(requestId: string): Promise<any> {
    try {
      const res = await this.client.get('/api/devnet/swap/transfer-instruction', {
        params: { requestId },
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async getDevnetSwapIntent(requestId: string): Promise<any> {
    try {
      const res = await this.client.get('/api/devnet/swap/intent', {
        params: { requestId },
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }

  async inspectDevnetSwap(requestId: string): Promise<any> {
    try {
      const res = await this.requestWithFallback('/api/swap/inspect', '/api/devnet/swap/inspect', {
        method: 'get',
        params: { requestId },
      });
      return res.data;
    } catch (error: any) {
      const data = error?.response?.data;
      const message = data?.error?.message || error?.message || String(error);
      return data || { ok: false, error: { message } };
    }
  }
}

// Export singleton instance
export const backendApi = new BackendApiService();
