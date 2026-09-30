// Generated from Token/Token.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4 from '@daml.js/daml-prim-DA-Types-1.0.0';
import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';

export declare type TransferSplit = {
  recipient: damlTypes.Party;
  qty: damlTypes.Numeric;
};

export declare const TransferSplit:
  damlTypes.Serializable<TransferSplit> & {
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


export declare type Token = {
  issuer: damlTypes.Party;
  owner: damlTypes.Party;
  symbol: string;
  amount: damlTypes.Numeric;
};

export declare interface TokenInterface {
  Transfer: damlTypes.Choice<Token, Transfer, damlTypes.ContractId<Token>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Token, undefined>>;
  Credit: damlTypes.Choice<Token, Credit, damlTypes.ContractId<Token>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Token, undefined>>;
  Archive: damlTypes.Choice<Token, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Token, undefined>>;
  TransferSplit: damlTypes.Choice<Token, TransferSplit, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.Optional<damlTypes.ContractId<Token>>, damlTypes.ContractId<Token>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Token, undefined>>;
}
export declare const Token:
  damlTypes.Template<Token, undefined, '#clearportx-fees:Token.Token:Token'> &
  damlTypes.ToInterface<Token, never> &
  TokenInterface;

export declare namespace Token {
  export type CreateEvent = damlLedger.CreateEvent<Token, undefined, typeof Token.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<Token, typeof Token.templateId>
  export type Event = damlLedger.Event<Token, undefined, typeof Token.templateId>
  export type QueryResult = damlLedger.QueryResult<Token, undefined, typeof Token.templateId>
}


