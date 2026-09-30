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

var pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 = require('@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0');


exports.Burn = {
  decoder: damlTypes.lazyMemo(function () { return jtv.object({qty: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
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



exports.LPToken = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:LPToken.LPToken:LPToken',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({issuer: damlTypes.Party.decoder, owner: damlTypes.Party.decoder, poolId: damlTypes.Text.decoder, amount: damlTypes.Numeric(10).decoder, }); }),
  encode: function (__typed__) {
  return {
    issuer: damlTypes.Party.encode(__typed__.issuer),
    owner: damlTypes.Party.encode(__typed__.owner),
    poolId: damlTypes.Text.encode(__typed__.poolId),
    amount: damlTypes.Numeric(10).encode(__typed__.amount),
  };
}
,
  Transfer: {
    template: function () { return exports.LPToken; },
    choiceName: 'Transfer',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.Transfer.decoder; }),
    argumentEncode: function (__typed__) { return exports.Transfer.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.LPToken).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.LPToken).encode(__typed__); },
  },
  Credit: {
    template: function () { return exports.LPToken; },
    choiceName: 'Credit',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.Credit.decoder; }),
    argumentEncode: function (__typed__) { return exports.Credit.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.ContractId(exports.LPToken).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.ContractId(exports.LPToken).encode(__typed__); },
  },
  Archive: {
    template: function () { return exports.LPToken; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
  Burn: {
    template: function () { return exports.LPToken; },
    choiceName: 'Burn',
    argumentDecoder: damlTypes.lazyMemo(function () { return exports.Burn.decoder; }),
    argumentEncode: function (__typed__) { return exports.Burn.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Numeric(10).decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Numeric(10).encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.LPToken, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

