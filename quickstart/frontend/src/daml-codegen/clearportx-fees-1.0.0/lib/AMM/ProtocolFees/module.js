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

var Token_Token = require('../../Token/Token/module');


exports.GetAccumulatedFees = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({}); }),
  encode: function (__typed__) {
  return {
  };
}
,
};



exports.WithdrawProtocolFees = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({recipient: damlTypes.Party.decoder, }); }),
  encode: function (__typed__) {
  return {
    recipient: damlTypes.Party.encode(__typed__.recipient),
  };
}
,
};



exports.AddProtocolFee = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({feeAmount: damlTypes.Numeric(10).decoder, swapTime: damlTypes.Time.decoder, }); }),
  encode: function (__typed__) {
  return {
    feeAmount: damlTypes.Numeric(10).encode(__typed__.feeAmount),
    swapTime: damlTypes.Time.encode(__typed__.swapTime),
  };
}
,
};



exports.ProtocolFeeCollector = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:AMM.ProtocolFees:ProtocolFeeCollector',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({treasury: damlTypes.Party.decoder, poolId: damlTypes.Text.decoder, tokenSymbol: damlTypes.Text.decoder, tokenIssuer: damlTypes.Party.decoder, accumulatedFees: damlTypes.Numeric(10).decoder, lastCollectionTime: damlTypes.Time.decoder, }); }),
  encode: function (__typed__) {
  return {
    treasury: damlTypes.Party.encode(__typed__.treasury),
    poolId: damlTypes.Text.encode(__typed__.poolId),
    tokenSymbol: damlTypes.Text.encode(__typed__.tokenSymbol),
    tokenIssuer: damlTypes.Party.encode(__typed__.tokenIssuer),
    accumulatedFees: damlTypes.Numeric(10).encode(__typed__.accumulatedFees),
    lastCollectionTime: damlTypes.Time.encode(__typed__.lastCollectionTime),
  };
}
,
  AddProtocolFee: {
    template: function () { return exports.ProtocolFeeCollector; },
    choiceName: 'AddProtocolFee',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.AddProtocolFee.decoder; }),
    argumentEncode: function (__typed__) { return exports.AddProtocolFee.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.ProtocolFeeCollector).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.ProtocolFeeCollector).encode(__typed__); },
  },
  WithdrawProtocolFees: {
    template: function () { return exports.ProtocolFeeCollector; },
    choiceName: 'WithdrawProtocolFees',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.WithdrawProtocolFees.decoder; }),
    argumentEncode: function (__typed__) { return exports.WithdrawProtocolFees.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(exports.ProtocolFeeCollector)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.ContractId(Token_Token.Token), damlTypes.ContractId(exports.ProtocolFeeCollector)).encode(__typed__); },
  },
  Archive: {
    template: function () { return exports.ProtocolFeeCollector; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  GetAccumulatedFees: {
    template: function () { return exports.ProtocolFeeCollector; },
    choiceName: 'GetAccumulatedFees',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.GetAccumulatedFees.decoder; }),
    argumentEncode: function (__typed__) { return exports.GetAccumulatedFees.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Numeric(10).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Numeric(10).encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.ProtocolFeeCollector, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

