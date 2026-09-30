# Phase 3: Frontend-Backend Integration Complete

## Summary

Phase 3 successfully integrated the ClearportX React frontend with the Canton backend running on production server (devnet-host.example).

## What Was Completed

### 1. TypeScript Code Generation ✅
- Generated TypeScript bindings from `clearportx-fees-1.0.0.dar`
- Location: `src/daml-codegen/`
- Package ID: `7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1`

### 2. Real Canton API Service ✅
- **File**: `src/services/realCantonApi.ts`
- **Features**:
  - HTTP JSON API v2 integration
  - Off-chain AMM calculations (instant quotes, no gas cost)
  - Token and pool queries
  - Swap execution
  - Liquidity management
  - JWT authentication support

### 3. ContractId Cache (Zustand) ✅
- **File**: `src/stores/useContractStore.ts`
- **Purpose**: Cache contract IDs to avoid repeated queries
- **Features**:
  - Token cache by symbol
  - Pool cache by token pair
  - 30-second TTL with staleness detection
  - Loading state management

### 4. Configuration Updates ✅
- **File**: `src/config/canton.ts`
- Updated with:
  - Correct package ID
  - Template IDs for all contracts
  - Fee configuration (matches DAML)
  - Production/development endpoints

### 5. Environment Configuration ✅
- **Production** (`.env.production`):
  - API URL: `https://api.clearportx.com`
  - Source maps disabled

- **Development** (`.env.development`):
  - API URL: `http://localhost:2975`
  - Hot reload enabled

## Architecture

### ContractId-Only Pattern
Following DAML 3.3.0 best practices:
- **Off-chain queries**: Frontend queries contracts via HTTP JSON API
- **Cached ContractIds**: Zustand store caches IDs for 30 seconds
- **Explicit CIDs in choices**: Pass ContractIds directly to DAML choices
- **No query in choices**: All DAML choices are ContractId-only

### Off-Chain Quote Calculation
Swap quotes are calculated in the frontend:
```typescript
const result = AMMCalculator.calculateSwapOutput(
  inputAmount,
  reserveIn,
  reserveOut,
  feeRateBps
);
```

**Benefits**:
- Instant quotes (no blockchain call)
- No gas cost
- Real-time price updates

### Two-Step Swap Flow
1. **PrepareSwap**: Create SwapRequest with locked inputs
2. **ExecuteSwap**: Pool exercises swap, returns outputs

## API Endpoints

### Production
- **Base URL**: `https://api.clearportx.com`
- **Health**: `https://api.clearportx.com/v2/version`
- **OpenAPI Docs**: `https://api.clearportx.com/docs/openapi`

### Key Endpoints
- `POST /v2/query` - Query contracts by template
- `POST /v2/commands/submit-and-wait` - Execute DAML choices
- `GET /v2/version` - Health check

## Smart Contract Structure

### Package: clearportx-fees-1.0.0
**Package ID**: `7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1`

#### Templates
1. **Token.Token** - Fungible tokens with TransferSplit
2. **AMM.Pool** - Liquidity pool with protocol fees
3. **LPToken.LPToken** - LP token ownership
4. **AMM.SwapRequest** - Two-step swap mechanism
5. **AMM.ProtocolFees** - Fee accumulation (25% to protocol)

#### Protocol Fees
- **Trading fee**: 0.3% (30 bps)
- **Protocol split**: 25% of fee → 0.075% total
- **LP split**: 75% of fee → 0.225% total
- **Max price impact**: 50% (5000 bps)

## Usage Examples

### 1. Initialize API
```typescript
import { realCantonApi } from './services/realCantonApi';

// Check health
const isHealthy = await realCantonApi.healthCheck();

// Set auth token (for transactions)
realCantonApi.setToken('your-jwt-token');
```

### 2. Query Tokens
```typescript
const tokens = await realCantonApi.getTokens();
// Returns: TokenInfo[] with contractIds
```

### 3. Query Pools
```typescript
const pools = await realCantonApi.getPools();
// Returns: PoolInfo[] with contractIds and reserves
```

### 4. Calculate Swap Quote (Off-chain)
```typescript
const quote = await realCantonApi.calculateSwapQuote(
  inputToken,
  outputToken,
  inputAmount
);

// Returns:
// {
//   inputAmount: 100,
//   outputAmount: 49.85,
//   priceImpact: 0.15,
//   fee: 0.3,
//   route: ['USDC', 'ETH'],
//   slippage: 0.5
// }
```

### 5. Execute Swap
```typescript
const result = await realCantonApi.executeSwap(
  poolContractId,
  inputTokenContractId,
  inputAmount,
  minOutputAmount
);

// Returns:
// {
//   success: true,
//   transactionId: '...'
// }
```

### 6. Use Contract Cache
```typescript
import { useContractStore } from './stores/useContractStore';

function SwapComponent() {
  const getTokenBySymbol = useContractStore(state => state.getTokenBySymbol);
  const getPoolByPair = useContractStore(state => state.getPoolByPair);

  // Get cached token
  const usdcToken = getTokenBySymbol('USDC');

  // Get cached pool
  const pool = getPoolByPair('USDC', 'ETH');

  return <div>...</div>;
}
```

## Server Infrastructure

### Production Server
- **IP**: devnet-host.example
- **Domain**: api.clearportx.com
- **SSL**: Let's Encrypt (expires 2026-01-07)
- **Nginx**: Reverse proxy to Canton JSON API
- **Canton**: HTTP JSON API v2 on port 2975

### Backend Stack
```
User → HTTPS → Nginx (443) → Canton HTTP JSON API (2975) → Canton Node
```

## Next Steps for Full Integration

### Frontend Changes Needed
1. **Replace mock API** with real API in components
2. **Add authentication** (JWT token management)
3. **Handle transaction states** (pending, confirmed, failed)
4. **Add error handling** for network/blockchain errors
5. **Implement wallet connection** (if using external wallets)

### Example Component Update
```typescript
// Before (mock)
import { mockCantonApi } from './services/mockCantonApi';

// After (real)
import { realCantonApi } from './services/realCantonApi';

// Usage stays the same!
const tokens = await realCantonApi.getTokens();
```

### Authentication Setup
```typescript
// 1. User logs in / connects wallet
const authToken = await loginUser(credentials);

// 2. Set token in API
realCantonApi.setToken(authToken);

// 3. Now transactions will work
const result = await realCantonApi.executeSwap(...);
```

### Load Contracts on App Start
```typescript
// App.tsx or useEffect
useEffect(() => {
  async function loadContracts() {
    const tokens = await realCantonApi.getTokens();
    const pools = await realCantonApi.getPools();

    useContractStore.getState().setTokens(tokens);
    useContractStore.getState().setPools(pools);
  }

  loadContracts();
}, []);
```

## Testing

### Health Check
```bash
curl https://api.clearportx.com/v2/version
# Should return: {"version":"3.3.0-SNAPSHOT",...}
```

### Local Development
```bash
# Start Canton locally (if testing against local node)
cd /root/cn-quickstart/quickstart
docker-compose up

# Start React app
cd /root/canton-website/app
npm start
```

### Production Build
```bash
cd /root/canton-website/app
npm run build
# Outputs to build/ directory
```

## Troubleshooting

### "Authentication required" error
- Set JWT token: `realCantonApi.setToken(token)`
- Verify token is valid and not expired

### "Contract not found" error
- Refresh cache: `useContractStore.getState().clearCache()`
- Verify contracts exist on Canton node

### Network errors
- Check Canton health: `curl https://api.clearportx.com/v2/version`
- Verify firewall allows port 443
- Check Nginx logs: `docker logs nginx`

### CORS errors
- Verify Nginx CORS headers are set
- Check browser console for specific CORS policy

## File Structure

```
app/
├── src/
│   ├── config/
│   │   └── canton.ts              # Canton configuration
│   ├── services/
│   │   ├── realCantonApi.ts       # Real API service (NEW)
│   │   ├── mockCantonApi.ts       # Mock API (for testing)
│   │   └── index.ts               # Service exports
│   ├── stores/
│   │   ├── useContractStore.ts    # ContractId cache (NEW)
│   │   └── useAppStore.ts         # App state
│   ├── daml-codegen/              # Generated TypeScript bindings (NEW)
│   │   └── clearportx-fees-1.0.0/
│   └── types/
│       └── canton.ts              # TypeScript types
├── .env.production                # Production env vars (NEW)
├── .env.development               # Development env vars (NEW)
└── PHASE3_INTEGRATION.md          # This file (NEW)
```

## Summary

✅ Backend deployed and running (devnet-host.example)
✅ SSL certificate configured (api.clearportx.com)
✅ DAR uploaded to Canton (clearportx-fees-1.0.0)
✅ TypeScript bindings generated
✅ Real API service implemented
✅ ContractId cache created
✅ Configuration updated
✅ Environment variables set

**Status**: Frontend integration framework complete. Ready for component-level integration.

**API URL**: https://api.clearportx.com
**Package ID**: 7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1
