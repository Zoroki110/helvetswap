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

var LPToken_LPToken = require('../../LPToken/LPToken/module');
var Token_Token = require('../../Token/Token/module');


exports.ArchiveAndUpdateReserves = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({updatedReserveA: damlTypes.Numeric(10).decoder, updatedReserveB: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    updatedReserveA: damlTypes.Numeric(10).encode(__typed__.updatedReserveA),
    updatedReserveB: damlTypes.Numeric(10).encode(__typed__.updatedReserveB),
  };
}
,
};



exports.VerifyReserves = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({poolTokenACid: damlTypes.ContractId(Token_Token.Token).decoder, poolTokenBCid: damlTypes.ContractId(Token_Token.Token).decoder, }); }),
  encode: function (__typed__) {
  return {
    poolTokenACid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenACid),
    poolTokenBCid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenBCid),
  };
}
,
};



exports.GetSpotPrice = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({}); }),
  encode: function (__typed__) {
  return {
  };
}
,
};



exports.GetSupply = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({}); }),
  encode: function (__typed__) {
  return {
  };
}
,
};



exports.GetReservesForPool = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({}); }),
  encode: function (__typed__) {
  return {
  };
}
,
};



exports.RemoveLiquidity = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({provider: damlTypes.Party.decoder, lpTokenCid: damlTypes.ContractId(LPToken_LPToken.LPToken).decoder, lpTokenAmount: damlTypes.Numeric(10).decoder, minAmountA: damlTypes.Numeric(10).decoder, minAmountB: damlTypes.Numeric(10).decoder, poolTokenACid: damlTypes.ContractId(Token_Token.Token).decoder, poolTokenBCid: damlTypes.ContractId(Token_Token.Token).decoder, deadline: damlTypes.Time.decoder, }); }),
  encode: function (__typed__) {
  return {
    provider: damlTypes.Party.encode(__typed__.provider),
    lpTokenCid: damlTypes.ContractId(LPToken_LPToken.LPToken).encode(__typed__.lpTokenCid),
    lpTokenAmount: damlTypes.Numeric(10).encode(__typed__.lpTokenAmount),
    minAmountA: damlTypes.Numeric(10).encode(__typed__.minAmountA),
    minAmountB: damlTypes.Numeric(10).encode(__typed__.minAmountB),
    poolTokenACid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenACid),
    poolTokenBCid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.poolTokenBCid),
    deadline: damlTypes.Time.encode(__typed__.deadline),
  };
}
,
};



exports.AddLiquidity = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({provider: damlTypes.Party.decoder, tokenACid: damlTypes.ContractId(Token_Token.Token).decoder, tokenBCid: damlTypes.ContractId(Token_Token.Token).decoder, amountA: damlTypes.Numeric(10).decoder, amountB: damlTypes.Numeric(10).decoder, minLPTokens: damlTypes.Numeric(10).decoder, deadline: damlTypes.Time.decoder, }); }),
  encode: function (__typed__) {
  return {
    provider: damlTypes.Party.encode(__typed__.provider),
    tokenACid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.tokenACid),
    tokenBCid: damlTypes.ContractId(Token_Token.Token).encode(__typed__.tokenBCid),
    amountA: damlTypes.Numeric(10).encode(__typed__.amountA),
    amountB: damlTypes.Numeric(10).encode(__typed__.amountB),
    minLPTokens: damlTypes.Numeric(10).encode(__typed__.minLPTokens),
    deadline: damlTypes.Time.encode(__typed__.deadline),
  };
}
,
};



exports.Pool = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:AMM.Pool:Pool',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({poolOperator: damlTypes.Party.decoder, poolParty: damlTypes.Party.decoder, lpIssuer: damlTypes.Party.decoder, issuerA: damlTypes.Party.decoder, issuerB: damlTypes.Party.decoder, symbolA: damlTypes.Text.decoder, symbolB: damlTypes.Text.decoder, feeBps: damlTypes.Int.decoder, poolId: damlTypes.Text.decoder, maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.decoder, totalLPSupply: damlTypes.Numeric(10).decoder, reserveA: damlTypes.Numeric(10).decoder, reserveB: damlTypes.Numeric(10).decoder, protocolFeeReceiver: damlTypes.Party.decoder, }); }),
  encode: function (__typed__) {
  return {
    poolOperator: damlTypes.Party.encode(__typed__.poolOperator),
    poolParty: damlTypes.Party.encode(__typed__.poolParty),
    lpIssuer: damlTypes.Party.encode(__typed__.lpIssuer),
    issuerA: damlTypes.Party.encode(__typed__.issuerA),
    issuerB: damlTypes.Party.encode(__typed__.issuerB),
    symbolA: damlTypes.Text.encode(__typed__.symbolA),
    symbolB: damlTypes.Text.encode(__typed__.symbolB),
    feeBps: damlTypes.Int.encode(__typed__.feeBps),
    poolId: damlTypes.Text.encode(__typed__.poolId),
    maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.encode(__typed__.maxTTL),
    totalLPSupply: damlTypes.Numeric(10).encode(__typed__.totalLPSupply),
    reserveA: damlTypes.Numeric(10).encode(__typed__.reserveA),
    reserveB: damlTypes.Numeric(10).encode(__typed__.reserveB),
    protocolFeeReceiver: damlTypes.Party.encode(__typed__.protocolFeeReceiver),
  };
}
,
  AddLiquidity: {
    template: function () { return exports.Pool; },
    choiceName: 'AddLiquidity',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.AddLiquidity.decoder; }),
    argumentEncode: function (__typed__) { return exports.AddLiquidity.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(LPToken_LPToken.LPToken), damlTypes.ContractId(exports.Pool)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(LPToken_LPToken.LPToken), damlTypes.ContractId(exports.Pool)).encode(__typed__); },
  },
  RemoveLiquidity: {
    template: function () { return exports.Pool; },
    choiceName: 'RemoveLiquidity',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.RemoveLiquidity.decoder; }),
    argumentEncode: function (__typed__) { return exports.RemoveLiquidity.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple3(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(exports.Pool)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple3(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(exports.Pool)).encode(__typed__); },
  },
  GetReservesForPool: {
    template: function () { return exports.Pool; },
    choiceName: 'GetReservesForPool',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.GetReservesForPool.decoder; }),
    argumentEncode: function (__typed__) { return exports.GetReservesForPool.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Numeric(10), damlTypes.Numeric(10)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Numeric(10), damlTypes.Numeric(10)).encode(__typed__); },
  },
  GetSupply: {
    template: function () { return exports.Pool; },
    choiceName: 'GetSupply',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.GetSupply.decoder; }),
    argumentEncode: function (__typed__) { return exports.GetSupply.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Numeric(10).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Numeric(10).encode(__typed__); },
  },
  GetSpotPrice: {
    template: function () { return exports.Pool; },
    choiceName: 'GetSpotPrice',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.GetSpotPrice.decoder; }),
    argumentEncode: function (__typed__) { return exports.GetSpotPrice.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Numeric(10), damlTypes.Numeric(10)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Numeric(10), damlTypes.Numeric(10)).encode(__typed__); },
  },
  VerifyReserves: {
    template: function () { return exports.Pool; },
    choiceName: 'VerifyReserves',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.VerifyReserves.decoder; }),
    argumentEncode: function (__typed__) { return exports.VerifyReserves.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Bool, damlTypes.Text).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Bool, damlTypes.Text).encode(__typed__); },
  },
  Archive: {
    template: function () { return exports.Pool; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  ArchiveAndUpdateReserves: {
    template: function () { return exports.Pool; },
    choiceName: 'ArchiveAndUpdateReserves',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.ArchiveAndUpdateReserves.decoder; }),
    argumentEncode: function (__typed__) { return exports.ArchiveAndUpdateReserves.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.Pool).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.Pool).encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.Pool, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

