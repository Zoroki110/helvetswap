// Generated from AMM/SwapRequest.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4 from '@daml.js/daml-prim-DA-Types-1.0.0';
import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';
import * as pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946 from '@daml.js/daml-stdlib-DA-Time-Types-1.0.0';

import * as AMM_Pool from '../../AMM/Pool/module';
import * as Token_Token from '../../Token/Token/module';

export declare type ExecuteSwap = {
  poolTokenACid: damlTypes.ContractId<Token_Token.Token>;
  poolTokenBCid: damlTypes.ContractId<Token_Token.Token>;
};

export declare const ExecuteSwap:
  damlTypes.Serializable<ExecuteSwap> & {
  }
;


export declare type SwapReady = {
  trader: damlTypes.Party;
  poolCid: damlTypes.ContractId<AMM_Pool.Pool>;
  poolParty: damlTypes.Party;
  protocolFeeReceiver: damlTypes.Party;
  issuerA: damlTypes.Party;
  issuerB: damlTypes.Party;
  symbolA: string;
  symbolB: string;
  feeBps: damlTypes.Int;
  maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime;
  inputSymbol: string;
  inputAmount: damlTypes.Numeric;
  outputSymbol: string;
  minOutput: damlTypes.Numeric;
  deadline: damlTypes.Time;
  maxPriceImpactBps: damlTypes.Int;
};

export declare interface SwapReadyInterface {
  Archive: damlTypes.Choice<SwapReady, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<SwapReady, undefined>>;
  ExecuteSwap: damlTypes.Choice<SwapReady, ExecuteSwap, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.ContractId<Token_Token.Token>, damlTypes.ContractId<AMM_Pool.Pool>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<SwapReady, undefined>>;
}
export declare const SwapReady:
  damlTypes.Template<SwapReady, undefined, '#clearportx-fees:AMM.SwapRequest:SwapReady'> &
  damlTypes.ToInterface<SwapReady, never> &
  SwapReadyInterface;

export declare namespace SwapReady {
  export type CreateEvent = damlLedger.CreateEvent<SwapReady, undefined, typeof SwapReady.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<SwapReady, typeof SwapReady.templateId>
  export type Event = damlLedger.Event<SwapReady, undefined, typeof SwapReady.templateId>
  export type QueryResult = damlLedger.QueryResult<SwapReady, undefined, typeof SwapReady.templateId>
}



export declare type PrepareSwap = {
  protocolFeeReceiver: damlTypes.Party;
};

export declare const PrepareSwap:
  damlTypes.Serializable<PrepareSwap> & {
  }
;


export declare type CancelSwapRequest = {
};

export declare const CancelSwapRequest:
  damlTypes.Serializable<CancelSwapRequest> & {
  }
;


export declare type SwapRequest = {
  trader: damlTypes.Party;
  poolCid: damlTypes.ContractId<AMM_Pool.Pool>;
  poolParty: damlTypes.Party;
  poolOperator: damlTypes.Party;
  issuerA: damlTypes.Party;
  issuerB: damlTypes.Party;
  symbolA: string;
  symbolB: string;
  feeBps: damlTypes.Int;
  maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime;
  inputTokenCid: damlTypes.ContractId<Token_Token.Token>;
  inputSymbol: string;
  inputAmount: damlTypes.Numeric;
  outputSymbol: string;
  minOutput: damlTypes.Numeric;
  deadline: damlTypes.Time;
  maxPriceImpactBps: damlTypes.Int;
};

export declare interface SwapRequestInterface {
  CancelSwapRequest: damlTypes.Choice<SwapRequest, CancelSwapRequest, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<SwapRequest, undefined>>;
  PrepareSwap: damlTypes.Choice<SwapRequest, PrepareSwap, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.ContractId<SwapReady>, damlTypes.ContractId<Token_Token.Token>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<SwapRequest, undefined>>;
  Archive: damlTypes.Choice<SwapRequest, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<SwapRequest, undefined>>;
}
export declare const SwapRequest:
  damlTypes.Template<SwapRequest, undefined, '#clearportx-fees:AMM.SwapRequest:SwapRequest'> &
  damlTypes.ToInterface<SwapRequest, never> &
  SwapRequestInterface;

export declare namespace SwapRequest {
  export type CreateEvent = damlLedger.CreateEvent<SwapRequest, undefined, typeof SwapRequest.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<SwapRequest, typeof SwapRequest.templateId>
  export type Event = damlLedger.Event<SwapRequest, undefined, typeof SwapRequest.templateId>
  export type QueryResult = damlLedger.QueryResult<SwapRequest, undefined, typeof SwapRequest.templateId>
}


