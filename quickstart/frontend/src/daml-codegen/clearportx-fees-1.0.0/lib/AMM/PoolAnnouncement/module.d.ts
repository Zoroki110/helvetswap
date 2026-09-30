// Generated from AMM/PoolAnnouncement.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';
import * as pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946 from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0';

export declare type PoolAnnouncement = {
  poolOperator: damlTypes.Party;
  poolId: string;
  symbolA: string;
  issuerA: damlTypes.Party;
  symbolB: string;
  issuerB: damlTypes.Party;
  feeBps: damlTypes.Int;
  maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime;
  createdAt: damlTypes.Time;
};

export declare interface PoolAnnouncementInterface {
  Archive: damlTypes.Choice<PoolAnnouncement, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<PoolAnnouncement, undefined>>;
}
export declare const PoolAnnouncement:
  damlTypes.Template<PoolAnnouncement, undefined, '#clearportx-fees:AMM.PoolAnnouncement:PoolAnnouncement'> &
  damlTypes.ToInterface<PoolAnnouncement, never> &
  PoolAnnouncementInterface;

export declare namespace PoolAnnouncement {
  export type CreateEvent = damlLedger.CreateEvent<PoolAnnouncement, undefined, typeof PoolAnnouncement.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<PoolAnnouncement, typeof PoolAnnouncement.templateId>
  export type Event = damlLedger.Event<PoolAnnouncement, undefined, typeof PoolAnnouncement.templateId>
  export type QueryResult = damlLedger.QueryResult<PoolAnnouncement, undefined, typeof PoolAnnouncement.templateId>
}


