import { PoolInfo, SwapQuote } from "../types/canton";

const DEFAULT_FEE_RATE = 0.003;

export function calculateSwapQuoteFromPool(
  pool: PoolInfo | null | undefined,
  inputSymbol: string | undefined,
  outputSymbol: string | undefined,
  rawAmountIn: number
): SwapQuote | null {
  if (!pool || !inputSymbol || !outputSymbol) {
    return null;
  }
  if (!Number.isFinite(rawAmountIn) || rawAmountIn <= 0) {
    return null;
  }

  const fromSymbol = inputSymbol.toUpperCase();
  const toSymbol = outputSymbol.toUpperCase();
  const symbolA = pool.tokenA.symbol?.toUpperCase?.() ?? "";
  const symbolB = pool.tokenB.symbol?.toUpperCase?.() ?? "";

  let reserveIn: number | null = null;
  let reserveOut: number | null = null;

  if (fromSymbol === symbolA && toSymbol === symbolB) {
    reserveIn = pool.reserveA;
    reserveOut = pool.reserveB;
  } else if (fromSymbol === symbolB && toSymbol === symbolA) {
    reserveIn = pool.reserveB;
    reserveOut = pool.reserveA;
  } else {
    return null;
  }

  if (
    reserveIn === null ||
    reserveOut === null ||
    !Number.isFinite(reserveIn) ||
    !Number.isFinite(reserveOut) ||
    reserveIn <= 0 ||
    reserveOut <= 0
  ) {
    return null;
  }

  const feeRate = Number.isFinite(pool.feeRate) ? pool.feeRate : DEFAULT_FEE_RATE;
  const feeAmount = rawAmountIn * (feeRate ?? DEFAULT_FEE_RATE);
  const amountAfterFee = rawAmountIn - feeAmount;
  if (!Number.isFinite(amountAfterFee) || amountAfterFee <= 0) {
    return null;
  }

  const outputAmount = (amountAfterFee * reserveOut) / (reserveIn + amountAfterFee);
  const priceBefore = reserveOut / reserveIn;
  const priceAfter = (reserveOut - outputAmount) / (reserveIn + amountAfterFee);
  const priceImpact =
    priceBefore > 0
      ? Math.abs((priceAfter - priceBefore) / priceBefore) * 100
      : 0;

  return {
    inputAmount: rawAmountIn,
    outputAmount: Number.isFinite(outputAmount) ? outputAmount : 0,
    priceImpact: Number.isFinite(priceImpact) ? priceImpact : 0,
    fee: Number.isFinite(feeAmount) ? feeAmount : 0,
    route: [fromSymbol, toSymbol],
    slippage: 0.5,
  };
}
