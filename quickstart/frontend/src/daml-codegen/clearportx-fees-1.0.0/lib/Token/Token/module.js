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


exports.TransferSplit = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({recipient: damlTypes.Party.decoder, qty: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    recipient: damlTypes.Party.encode(__typed__.recipient),
    qty: damlTypes.Numeric(10).encode(__typed__.qty),
  };
}
,
};



exports.Credit = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({qty: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    qty: damlTypes.Numeric(10).encode(__typed__.qty),
  };
}
,
};



exports.Transfer = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({recipient: damlTypes.Party.decoder, qty: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    recipient: damlTypes.Party.encode(__typed__.recipient),
    qty: damlTypes.Numeric(10).encode(__typed__.qty),
  };
}
,
};



exports.Token = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:Token.Token:Token',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({issuer: damlTypes.Party.decoder, owner: damlTypes.Party.decoder, symbol: damlTypes.Text.decoder, amount: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    issuer: damlTypes.Party.encode(__typed__.issuer),
    owner: damlTypes.Party.encode(__typed__.owner),
    symbol: damlTypes.Text.encode(__typed__.symbol),
    amount: damlTypes.Numeric(10).encode(__typed__.amount),
  };
}
,
  Transfer: {
    template: function () { return exports.Token; },
    choiceName: 'Transfer',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.Transfer.decoder; }),
    argumentEncode: function (__typed__) { return exports.Transfer.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.Token).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.Token).encode(__typed__); },
  },
  Credit: {
    template: function () { return exports.Token; },
    choiceName: 'Credit',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.Credit.decoder; }),
    argumentEncode: function (__typed__) { return exports.Credit.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.Token).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.Token).encode(__typed__); },
  },
  Archive: {
    template: function () { return exports.Token; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  TransferSplit: {
    template: function () { return exports.Token; },
    choiceName: 'TransferSplit',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.TransferSplit.decoder; }),
    argumentEncode: function (__typed__) { return exports.TransferSplit.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Optional(damlTypes.ContractId(exports.Token)), damlTypes.ContractId(exports.Token)).decoder; }),
    resultEncode: function (__typed__) { return pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2(damlTypes.Optional(damlTypes.ContractId(exports.Token)), damlTypes.ContractId(exports.Token)).encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.Token, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

