"use strict";
/* eslint-disable-next-line no-unused-vars */
function __export(m) {
/* eslint-disable-next-line no-prototype-builtins */
    for (var p in m) if (!exports.hasOwnProperty(p)) exports[p] = m[p];
}
Object.defineProperty(exports, "__esModule", { value: true });
/* eslint-disable-next-line no-unused-vars */
var jtv = require('@mojotech/json-type-validation');
/* eslint-disable-next-line no-unused-vars */
var damlTypes = require('@daml/types');
/* eslint-disable-next-line no-unused-vars */
var damlLedger = require('@daml/ledger');

var pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4 = require('@daml.js/daml-prim-DA-Types-1.0.0');
var pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 = require('@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0');
var pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946 = require('@daml.js/daml-stdlib-DA-Time-Types-1.0.0');

var AMM_Pool = require('../../AMM/Pool/module');
var Token_Token = require('../../Token/Token/module');


exports.ExecuteSwap = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({poolTokenACid: damlTypes.ContractId(Token_Token.Token).decoder, poolTokenBCid: damlTypes.ContractId(Token_Token.Token).decoder, }); }),
  encode: function (__typed__) {
  return {
    poolTokenACid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenACid),
    poolTokenBCid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenBCid),
  };
}
,
};



exports.SwapReady = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:AMM.SwapRequest:SwapReady',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({trader: damlTypes.Party.decoder, poolCid: damlTypes.ContractId(AMM_Pool.Pool).decoder, poolParty: damlTypes.Party.decoder, protocolFeeReceiver: damlTypes.Party.decoder, issuerA: damlTypes.Party.decoder, issuerB: damlTypes.Party.decoder, symbolA: damlTypes.Text.decoder, symbolB: damlTypes.Text.decoder, feeBps: damlTypes.Int.decoder, maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.decoder, inputSymbol: damlTypes.Text.decoder, inputAmount: damlTypes.Numeric(10).decoder, outputSymbol: damlTypes.Text.decoder, minOutput: damlTypes.Numeric(10).decoder, deadline: damlTypes.Time.decoder, maxPriceImpactBps: damlTypes.Int.decoder, }); }),
  encode: function (__typed__) {
  return {
    trader: damlTypes.Party.encode(__typed__.trader),
    poolCid: damlTypes.ContractId(AMM_Pool.Pool).encode(__typed__.poolCid),
    poolParty: damlTypes.Party.encode(__typed__.poolParty),
    protocolFeeReceiver: damlTypes.Party.encode(__typed__.protocolFeeReceiver),
    issuerA: damlTypes.Party.encode(__typed__.issuerA),
    issuerB: damlTypes.Party.encode(__typed__.issuerB),
    symbolA: damlTypes.Text.encode(__typed__.symbolA),
    symbolB: damlTypes.Text.encode(__typed__.symbolB),
    feeBps: damlTypes.Int.encode(__typed__.feeBps),
    maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.encode(__typed__.maxTTL),
    inputSymbol: damlTypes.Text.encode(__typed__.inputSymbol),
    inputAmount: damlTypes.Numeric(10).encode(__typed__.inputAmount),
    outputSymbol: damlTypes.Text.encode(__typed__.outputSymbol),
    minOutput: damlTypes.Numeric(10).encode(__typed__.minOutput),
    deadline: damlTypes.Time.encode(__typed__.deadline),
    maxPriceImpactBps: damlTypes.Int.encode(__typed__.maxPriceImpactBps),
  };
}
,
  Archive: {
    template: function () { return exports.SwapReady; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  ExecuteSwap: {
    template: function () { return exports.SwapReady; },
    choiceName: 'ExecuteSwap',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.ExecuteSwap.decoder; }),
    argumentEncode: function (__typed__) { return exports.ExecuteSwap.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(AMM_Pool.Pool)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(AMM_Pool.Pool)).encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.SwapReady, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);



exports.PrepareSwap = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({protocolFeeReceiver: damlTypes.Party.decoder, }); }),
  encode: function (__typed__) {
  return {
    protocolFeeReceiver: damlTypes.Party.encode(__typed__.protocolFeeReceiver),
  };
}
,
};



exports.CancelSwapRequest = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({}); }),
  encode: function (__typed__) {
  return {
  };
}
,
};



exports.SwapRequest = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:AMM.SwapRequest:SwapRequest',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({trader: damlTypes.Party.decoder, poolCid: damlTypes.ContractId(AMM_Pool.Pool).decoder, poolParty: damlTypes.Party.decoder, poolOperator: damlTypes.Party.decoder, issuerA: damlTypes.Party.decoder, issuerB: damlTypes.Party.decoder, symbolA: damlTypes.Text.decoder, symbolB: damlTypes.Text.decoder, feeBps: damlTypes.Int.decoder, maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.decoder, inputTokenCid: damlTypes.ContractId(Token_Token.Token).decoder, inputSymbol: damlTypes.Text.decoder, inputAmount: damlTypes.Numeric(10).decoder, outputSymbol: damlTypes.Text.decoder, minOutput: damlTypes.Numeric(10).decoder, deadline: damlTypes.Time.decoder, maxPriceImpactBps: damlTypes.Int.decoder, }); }),
  encode: function (__typed__) {
  return {
    trader: damlTypes.Party.encode(__typed__.trader),
    poolCid: damlTypes.ContractId(AMM_Pool.Pool).encode(__typed__.poolCid),
    poolParty: damlTypes.Party.encode(__typed__.poolParty),
    poolOperator: damlTypes.Party.encode(__typed__.poolOperator),
    issuerA: damlTypes.Party.encode(__typed__.issuerA),
    issuerB: damlTypes.Party.encode(__typed__.issuerB),
    symbolA: damlTypes.Text.encode(__typed__.symbolA),
    symbolB: damlTypes.Text.encode(__typed__.symbolB),
    feeBps: damlTypes.Int.encode(__typed__.feeBps),
    maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.encode(__typed__.maxTTL),
    inputTokenCid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.inputTokenCid),
    inputSymbol: damlTypes.Text.encode(__typed__.inputSymbol),
    inputAmount: damlTypes.Numeric(10).encode(__typed__.inputAmount),
    outputSymbol: damlTypes.Text.encode(__typed__.outputSymbol),
    minOutput: damlTypes.Numeric(10).encode(__typed__.minOutput),
    deadline: damlTypes.Time.encode(__typed__.deadline),
    maxPriceImpactBps: damlTypes.Int.encode(__typed__.maxPriceImpactBps),
  };
}
,
  CancelSwapRequest: {
    template: function () { return exports.SwapRequest; },
    choiceName: 'CancelSwapRequest',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.CancelSwapRequest.decoder; }),
    argumentEncode: function (__typed__) { return exports.CancelSwapRequest.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  PrepareSwap: {
    template: function () { return exports.SwapRequest; },
    choiceName: 'PrepareSwap',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.PrepareSwap.decoder; }),
    argumentEncode: function (__typed__) { return exports.PrepareSwap.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(exports.SwapReady), damlTypes.ContractId(Token_Token.Token)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(exports.SwapReady), damlTypes.ContractId(Token_Token.Token)).encode(__typed__); },
  },
  Archive: {
    template: function () { return exports.SwapRequest; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.SwapRequest, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

