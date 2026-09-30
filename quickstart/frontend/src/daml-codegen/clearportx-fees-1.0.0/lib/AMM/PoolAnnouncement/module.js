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
var pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946 = require('@daml.js/daml-stdlib-DA-Time-Types-1.0.0');


exports.PoolAnnouncement = damlTypes.assembleTemplate(
{
  templateId: '#clearportx-fees:AMM.PoolAnnouncement:PoolAnnouncement',
  keyDecoder: damlTypes.lazyMemo(function () { return jtv.constant(undefined); }),
  keyEncode: function () { throw 'EncodeError'; },
  decoder: damlTypes.lazyMemo(function () { return jtv.object({poolOperator: damlTypes.Party.decoder, poolId: damlTypes.Text.decoder, symbolA: damlTypes.Text.decoder, issuerA: damlTypes.Party.decoder, symbolB: damlTypes.Text.decoder, issuerB: damlTypes.Party.decoder, feeBps: damlTypes.Int.decoder, maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.decoder, createdAt: damlTypes.Time.decoder, }); }),
  encode: function (__typed__) {
  return {
    poolOperator: damlTypes.Party.encode(__typed__.poolOperator),
    poolId: damlTypes.Text.encode(__typed__.poolId),
    symbolA: damlTypes.Text.encode(__typed__.symbolA),
    issuerA: damlTypes.Party.encode(__typed__.issuerA),
    symbolB: damlTypes.Text.encode(__typed__.symbolB),
    issuerB: damlTypes.Party.encode(__typed__.issuerB),
    feeBps: damlTypes.Int.encode(__typed__.feeBps),
    maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime.encode(__typed__.maxTTL),
    createdAt: damlTypes.Time.encode(__typed__.createdAt),
  };
}
,
  Archive: {
    template: function () { return exports.PoolAnnouncement; },
    choiceName: 'Archive',
    argumentDecoder: damlTypes.lazyMemo(function () { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.decoder; }),
    argumentEncode: function (__typed__) { return pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive.encode(__typed__); },
    resultDecoder: damlTypes.lazyMemo(function () { return damlTypes.Unit.decoder; }),
    resultEncode: function (__typed__) { return damlTypes.Unit.encode(__typed__); },
  },
}

);


damlTypes.registerTemplate(exports.PoolAnnouncement, ['7c801516582a02fc151118fc15e9d777bee504de314447c589478390f2c8cea1', '#clearportx-fees']);

