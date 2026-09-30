import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { backendApi } from '../services/backendApi';
import { LoopWalletConnector } from '../wallet/LoopWalletConnector';
import { getLoopProvider } from '../loop/loopProvider';
import { submitTx, SubmitTxMode } from '../loop/submitTx';

const AMULET_ADMIN = 'DSO::1220be58c29e65de40bf273be1dc2b266d43a9a002ea5b18955aeef7aac881bb471a';
const CBTC_ADMIN = 'cbtc-network::12202a83c6f4082217c175e29bc53da5f2703ba2675778ab99217a5a881a949203ff';
const AMULET_ID = 'Amulet';
const CBTC_ID = 'CBTC';
const OPERATOR = 'ClearportX-DEX-1::122081f2b8e29cbe57d1037a18e6f70e57530773b3a4d1bea6bab981b7a76e943b37';
const TI_TEMPLATE_ID = '#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferInstruction';

let loopConnector: LoopWalletConnector | null = null;
function getLoopConnector(): LoopWalletConnector {
  if (!loopConnector) {
    loopConnector = new LoopWalletConnector();
    LoopWalletConnector.initOnce();
  }
  return loopConnector;
}

type InstrumentOption = 'Amulet' | 'CBTC';

export default function DevNetLoopTxTester() {
  const isDevnet =
    process.env.REACT_APP_ENV === 'devnet' ||
    process.env.NODE_ENV === 'development' ||
    (typeof window !== 'undefined' &&
      (window.location.hostname === 'localhost' || window.location.hostname.startsWith('127.')));
  const [receiverParty, setReceiverParty] = useState('');
  const [amount, setAmount] = useState('0.0010000000');
  const [executeBeforeSeconds, setExecuteBeforeSeconds] = useState(7200);
  const [memo, setMemo] = useState('sdk10-debug');
  const [instrument, setInstrument] = useState<InstrumentOption>('CBTC');
  const [payoutResult, setPayoutResult] = useState<any>(null);
  const [payoutLoading, setPayoutLoading] = useState(false);

  const [cidToAccept, setCidToAccept] = useState('');
  const [actAsParty, setActAsParty] = useState('');
  const [mode, setMode] = useState<SubmitTxMode>('WAIT');
  const [estimateTraffic, setEstimateTraffic] = useState(false);
  const [acceptResult, setAcceptResult] = useState<any>(null);
  const [acceptLoading, setAcceptLoading] = useState(false);

  const [loopConnected, setLoopConnected] = useState(false);
  const [loopConnecting, setLoopConnecting] = useState(false);

  const [holdingsResult, setHoldingsResult] = useState<any>(null);
  const [outgoingResult, setOutgoingResult] = useState<any>(null);
  const [verifyLoading, setVerifyLoading] = useState(false);

  const instrumentAdmin = useMemo(() => (instrument === 'CBTC' ? CBTC_ADMIN : AMULET_ADMIN), [instrument]);
  const instrumentId = useMemo(() => (instrument === 'CBTC' ? CBTC_ID : AMULET_ID), [instrument]);

  useEffect(() => {
    if (!actAsParty && receiverParty) {
      setActAsParty(receiverParty);
    }
  }, [receiverParty, actAsParty]);

  useEffect(() => {
    const checkLoop = async () => {
      if (!isDevnet) return;
      const connector = getLoopConnector();
      const status = await connector.checkConnected();
      setLoopConnected(status.connected);
    };
    void checkLoop();
  }, [isDevnet]);

  const connectLoop = useCallback(async () => {
    setLoopConnecting(true);
    try {
      const connector = getLoopConnector();
      await connector.connectFromClick();
      const status = await connector.checkConnected();
      setLoopConnected(status.connected);
      if (!status.connected) {
        setAcceptResult({ ok: false, error: { code: 'CONNECT', message: 'Loop connection failed' } });
      }
    } catch (err: any) {
      setAcceptResult({ ok: false, error: { code: 'CONNECT', message: err?.message || String(err) } });
      setLoopConnected(false);
    } finally {
      setLoopConnecting(false);
    }
  }, []);

  const createPayout = useCallback(async () => {
    if (!receiverParty.trim()) {
      setPayoutResult({ ok: false, error: { message: 'receiverParty is required' } });
      return;
    }
    if (!amount.trim()) {
      setPayoutResult({ ok: false, error: { message: 'amount is required' } });
      return;
    }
    setPayoutLoading(true);
    setPayoutResult(null);
    try {
      const response = await backendApi.createDevnetPayout(instrument.toLowerCase() as 'amulet' | 'cbtc', {
        receiverParty: receiverParty.trim(),
        amount: amount.trim(),
        executeBeforeSeconds,
        memo: memo.trim(),
      });
      setPayoutResult(response);
      const cid = response?.result?.cid;
      if (cid) {
        setCidToAccept(cid);
      }
    } catch (err: any) {
      setPayoutResult({ ok: false, error: { message: err?.message || String(err) } });
    } finally {
      setPayoutLoading(false);
    }
  }, [amount, executeBeforeSeconds, instrument, memo, receiverParty]);

  const acceptByCid = useCallback(async () => {
    const cid = cidToAccept.trim();
    if (!cid) {
      setAcceptResult({ ok: false, error: { code: 'VALIDATION', message: 'cid is required' } });
      return;
    }
    if (!actAsParty.trim()) {
      setAcceptResult({ ok: false, error: { code: 'VALIDATION', message: 'actAsParty is required' } });
      return;
    }
    setAcceptLoading(true);
    setAcceptResult(null);
    try {
      const connector = getLoopConnector();
      const status = await connector.ensureConnected(`loop-debug-${Date.now()}`);
      if (!status.connected || !getLoopProvider()) {
        setAcceptResult({ ok: false, error: { code: 'CONNECT', message: status.error || 'Loop not connected' } });
        return;
      }

      const commands = [
        {
          ExerciseCommand: {
            templateId: TI_TEMPLATE_ID,
            contractId: cid,
            choice: 'TransferInstruction_Accept',
            choiceArgument: {},
          },
        },
      ];

      const result = await submitTx({
        commands,
        actAs: actAsParty.trim(),
        readAs: actAsParty.trim(),
        mode,
        estimateTraffic,
      });
      setAcceptResult(result);
    } catch (err: any) {
      setAcceptResult({ ok: false, error: { code: 'ERROR', message: err?.message || String(err) } });
    } finally {
      setAcceptLoading(false);
    }
  }, [actAsParty, cidToAccept, estimateTraffic, mode]);

  const checkHoldings = useCallback(async () => {
    if (!receiverParty.trim()) {
      setHoldingsResult({ ok: false, error: { message: 'receiverParty is required' } });
      return;
    }
    setVerifyLoading(true);
    try {
      const res = await backendApi.getHoldingUtxos(receiverParty.trim(), true);
      setHoldingsResult(res);
    } catch (err: any) {
      setHoldingsResult({ ok: false, error: { message: err?.message || String(err) } });
    } finally {
      setVerifyLoading(false);
    }
  }, [receiverParty]);

  const checkOutgoing = useCallback(async () => {
    setVerifyLoading(true);
    try {
      const res = await backendApi.getOutgoingTransferInstructions({
        senderParty: OPERATOR,
        instrumentAdmin,
        instrumentId,
      });
      setOutgoingResult(res);
    } catch (err: any) {
      setOutgoingResult({ ok: false, error: { message: err?.message || String(err) } });
    } finally {
      setVerifyLoading(false);
    }
  }, [instrumentAdmin, instrumentId]);

  if (!isDevnet) {
    return (
      <div className="card text-center py-12">
        <h2 className="text-xl font-semibold text-gray-900 dark:text-gray-100">DevNet Debug Panel</h2>
        <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">
          This page is only available in DevNet or on localhost.
        </p>
      </div>
    );
  }

  return (
    <div className="bg-white dark:bg-dark-800 rounded-2xl shadow-lg p-6 border border-gray-200 dark:border-dark-700 space-y-6">
      <div>
        <h2 className="text-2xl font-bold text-gray-900 dark:text-white">Debug: Loop Tx Tester</h2>
        <p className="text-sm text-gray-600 dark:text-gray-400">
          Minimal DevNet panel for submitTx WAIT mode verification.
        </p>
      </div>

      {/* Create payout */}
      <div className="p-4 bg-gray-50 dark:bg-dark-700 rounded-xl border border-gray-200 dark:border-dark-600 space-y-4">
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Create payout (backend)</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            placeholder="receiverParty"
            value={receiverParty}
            onChange={(e) => setReceiverParty(e.target.value)}
          />
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            placeholder="amount"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
          />
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            type="number"
            placeholder="executeBeforeSeconds"
            value={executeBeforeSeconds}
            onChange={(e) => setExecuteBeforeSeconds(Number(e.target.value || 0))}
          />
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            placeholder="memo"
            value={memo}
            onChange={(e) => setMemo(e.target.value)}
          />
          <select
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            value={instrument}
            onChange={(e) => setInstrument(e.target.value as InstrumentOption)}
          >
            <option value="Amulet">Amulet</option>
            <option value="CBTC">CBTC</option>
          </select>
        </div>
        <button
          className={`w-full py-3 px-4 rounded-xl font-semibold text-white transition-all ${
            payoutLoading ? 'bg-gray-400 cursor-not-allowed' : 'bg-blue-600 hover:bg-blue-700'
          }`}
          onClick={createPayout}
          disabled={payoutLoading}
        >
          {payoutLoading ? 'Creating...' : 'Create payout'}
        </button>
        {payoutResult && (
          <pre className="mt-3 text-xs bg-gray-900 text-green-200 rounded-xl p-4 overflow-auto">
            {JSON.stringify(payoutResult, null, 2)}
          </pre>
        )}
      </div>

      {/* Accept by CID */}
      <div className="p-4 bg-gray-50 dark:bg-dark-700 rounded-xl border border-gray-200 dark:border-dark-600 space-y-4">
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Accept by CID (Loop)</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            placeholder="CID to accept"
            value={cidToAccept}
            onChange={(e) => setCidToAccept(e.target.value)}
          />
          <input
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            placeholder="actAsParty"
            value={actAsParty}
            onChange={(e) => setActAsParty(e.target.value)}
          />
          <select
            className="w-full px-4 py-3 bg-white dark:bg-dark-900 border border-gray-200 dark:border-dark-600 rounded-xl text-sm text-gray-900 dark:text-white"
            value={mode}
            onChange={(e) => setMode(e.target.value as SubmitTxMode)}
          >
            <option value="WAIT">WAIT</option>
            <option value="LEGACY">LEGACY</option>
          </select>
          <label className="flex items-center space-x-2 text-sm text-gray-700 dark:text-gray-300">
            <input
              type="checkbox"
              checked={estimateTraffic}
              onChange={(e) => setEstimateTraffic(e.target.checked)}
            />
            <span>estimateTraffic</span>
          </label>
        </div>
        <div className="flex flex-col md:flex-row gap-3">
          <button
            className={`flex-1 py-3 px-4 rounded-xl font-semibold text-white transition-all ${
              loopConnecting ? 'bg-gray-400 cursor-not-allowed' : 'bg-emerald-600 hover:bg-emerald-700'
            }`}
            onClick={connectLoop}
            disabled={loopConnecting}
          >
            {loopConnected ? 'Loop Connected' : loopConnecting ? 'Connecting...' : 'Connect Loop'}
          </button>
          <button
            className={`flex-1 py-3 px-4 rounded-xl font-semibold text-white transition-all ${
              acceptLoading ? 'bg-gray-400 cursor-not-allowed' : 'bg-indigo-600 hover:bg-indigo-700'
            }`}
            onClick={acceptByCid}
            disabled={acceptLoading}
          >
            {acceptLoading ? 'Submitting...' : 'Accept by CID'}
          </button>
        </div>
        {acceptResult && (
          <pre className="mt-3 text-xs bg-gray-900 text-blue-200 rounded-xl p-4 overflow-auto">
            {JSON.stringify(acceptResult, null, 2)}
          </pre>
        )}
      </div>

      {/* Verify section */}
      <div className="p-4 bg-gray-50 dark:bg-dark-700 rounded-xl border border-gray-200 dark:border-dark-600 space-y-4">
        <h3 className="text-lg font-semibold text-gray-900 dark:text-white">Verify (backend)</h3>
        <div className="flex flex-col md:flex-row gap-3">
          <button
            className={`flex-1 py-3 px-4 rounded-xl font-semibold text-white transition-all ${
              verifyLoading ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-700 hover:bg-gray-800'
            }`}
            onClick={checkHoldings}
            disabled={verifyLoading}
          >
            Check receiver holdings
          </button>
          <button
            className={`flex-1 py-3 px-4 rounded-xl font-semibold text-white transition-all ${
              verifyLoading ? 'bg-gray-400 cursor-not-allowed' : 'bg-gray-700 hover:bg-gray-800'
            }`}
            onClick={checkOutgoing}
            disabled={verifyLoading}
          >
            Check outgoing for operator
          </button>
        </div>
        {holdingsResult && (
          <pre className="mt-3 text-xs bg-gray-900 text-orange-200 rounded-xl p-4 overflow-auto">
            {JSON.stringify(holdingsResult, null, 2)}
          </pre>
        )}
        {outgoingResult && (
          <pre className="mt-3 text-xs bg-gray-900 text-purple-200 rounded-xl p-4 overflow-auto">
            {JSON.stringify(outgoingResult, null, 2)}
          </pre>
        )}
      </div>
    </div>
  );
}

