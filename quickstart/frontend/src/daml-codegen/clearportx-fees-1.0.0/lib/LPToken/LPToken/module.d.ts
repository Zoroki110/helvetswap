// Generated from LPToken/LPToken.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';

export declare type Burn = {
  qty: damlTypes.Numeric;
};

export declare const Burn:
  damlTypes.Serializable<Burn> & {
  }
;


export declare type Credit = {
  qty: damlTypes.Numeric;
};

export declare const Credit:
  damlTypes.Serializable<Credit> & {
  }
;


export declare type Transfer = {
  recipient: damlTypes.Party;
  qty: damlTypes.Numeric;
};

export declare const Transfer:
  damlTypes.Serializable<Transfer> & {
  }
;


export declare type LPToken = {
  issuer: damlTypes.Party;
  owner: damlTypes.Party;
  poolId: string;
  amount: damlTypes.Numeric;
};

export declare interface LPTokenInterface {
  Transfer: damlTypes.Choice<LPToken, Transfer, damlTypes.ContractId<LPToken>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<LPToken, undefined>>;
  Credit: damlTypes.Choice<LPToken, Credit, damlTypes.ContractId<LPToken>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<LPToken, undefined>>;
  Archive: damlTypes.Choice<LPToken, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<LPToken, undefined>>;
  Burn: damlTypes.Choice<LPToken, Burn, damlTypes.Numeric, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<LPToken, undefined>>;
}
export declare const LPToken:
  damlTypes.Template<LPToken, undefined, '#clearportx-fees:LPToken.LPToken:LPToken'> &
  damlTypes.ToInterface<LPToken, never> &
  LPTokenInterface;

export declare namespace LPToken {
  export type CreateEvent = damlLedger.CreateEvent<LPToken, undefined, typeof LPToken.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<LPToken, typeof LPToken.templateId>
  export type Event = damlLedger.Event<LPToken, undefined, typeof LPToken.templateId>
  export type QueryResult = damlLedger.QueryResult<LPToken, undefined, typeof LPToken.templateId>
}


