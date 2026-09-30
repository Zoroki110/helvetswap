// Generated from TestNoArchive.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';

export declare type GiveNoArchive = {
  newOwner: damlTypes.Party;
};

export declare const GiveNoArchive:
  damlTypes.Serializable<GiveNoArchive> & {
  }
;


export declare type Coin = {
  issuer: damlTypes.Party;
  owner: damlTypes.Party;
};

export declare interface CoinInterface {
  Archive: damlTypes.Choice<Coin, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Coin, undefined>>;
  GiveNoArchive: damlTypes.Choice<Coin, GiveNoArchive, damlTypes.ContractId<Coin>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Coin, undefined>>;
}
export declare const Coin:
  damlTypes.Template<Coin, undefined, '#clearportx-fees:TestNoArchive:Coin'> &
  damlTypes.ToInterface<Coin, never> &
  CoinInterface;

export declare namespace Coin {
  export type CreateEvent = damlLedger.CreateEvent<Coin, undefined, typeof Coin.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<Coin, typeof Coin.templateId>
  export type Event = damlLedger.Event<Coin, undefined, typeof Coin.templateId>
  export type QueryResult = damlLedger.QueryResult<Coin, undefined, typeof Coin.templateId>
}


