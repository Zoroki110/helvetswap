// Generated from AMM/Pool.daml
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

import * as LPToken_LPToken from '../../LPToken/LPToken/module';
import * as Token_Token from '../../Token/Token/module';

export declare type ArchiveAndUpdateReserves = {
  updatedReserveA: damlTypes.Numeric;
  updatedReserveB: damlTypes.Numeric;
};

export declare const ArchiveAndUpdateReserves:
  damlTypes.Serializable<ArchiveAndUpdateReserves> & {
  }
;


export declare type VerifyReserves = {
  poolTokenACid: damlTypes.ContractId<Token_Token.Token>;
  poolTokenBCid: damlTypes.ContractId<Token_Token.Token>;
};

export declare const VerifyReserves:
  damlTypes.Serializable<VerifyReserves> & {
  }
;


export declare type GetSpotPrice = {
};

export declare const GetSpotPrice:
  damlTypes.Serializable<GetSpotPrice> & {
  }
;


export declare type GetSupply = {
};

export declare const GetSupply:
  damlTypes.Serializable<GetSupply> & {
  }
;


export declare type GetReservesForPool = {
};

export declare const GetReservesForPool:
  damlTypes.Serializable<GetReservesForPool> & {
  }
;


export declare type RemoveLiquidity = {
  provider: damlTypes.Party;
  lpTokenCid: damlTypes.ContractId<LPToken_LPToken.LPToken>;
  lpTokenAmount: damlTypes.Numeric;
  minAmountA: damlTypes.Numeric;
  minAmountB: damlTypes.Numeric;
  poolTokenACid: damlTypes.ContractId<Token_Token.Token>;
  poolTokenBCid: damlTypes.ContractId<Token_Token.Token>;
  deadline: damlTypes.Time;
};

export declare const RemoveLiquidity:
  damlTypes.Serializable<RemoveLiquidity> & {
  }
;


export declare type AddLiquidity = {
  provider: damlTypes.Party;
  tokenACid: damlTypes.ContractId<Token_Token.Token>;
  tokenBCid: damlTypes.ContractId<Token_Token.Token>;
  amountA: damlTypes.Numeric;
  amountB: damlTypes.Numeric;
  minLPTokens: damlTypes.Numeric;
  deadline: damlTypes.Time;
};

export declare const AddLiquidity:
  damlTypes.Serializable<AddLiquidity> & {
  }
;


export declare type Pool = {
  poolOperator: damlTypes.Party;
  poolParty: damlTypes.Party;
  lpIssuer: damlTypes.Party;
  issuerA: damlTypes.Party;
  issuerB: damlTypes.Party;
  symbolA: string;
  symbolB: string;
  feeBps: damlTypes.Int;
  poolId: string;
  maxTTL: pkgb70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946.DA.Time.Types.RelTime;
  totalLPSupply: damlTypes.Numeric;
  reserveA: damlTypes.Numeric;
  reserveB: damlTypes.Numeric;
  protocolFeeReceiver: damlTypes.Party;
};

export declare interface PoolInterface {
  AddLiquidity: damlTypes.Choice<Pool, AddLiquidity, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.ContractId<LPToken_LPToken.LPToken>, damlTypes.ContractId<Pool>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  RemoveLiquidity: damlTypes.Choice<Pool, RemoveLiquidity, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple3<damlTypes.ContractId<Token_Token.Token>, damlTypes.ContractId<Token_Token.Token>, damlTypes.ContractId<Pool>>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  GetReservesForPool: damlTypes.Choice<Pool, GetReservesForPool, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.Numeric, damlTypes.Numeric>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  GetSupply: damlTypes.Choice<Pool, GetSupply, damlTypes.Numeric, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  GetSpotPrice: damlTypes.Choice<Pool, GetSpotPrice, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<damlTypes.Numeric, damlTypes.Numeric>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  VerifyReserves: damlTypes.Choice<Pool, VerifyReserves, pkg5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4.DA.Types.Tuple2<boolean, string>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  Archive: damlTypes.Choice<Pool, pkg9e70a8b3510d617f8a136213f33d6a903a10ca0eeec76bb06ba55d1ed9680f69.DA.Internal.Template.Archive, {}, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
  ArchiveAndUpdateReserves: damlTypes.Choice<Pool, ArchiveAndUpdateReserves, damlTypes.ContractId<Pool>, undefined> & damlTypes.ChoiceFrom<damlTypes.Template<Pool, undefined>>;
}
export declare const Pool:
  damlTypes.Template<Pool, undefined, '#clearportx-fees:AMM.Pool:Pool'> &
  damlTypes.ToInterface<Pool, never> &
  PoolInterface;

export declare namespace Pool {
  export type CreateEvent = damlLedger.CreateEvent<Pool, undefined, typeof Pool.templateId>
  export type ArchiveEvent = damlLedger.ArchiveEvent<Pool, typeof Pool.templateId>
  export type Event = damlLedger.Event<Pool, undefined, typeof Pool.templateId>
  export type QueryResult = damlLedger.QueryResult<Pool, undefined, typeof Pool.templateId>
}


