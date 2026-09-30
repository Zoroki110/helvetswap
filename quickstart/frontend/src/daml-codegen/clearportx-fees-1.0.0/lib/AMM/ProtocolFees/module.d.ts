// Generated from AMM/ProtocolFees.daml
/* eslint-disable @typescript-eslint/camelcase */
/* eslint-disable @typescript-eslint/no-namespace */
/* eslint-disable @typescript-eslint/no-use-before-define */
import * as jtv from '@mojotech/json-type-validation';
import * as damlTypes from '@daml/types';
/* eslint-disable-next-line @typescript-eslint/no-unused-vars */
import * as damlLedger from '@daml/ledger';

import * as pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4 from '@daml.js/daml-prim-DA-Types-1.0.0';
import * as pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69 from '@daml.js/ghc-stdlib-DA-Internal-Template-1.0.0';

import * as Token_Token from '../../Token/Token/module';

export declare type GetAccumulatedFees = {
};

export declare const GetAccumulatedFees:
  damlTypes.Serializable<GetAccumulatedFees> & {
  }
;


export declare type WithdrawProtocolFees = {
  recipient: damlTypes.Party;
};

export declare const WithdrawProtocolFees:
  damlTypes.Serializable<WithdrawProtocolFees> & {
  }
;


export declare type AddProtocolFee = {
  feeAmount: damlTypes.Numeric;
  swapTime: damlTypes.Time;
};

export declare const AddProtocolFee:
  damlTypes.Serializable<AddProtocolFee> & {
  }
;


export declare type ProtocolFeeCollector = {
  treasury: damlTypes.Party;
  poolId: string;
  tokenSymbol: string;
  tokenIssuer: damlTypes.Party;
  accumulatedFees: damlTypes.Numeric;
  lastCollectionTime: damlTypes.Time;
};

export declare interface ProtocolFeeCollectorInterface {
  AddProtocolFee: damlTypes.Choice<ProtocolFeeCollector, AddProtocolFee, damlTypes.ContractId<ProtocolFeeCollector>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<ProtocolFeeCollector, undefined>>;
  WithdrawProtocolFees: damlTypes.Choice<ProtocolFeeCollector, WithdrawProtocolFees, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.ContractId<Token_Token.Token>, damlTypes.ContractId<ProtocolFeeCollector>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<ProtocolFeeCollector, undefined>>;
  Archive: damlTypes.Choice<ProtocolFeeCollector, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<ProtocolFeeCollector, undefined>>;
  GetAccumulatedFees: damlTypes.Choice<ProtocolFeeCollector, GetAccumulatedFees, damlTypes.Numeric, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<ProtocolFeeCollector, undefined>>;
}
export declare const ProtocolFeeCollector:
  damlTypes.Template<ProtocolFeeCollector, undefined, '#clearportx-fees:AMM.ProtocolFees:ProtocolFeeCollector'> &
  damlTypes.ToInterface<ProtocolFeeCollector, never> &
  ProtocolFeeCollectorInterface;

export declare namespace ProtocolFeeCollector {
  export type CreateEvent = damlLedger.CreateEvent<ProtocolFeeCollector, undefined, typeof ProtocolFeeCollector.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<ProtocolFeeCollector, typeof ProtocolFeeCollector.templateId>
  export type Event = damlLedger.Event<ProtocolFeeCollector, undefined, typeof ProtocolFeeCollector.templateId>
  export type QueryResult = damlLedger.QueryResult<ProtocolFeeCollector, undefined, typeof ProtocolFeeCollector.templateId>
}


