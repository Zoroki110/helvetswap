import React, { useState, useCallback, useEffect } from 'react';
import { backendApi } from '../services/backendApi';
import { LoopWalletConnector } from '../wallet/LoopWalletConnector';

// CBTC network admin party on DevNet
const CBTC_INSTRUMENT_ADMIN = 'cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff';
const CBTC_INSTRUMENT_ID = 'CBTC';

// ClearportX operator party
const CLEARPORTX_OPERATOR = 'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';

// Singleton Loop connector instance
let loopConnector: LoopWalletConnector | null = null;
function getLoopConnector(): LoopWalletConnector {
  if (!loopConnector) {
    loopConnector = new LoopWalletConnector();
    LoopWalletConnector.initOnce();
  }
  return loopConnector;
}

interface AcceptResult {
  ok: boolean;
  ledgerUpdateId?: string | null;
  holdingCid?: string;
  amount?: string;
  error?: string | null;
  hint?: string | null;
  classification?: string | null;
  elapsedMs?: number;
  requestId?: string;
  transferInstructionId?: string | null;
}

interface CbtcHolding {
  contractId: string;
  amount: string;
  owner: string;
}

interface CbtcOffer {
  contractId: string;
  sender: string;
  receiver: string;
  amount: string;
  reason: string | null;
  instrumentId: string;
  transferInstructionId?: string | null;
  packageId?: string | null;
}

/**
 * DevNet-only UI component for accepting incoming CBTC offers.
 *
 * Option A Flow:
 * 1. User pastes TransferOffer contractId
 * 2. Backend fetches registry choice-context and exercises TransferInstruction_Accept as ClearportX
 * 3. Acceptance creates CBTC Holding owned by ClearportX
 * 4. Backend polls for the new holding using HoldingSelectorService
 */
export default function DevNetCbtcAccept() {
  const [transferOfferCid, setTransferOfferCid] = useState('');
  const [isAccepting, setIsAccepting] = useState(false);
  const [isPolling, setIsPolling] = useState(false);
  const [result, setResult] = useState<AcceptResult | null>(null);
  const [existingHoldings, setExistingHoldings] = useState<CbtcHolding[]>([]);
  const [isLoadingHoldings, setIsLoadingHoldings] = useState(false);
  const [incomingOffers, setIncomingOffers] = useState<CbtcOffer[]>([]);
  const [isLoadingOffers, setIsLoadingOffers] = useState(false);
  const [acceptingOfferId, setAcceptingOfferId] = useState<string | null>(null);
  const [useLoopSdk, setUseLoopSdk] = useState(true); // Default to Loop SDK
  const [loopConnected, setLoopConnected] = useState(false);
  const [loopConnecting, setLoopConnecting] = useState(false);

  // Load incoming CBTC offers for ClearportX
  const loadIncomingOffers = useCallback(async () => {
    setIsLoadingOffers(true);
    try {
      const offers = await backendApi.getCbtcOffers(CLEARPORTX_OPERATOR);
      setIncomingOffers(offers.map(o => ({
        contractId: o.contractId,
        sender: o.sender,
        receiver: o.receiver,
        amount: o.amount,
        reason: o.reason,
        instrumentId: o.instrumentId,
        transferInstructionId: o.transferInstructionId,
        packageId: o.packageId,
      })));
    } catch (err) {
      console.error('[DevNetCbtcAccept] Failed to load offers:', err);
    } finally {
      setIsLoadingOffers(false);
    }
  }, []);

  // Load existing CBTC holdings for ClearportX
  const loadExistingHoldings = useCallback(async () => {
    setIsLoadingHoldings(true);
    try {
      const holdings = await backendApi.getCbtcHoldings(CLEARPORTX_OPERATOR);
      setExistingHoldings(holdings.map(h => ({
        contractId: h.contractId,
        amount: h.amount,
        owner: h.owner,
      })));
    } catch (err) {
      console.error('Failed to load existing holdings:', err);
    } finally {
      setIsLoadingHoldings(false);
    }
  }, []);

  // Check Loop connection status on mount
  useEffect(() => {
    const checkLoop = async () => {
      if (useLoopSdk) {
        const connector = getLoopConnector();
        const status = await connector.checkConnected();
        setLoopConnected(status.connected);
      }
    };
    checkLoop();
  }, [useLoopSdk]);

  // Auto-load offers and holdings on mount
  useEffect(() => {
    loadIncomingOffers();
    loadExistingHoldings();
  }, [loadIncomingOffers, loadExistingHoldings]);

  // Connect to Loop wallet
  const connectLoop = useCallback(async () => {
    setLoopConnecting(true);
    try {
      const connector = getLoopConnector();
      await connector.connectFromClick(); // Must be called from user click
      const status = await connector.checkConnected();
      setLoopConnected(status.connected);
      if (!status.connected) {
        setResult({
          ok: false,
          error: 'Loop connection failed. Please allow popups and try again.',
        });
      }
    } catch (err: any) {
      console.error('[DevNetCbtcAccept] Loop connection error:', err);
      setResult({
        ok: false,
        error: err?.message || 'Failed to connect Loop wallet',
      });
      setLoopConnected(false);
    } finally {
      setLoopConnecting(false);
    }
  }, []);

  // Accept CBTC offer via Loop SDK
  const acceptOfferViaLoop = useCallback(async (offer: CbtcOffer) => {
    const requestId = `loop-accept-${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;

    if (!offer.transferInstructionId) {
      setResult({
        ok: false,
        error: 'Missing transferInstructionId - cannot accept via Loop SDK',
        hint: 'This offer may be from an older version. Try backend acceptance.',
      });
      return;
    }

    setIsAccepting(true);
    setAcceptingOfferId(offer.contractId);
    setResult(null);

    try {
      const connector = getLoopConnector();

      // Ensure connected
      const connStatus = await connector.ensureConnected(requestId);
      if (!connStatus.connected) {
        setResult({
          ok: false,
          error: 'Loop wallet not connected',
          hint: 'Click "Connect Loop Wallet" button first',
        });
        setIsAccepting(false);
        setAcceptingOfferId(null);
        return;
      }

      const acceptResult = await connector.acceptIncomingCbtcOffer({
        transferInstructionCid: offer.transferInstructionId,
        receiverParty: CLEARPORTX_OPERATOR,
        packageId: offer.packageId,
      });

      if (!acceptResult.success || !acceptResult.updateId) {
        setResult({
          ok: false,
          error: acceptResult.error || 'Loop SDK acceptance failed',
          hint: 'Check browser console for details',
          requestId,
        });
        setIsAccepting(false);
        setAcceptingOfferId(null);
        return;
      }

      // Poll backend for the new holding
      setIsAccepting(false);
      setIsPolling(true);

      const holdingResult = await backendApi.selectHolding({
        ownerParty: CLEARPORTX_OPERATOR,
        instrumentAdmin: CBTC_INSTRUMENT_ADMIN,
        instrumentId: CBTC_INSTRUMENT_ID,
        minAmount: '0',
        timeoutSeconds: 30,
        pollIntervalMs: 2000,
      });

      setIsPolling(false);
      setAcceptingOfferId(null);

      if (holdingResult.found) {
        setResult({
          ok: true,
          ledgerUpdateId: acceptResult.updateId,
          holdingCid: holdingResult.holdingCid || undefined,
          amount: holdingResult.amount || undefined,
          elapsedMs: holdingResult.elapsedMs,
          requestId,
          transferInstructionId: offer.transferInstructionId,
        });

        // Refresh lists
        await loadIncomingOffers();
        await loadExistingHoldings();
      } else {
        console.warn(`[DevNetCbtcAccept] request_id=${requestId} Holding not found after acceptance`);
        setResult({
          ok: false,
          ledgerUpdateId: acceptResult.updateId,
          error: holdingResult.error || 'Holding not found after acceptance',
          elapsedMs: holdingResult.elapsedMs,
          requestId,
          transferInstructionId: offer.transferInstructionId,
        });
      }
    } catch (err: any) {
      const errorMsg = err?.message || String(err);
      console.error(`[DevNetCbtcAccept] request_id=${requestId} Loop SDK exception:`, errorMsg, err);
      setResult({
        ok: false,
        error: errorMsg,
        requestId,
      });
      setIsAccepting(false);
      setIsPolling(false);
      setAcceptingOfferId(null);
    }
  }, [loadIncomingOffers, loadExistingHoldings]);

  // Accept CBTC offer via backend registry-powered endpoint
  const acceptOffer = useCallback(async (offerCid: string) => {
    const requestId = `accept-${Date.now()}-${Math.random().toString(36).substr(2, 9)}`;

    setIsAccepting(true);
    setAcceptingOfferId(offerCid);
    setResult(null);

    try {
      const acceptResult = await backendApi.acceptCbtcOffer(offerCid, CLEARPORTX_OPERATOR);

      if (!acceptResult.ok) {
        setResult({
          ok: false,
          error: acceptResult.rawError || 'Acceptance failed',
          hint: acceptResult.hint,
          classification: acceptResult.classification,
          requestId: acceptResult.requestId || requestId,
          transferInstructionId: acceptResult.transferInstructionId,
        });
        setIsAccepting(false);
        setAcceptingOfferId(null);
        return;
      }

      // Step 2: Poll backend for the new holding
      setIsAccepting(false);
      setIsPolling(true);

      const holdingResult = await backendApi.selectHolding({
        ownerParty: CLEARPORTX_OPERATOR,
        instrumentAdmin: CBTC_INSTRUMENT_ADMIN,
        instrumentId: CBTC_INSTRUMENT_ID,
        minAmount: '0',
        timeoutSeconds: 30,
        pollIntervalMs: 2000,
      });

      setIsPolling(false);
      setAcceptingOfferId(null);

      if (holdingResult.found) {
        setResult({
          ok: true,
          ledgerUpdateId: acceptResult.ledgerUpdateId || undefined,
          holdingCid: holdingResult.holdingCid || undefined,
          amount: holdingResult.amount || undefined,
          elapsedMs: holdingResult.elapsedMs,
          requestId: acceptResult.requestId || requestId,
          transferInstructionId: acceptResult.transferInstructionId,
        });

        // Refresh offers and holdings lists
        await loadIncomingOffers();
        await loadExistingHoldings();
      } else {
        console.warn(`[DevNetCbtcAccept] request_id=${requestId} Holding not found after acceptance`);
        setResult({
          ok: false,
          ledgerUpdateId: acceptResult.ledgerUpdateId || undefined,
          error: holdingResult.error || 'Holding not found after acceptance',
          elapsedMs: holdingResult.elapsedMs,
          requestId: acceptResult.requestId || requestId,
          transferInstructionId: acceptResult.transferInstructionId,
        });
      }
    } catch (err: any) {
      const errorMsg = err?.message || String(err);
      console.error(`[DevNetCbtcAccept] request_id=${requestId} Exception:`, errorMsg, err);
      setResult({
        ok: false,
        error: errorMsg,
        requestId,
      });
      setIsAccepting(false);
      setIsPolling(false);
      setAcceptingOfferId(null);
    }
  }, [loadIncomingOffers, loadExistingHoldings]);

  // Handle manual accept from input field
  const handleAccept = useCallback(async () => {
    if (!transferOfferCid.trim()) {
      setResult({ ok: false, error: 'Please enter a TransferOffer contract ID' });
      return;
    }
    await acceptOffer(transferOfferCid.trim());
  }, [transferOfferCid, acceptOffer]);

  // Handle one-click accept from offers list
  const handleAcceptFromList = useCallback(async (offer: CbtcOffer) => {
    if (useLoopSdk) {
      await acceptOfferViaLoop(offer);
    } else {
      await acceptOffer(offer.contractId);
    }
  }, [useLoopSdk, acceptOfferViaLoop, acceptOffer]);

  // Truncate contract ID for display
  const truncateCid = (cid: string) => {
    if (!cid || cid.length <= 24) return cid;
    return `${cid.substring(0, 12)}...${cid.substring(cid.length - 8)}`;
  };

  return (
    <div className="bg-white dark:bg-dark-800 rounded-2xl shadow-lg p-6 border border-gray-200 dark:border-dark-700">
      <div className="flex items-center justify-between mb-6">
        <h2 className="text-xl font-bold text-gray-900 dark:text-white">
          CBTC Accept (DevNet)
        </h2>
        <span className="px-2 py-1 text-xs font-medium bg-orange-100 text-orange-800 dark:bg-orange-900 dark:text-orange-200 rounded-full">
          Option A
        </span>
      </div>

      <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
        Accept incoming CBTC TransferOffers via backend registry choice-context or Loop SDK. The acceptance creates a
        CBTC Holding owned by ClearportX operator.
      </p>

      {/* Mode Selector and Loop Controls */}
      <div className="mb-6 p-4 bg-gray-50 dark:bg-dark-700 rounded-xl border border-gray-200 dark:border-dark-600">
        <div className="flex items-center justify-between mb-3">
          <label className="text-sm font-medium text-gray-700 dark:text-gray-300">
            Acceptance Mode:
          </label>
          <div className="flex items-center space-x-2">
            <button
              onClick={() => setUseLoopSdk(false)}
              className={`px-4 py-2 text-sm font-medium rounded-lg transition-all ${
                !useLoopSdk
                  ? 'bg-blue-500 text-white shadow'
                  : 'bg-white dark:bg-dark-600 text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-dark-500'
              }`}
            >
              Backend
            </button>
            <button
              onClick={() => setUseLoopSdk(true)}
              className={`px-4 py-2 text-sm font-medium rounded-lg transition-all ${
                useLoopSdk
                  ? 'bg-purple-500 text-white shadow'
                  : 'bg-white dark:bg-dark-600 text-gray-700 dark:text-gray-300 border border-gray-300 dark:border-dark-500'
              }`}
            >
              Loop SDK
            </button>
          </div>
        </div>

        {/* Loop SDK Connection Status */}
        {useLoopSdk && (
          <div className="mt-3 pt-3 border-t border-gray-200 dark:border-dark-600">
            <div className="flex items-center justify-between">
              <div className="flex items-center space-x-2">
                <div className={`w-2 h-2 rounded-full ${loopConnected ? 'bg-green-500' : 'bg-gray-400'}`}></div>
                <span className="text-sm text-gray-600 dark:text-gray-400">
                  {loopConnected ? 'Loop Wallet Connected' : 'Loop Wallet Not Connected'}
                </span>
              </div>
              {!loopConnected && (
                <button
                  onClick={connectLoop}
                  disabled={loopConnecting}
                  className={`px-4 py-2 text-sm font-medium rounded-lg transition-all ${
                    loopConnecting
                      ? 'bg-gray-400 text-white cursor-not-allowed'
                      : 'bg-purple-500 text-white hover:bg-purple-600 shadow hover:shadow-lg'
                  }`}
                >
                  {loopConnecting ? 'Connecting...' : 'Connect Loop'}
                </button>
              )}
            </div>
          </div>
        )}
      </div>

      {/* Input Section */}
      <div className="space-y-4">
        <div>
          <label className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
            TransferOffer Contract ID
          </label>
          <input
            type="text"
            value={transferOfferCid}
            onChange={(e) => setTransferOfferCid(e.target.value)}
            placeholder="Paste the TransferOffer contractId here..."
            className="w-full px-4 py-3 bg-gray-50 dark:bg-dark-700 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white placeholder-gray-400 dark:placeholder-gray-500 focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all"
            disabled={isAccepting || isPolling}
          />
        </div>

        <button
          onClick={handleAccept}
          disabled={isAccepting || isPolling || !transferOfferCid.trim()}
          className={`w-full py-3 px-4 rounded-xl font-semibold text-white transition-all ${
            isAccepting || isPolling || !transferOfferCid.trim()
              ? 'bg-gray-400 cursor-not-allowed'
              : 'bg-gradient-to-r from-blue-500 to-purple-600 hover:from-blue-600 hover:to-purple-700 shadow-lg hover:shadow-xl'
          }`}
        >
          {isAccepting ? (
            <span className="flex items-center justify-center">
              <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              {useLoopSdk ? 'Accepting via Loop SDK...' : 'Accepting via backend...'}
            </span>
          ) : isPolling ? (
            <span className="flex items-center justify-center">
              <svg className="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              Polling for Holding...
            </span>
          ) : (
            `Accept CBTC via ${useLoopSdk ? 'Loop SDK' : 'Backend'}`
          )}
        </button>
      </div>

      {/* Result Section */}
      {result && (
        <div className={`mt-6 p-4 rounded-xl ${
          result.ok
            ? 'bg-green-50 dark:bg-green-900/20 border border-green-200 dark:border-green-800'
            : 'bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800'
        }`}>
          <h3 className={`font-semibold mb-2 ${
            result.ok ? 'text-green-800 dark:text-green-200' : 'text-red-800 dark:text-red-200'
          }`}>
            {result.ok ? 'Success!' : 'Failed'}
          </h3>

          {result.ok ? (
            <div className="space-y-2 text-sm text-green-700 dark:text-green-300">
              {result.transferInstructionId && (
                <div>
                  <span className="font-medium">TI CID:</span>{' '}
                  <code className="bg-green-100 dark:bg-green-800 px-2 py-0.5 rounded text-xs">
                    {truncateCid(result.transferInstructionId)}
                  </code>
                </div>
              )}
              {result.holdingCid && (
                <div>
                  <span className="font-medium">Holding CID:</span>{' '}
                  <code className="bg-green-100 dark:bg-green-800 px-2 py-0.5 rounded text-xs">
                    {truncateCid(result.holdingCid)}
                  </code>
                </div>
              )}
              {result.amount && (
                <div>
                  <span className="font-medium">Amount:</span> {result.amount} CBTC
                </div>
              )}
              {result.elapsedMs !== undefined && (
                <div>
                  <span className="font-medium">Time:</span> {result.elapsedMs}ms
                </div>
              )}
            </div>
          ) : (
            <div className="space-y-2 text-sm text-red-700 dark:text-red-300">
              <p>{result.error}</p>
              {result.hint && (
                <p className="text-xs opacity-75">Hint: {result.hint}</p>
              )}
              {result.requestId && (
                <p className="text-xs opacity-75">Request ID: {result.requestId}</p>
              )}
              {result.ledgerUpdateId && (
                <p className="text-xs opacity-75">Update ID: {result.ledgerUpdateId}</p>
              )}
              {result.classification && (
                <p className="text-xs opacity-75">Classification: {result.classification}</p>
              )}
            </div>
          )}
        </div>
      )}

      {/* Incoming Offers Section */}
      <div className="mt-6 pt-6 border-t border-gray-200 dark:border-dark-700">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-semibold text-gray-900 dark:text-white">
            Incoming CBTC Offers
          </h3>
          <button
            onClick={loadIncomingOffers}
            disabled={isLoadingOffers}
            className="text-sm text-blue-600 dark:text-blue-400 hover:underline disabled:opacity-50"
          >
            {isLoadingOffers ? 'Loading...' : 'Refresh'}
          </button>
        </div>

        {isLoadingOffers ? (
          <div className="flex items-center justify-center py-4">
            <svg className="animate-spin h-5 w-5 text-blue-500" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
              <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
              <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
            </svg>
            <span className="ml-2 text-sm text-gray-500">Loading offers...</span>
          </div>
        ) : incomingOffers.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400 py-2">
            No incoming CBTC offers found for ClearportX.
          </p>
        ) : (
          <div className="space-y-3">
            {incomingOffers.map((offer, idx) => (
              <div
                key={offer.contractId || idx}
                className="p-4 bg-gradient-to-r from-orange-50 to-yellow-50 dark:from-orange-900/20 dark:to-yellow-900/20 rounded-xl border border-orange-200 dark:border-orange-800"
              >
                <div className="flex items-start justify-between">
                  <div className="space-y-1">
                    <div className="flex items-center space-x-2">
                      <span className="font-bold text-lg text-gray-900 dark:text-white">
                        {offer.amount} CBTC
                      </span>
                      <span className="px-2 py-0.5 text-xs bg-orange-100 dark:bg-orange-800 text-orange-800 dark:text-orange-200 rounded-full">
                        Pending
                      </span>
                    </div>
                    <div className="text-xs text-gray-500 dark:text-gray-400 space-y-0.5">
                      <div>
                        <span className="font-medium">From:</span>{' '}
                        <code className="bg-gray-100 dark:bg-gray-800 px-1 rounded">
                          {truncateCid(offer.sender)}
                        </code>
                      </div>
                      <div>
                        <span className="font-medium">Contract:</span>{' '}
                        <code className="bg-gray-100 dark:bg-gray-800 px-1 rounded">
                          {truncateCid(offer.contractId)}
                        </code>
                      </div>
                      {offer.transferInstructionId && (
                        <div>
                          <span className="font-medium">TI:</span>{' '}
                          <code className="bg-gray-100 dark:bg-gray-800 px-1 rounded">
                            {truncateCid(offer.transferInstructionId)}
                          </code>
                        </div>
                      )}
                      {offer.reason && (
                        <div>
                          <span className="font-medium">Reason:</span> {offer.reason}
                        </div>
                      )}
                    </div>
                  </div>
                  <button
                    onClick={() => handleAcceptFromList(offer)}
                    disabled={isAccepting || isPolling || acceptingOfferId === offer.contractId}
                    className={`px-4 py-2 rounded-lg font-semibold text-sm transition-all ${
                      acceptingOfferId === offer.contractId
                        ? 'bg-yellow-400 text-yellow-900 cursor-wait'
                        : isAccepting || isPolling
                        ? 'bg-gray-300 text-gray-500 cursor-not-allowed'
                        : 'bg-gradient-to-r from-green-500 to-emerald-600 text-white hover:from-green-600 hover:to-emerald-700 shadow hover:shadow-lg'
                    }`}
                  >
                    {acceptingOfferId === offer.contractId ? (
                      <span className="flex items-center">
                        <svg className="animate-spin -ml-1 mr-2 h-4 w-4" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                          <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4"></circle>
                          <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
                        </svg>
                        Accepting...
                      </span>
                    ) : (
                      'Accept'
                    )}
                  </button>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Existing Holdings Section */}
      <div className="mt-6 pt-6 border-t border-gray-200 dark:border-dark-700">
        <div className="flex items-center justify-between mb-4">
          <h3 className="font-semibold text-gray-900 dark:text-white">
            ClearportX CBTC Holdings
          </h3>
          <button
            onClick={loadExistingHoldings}
            disabled={isLoadingHoldings}
            className="text-sm text-blue-600 dark:text-blue-400 hover:underline disabled:opacity-50"
          >
            {isLoadingHoldings ? 'Loading...' : 'Refresh'}
          </button>
        </div>

        {existingHoldings.length === 0 ? (
          <p className="text-sm text-gray-500 dark:text-gray-400">
            No CBTC holdings found. Click Refresh to load.
          </p>
        ) : (
          <div className="space-y-2">
            {existingHoldings.map((holding, idx) => (
              <div
                key={holding.contractId || idx}
                className="flex items-center justify-between p-3 bg-gray-50 dark:bg-dark-700 rounded-lg"
              >
                <div className="text-sm">
                  <code className="text-xs text-gray-600 dark:text-gray-400">
                    {truncateCid(holding.contractId)}
                  </code>
                </div>
                <div className="font-semibold text-gray-900 dark:text-white">
                  {holding.amount} CBTC
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Info Section */}
      <div className="mt-6 p-4 bg-blue-50 dark:bg-blue-900/20 rounded-xl border border-blue-200 dark:border-blue-800">
        <h4 className="font-medium text-blue-800 dark:text-blue-200 mb-2">How it works</h4>
        <ol className="list-decimal list-inside text-sm text-blue-700 dark:text-blue-300 space-y-1">
          <li>Paste the TransferOffer contractId from the sender</li>
          <li>Loop SDK accepts the offer (has TI visibility)</li>
          <li>Acceptance creates a CBTC Holding owned by ClearportX</li>
          <li>Backend polls for the new holding using UTXO selection</li>
        </ol>
      </div>
    </div>
  );
}
