package com.digitalasset.quickstart.codegen.amm.pool;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.ContractFilter;
import com.daml.ledger.javaapi.data.CreateAndExerciseCommand;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlOptional;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.Int64;
import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.PackageVersion;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Template;
import com.daml.ledger.javaapi.data.Text;
import com.daml.ledger.javaapi.data.Unit;
import com.daml.ledger.javaapi.data.Value;
import com.daml.ledger.javaapi.data.codegen.Choice;
import com.daml.ledger.javaapi.data.codegen.ContractCompanion;
import com.daml.ledger.javaapi.data.codegen.ContractTypeCompanion;
import com.daml.ledger.javaapi.data.codegen.Created;
import com.daml.ledger.javaapi.data.codegen.Exercised;
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders;
import com.daml.ledger.javaapi.data.codegen.Update;
import com.daml.ledger.javaapi.data.codegen.ValueDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader;
import com.digitalasset.quickstart.codegen.da.internal.template.Archive;
import com.digitalasset.quickstart.codegen.da.time.types.RelTime;
import com.digitalasset.quickstart.codegen.da.types.Tuple2;
import com.digitalasset.quickstart.codegen.da.types.Tuple3;
import com.digitalasset.quickstart.codegen.lptoken.lptoken.LPToken;
import com.digitalasset.quickstart.codegen.token.token.Token;
import java.lang.Boolean;
import java.lang.Deprecated;
import java.lang.IllegalArgumentException;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class Pool extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-production", "AMM.Pool", "Pool");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637", "AMM.Pool", "Pool");

  public static final String PACKAGE_ID = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public static final String PACKAGE_NAME = "clearportx-amm-production";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 25});

  public static final Choice<Pool, AtomicSwap, Tuple2<Token.ContractId, ContractId>> CHOICE_AtomicSwap = 
      Choice.create("AtomicSwap", value$ -> value$.toValue(), value$ -> AtomicSwap.valueDecoder()
        .decode(value$), value$ ->
        Tuple2.<com.digitalasset.quickstart.codegen.token.token.Token.ContractId,
        com.digitalasset.quickstart.codegen.amm.pool.Pool.ContractId>valueDecoder(v$0 ->
          new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        v$1 ->
          new ContractId(v$1.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
        .decode(value$), new AtomicSwap.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.contractId(Token.ContractId::new), JsonLfDecoders.contractId(ContractId::new)),
        AtomicSwap::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::contractId, JsonLfEncoders::contractId));

  public static final Choice<Pool, EmitTrace, Unit> CHOICE_EmitTrace = 
      Choice.create("EmitTrace", value$ -> value$.toValue(), value$ -> EmitTrace.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new EmitTrace.JsonDecoder$().get(), JsonLfDecoders.unit, EmitTrace::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<Pool, VerifyReserves, Tuple2<Boolean, String>> CHOICE_VerifyReserves = 
      Choice.create("VerifyReserves", value$ -> value$.toValue(), value$ ->
        VerifyReserves.valueDecoder().decode(value$), value$ -> Tuple2.<java.lang.Boolean,
        java.lang.String>valueDecoder(PrimitiveValueDecoders.fromBool,
        PrimitiveValueDecoders.fromText).decode(value$), new VerifyReserves.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.bool, JsonLfDecoders.text),
        VerifyReserves::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::bool, JsonLfEncoders::text));

  public static final Choice<Pool, GetSupply, BigDecimal> CHOICE_GetSupply = 
      Choice.create("GetSupply", value$ -> value$.toValue(), value$ -> GetSupply.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromNumeric.decode(value$),
        new GetSupply.JsonDecoder$().get(), JsonLfDecoders.numeric(10), GetSupply::jsonEncoder,
        JsonLfEncoders::numeric);

  public static final Choice<Pool, GrantVisibility, ContractId> CHOICE_GrantVisibility = 
      Choice.create("GrantVisibility", value$ -> value$.toValue(), value$ ->
        GrantVisibility.valueDecoder().decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new GrantVisibility.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        GrantVisibility::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<Pool, GetReservesForPool, Tuple2<BigDecimal, BigDecimal>> CHOICE_GetReservesForPool = 
      Choice.create("GetReservesForPool", value$ -> value$.toValue(), value$ ->
        GetReservesForPool.valueDecoder().decode(value$), value$ -> Tuple2.<java.math.BigDecimal,
        java.math.BigDecimal>valueDecoder(PrimitiveValueDecoders.fromNumeric,
        PrimitiveValueDecoders.fromNumeric).decode(value$),
        new GetReservesForPool.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.numeric(10), JsonLfDecoders.numeric(10)),
        GetReservesForPool::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::numeric, JsonLfEncoders::numeric));

  public static final Choice<Pool, AddLiquidity, Tuple2<LPToken.ContractId, ContractId>> CHOICE_AddLiquidity = 
      Choice.create("AddLiquidity", value$ -> value$.toValue(), value$ ->
        AddLiquidity.valueDecoder().decode(value$), value$ ->
        Tuple2.<com.digitalasset.quickstart.codegen.lptoken.lptoken.LPToken.ContractId,
        com.digitalasset.quickstart.codegen.amm.pool.Pool.ContractId>valueDecoder(v$0 ->
          new LPToken.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        v$1 ->
          new ContractId(v$1.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
        .decode(value$), new AddLiquidity.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.contractId(LPToken.ContractId::new), JsonLfDecoders.contractId(ContractId::new)),
        AddLiquidity::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::contractId, JsonLfEncoders::contractId));

  public static final Choice<Pool, ArchiveAndUpdateReserves, ContractId> CHOICE_ArchiveAndUpdateReserves = 
      Choice.create("ArchiveAndUpdateReserves", value$ -> value$.toValue(), value$ ->
        ArchiveAndUpdateReserves.valueDecoder().decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new ArchiveAndUpdateReserves.JsonDecoder$().get(),
        JsonLfDecoders.contractId(ContractId::new), ArchiveAndUpdateReserves::jsonEncoder,
        JsonLfEncoders::contractId);

  public static final Choice<Pool, GetSpotPrice, Tuple2<BigDecimal, BigDecimal>> CHOICE_GetSpotPrice = 
      Choice.create("GetSpotPrice", value$ -> value$.toValue(), value$ ->
        GetSpotPrice.valueDecoder().decode(value$), value$ -> Tuple2.<java.math.BigDecimal,
        java.math.BigDecimal>valueDecoder(PrimitiveValueDecoders.fromNumeric,
        PrimitiveValueDecoders.fromNumeric).decode(value$), new GetSpotPrice.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.numeric(10), JsonLfDecoders.numeric(10)),
        GetSpotPrice::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::numeric, JsonLfEncoders::numeric));

  public static final Choice<Pool, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<Pool, RemoveLiquidity, Tuple3<Token.ContractId, Token.ContractId, ContractId>> CHOICE_RemoveLiquidity = 
      Choice.create("RemoveLiquidity", value$ -> value$.toValue(), value$ ->
        RemoveLiquidity.valueDecoder().decode(value$), value$ ->
        Tuple3.<com.digitalasset.quickstart.codegen.token.token.Token.ContractId,
        com.digitalasset.quickstart.codegen.token.token.Token.ContractId,
        com.digitalasset.quickstart.codegen.amm.pool.Pool.ContractId>valueDecoder(v$0 ->
          new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        v$1 ->
          new Token.ContractId(v$1.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        v$2 ->
          new ContractId(v$2.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
        .decode(value$), new RemoveLiquidity.JsonDecoder$().get(),
        new Tuple3.JsonDecoder$().get(JsonLfDecoders.contractId(Token.ContractId::new), JsonLfDecoders.contractId(Token.ContractId::new), JsonLfDecoders.contractId(ContractId::new)),
        RemoveLiquidity::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::contractId, JsonLfEncoders::contractId, JsonLfEncoders::contractId));

  public static final ContractCompanion.WithoutKey<Contract, ContractId, Pool> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(Pool.PACKAGE_ID, Pool.PACKAGE_NAME, Pool.PACKAGE_VERSION),
        "com.digitalasset.quickstart.codegen.amm.pool.Pool", TEMPLATE_ID, ContractId::new,
        v -> Pool.templateValueDecoder().decode(v), Pool::fromJson, Contract::new,
        List.of(CHOICE_GetSpotPrice, CHOICE_VerifyReserves, CHOICE_RemoveLiquidity,
        CHOICE_GetReservesForPool, CHOICE_EmitTrace, CHOICE_GrantVisibility, CHOICE_AtomicSwap,
        CHOICE_Archive, CHOICE_AddLiquidity, CHOICE_GetSupply, CHOICE_ArchiveAndUpdateReserves));

  public final String poolOperator;

  public final String poolParty;

  public final String lpIssuer;

  public final String issuerA;

  public final String issuerB;

  public final String symbolA;

  public final String symbolB;

  public final Long feeBps;

  public final String poolId;

  public final RelTime maxTTL;

  public final BigDecimal totalLPSupply;

  public final BigDecimal reserveA;

  public final BigDecimal reserveB;

  public final Optional<Token.ContractId> tokenACid;

  public final Optional<Token.ContractId> tokenBCid;

  public final String protocolFeeReceiver;

  public final Long maxInBps;

  public final Long maxOutBps;

  public Pool(String poolOperator, String poolParty, String lpIssuer, String issuerA,
      String issuerB, String symbolA, String symbolB, Long feeBps, String poolId, RelTime maxTTL,
      BigDecimal totalLPSupply, BigDecimal reserveA, BigDecimal reserveB,
      Optional<Token.ContractId> tokenACid, Optional<Token.ContractId> tokenBCid,
      String protocolFeeReceiver, Long maxInBps, Long maxOutBps) {
    this.poolOperator = poolOperator;
    this.poolParty = poolParty;
    this.lpIssuer = lpIssuer;
    this.issuerA = issuerA;
    this.issuerB = issuerB;
    this.symbolA = symbolA;
    this.symbolB = symbolB;
    this.feeBps = feeBps;
    this.poolId = poolId;
    this.maxTTL = maxTTL;
    this.totalLPSupply = totalLPSupply;
    this.reserveA = reserveA;
    this.reserveB = reserveB;
    this.tokenACid = tokenACid;
    this.tokenBCid = tokenBCid;
    this.protocolFeeReceiver = protocolFeeReceiver;
    this.maxInBps = maxInBps;
    this.maxOutBps = maxOutBps;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(Pool.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAtomicSwap} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Token.ContractId, ContractId>>> createAndExerciseAtomicSwap(
      AtomicSwap arg) {
    return createAnd().exerciseAtomicSwap(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAtomicSwap} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Token.ContractId, ContractId>>> createAndExerciseAtomicSwap(
      String trader, Token.ContractId traderInputTokenCid, String inputSymbol,
      BigDecimal inputAmount, String outputSymbol, BigDecimal minOutput, Long maxPriceImpactBps,
      Instant deadline) {
    return createAndExerciseAtomicSwap(new AtomicSwap(trader, traderInputTokenCid, inputSymbol,
        inputAmount, outputSymbol, minOutput, maxPriceImpactBps, deadline));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseEmitTrace} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseEmitTrace(EmitTrace arg) {
    return createAnd().exerciseEmitTrace(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseEmitTrace} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseEmitTrace(String message) {
    return createAndExerciseEmitTrace(new EmitTrace(message));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseVerifyReserves} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Boolean, String>>> createAndExerciseVerifyReserves(
      VerifyReserves arg) {
    return createAnd().exerciseVerifyReserves(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseVerifyReserves} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Boolean, String>>> createAndExerciseVerifyReserves() {
    return createAndExerciseVerifyReserves(new VerifyReserves());
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetSupply} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseGetSupply(GetSupply arg) {
    return createAnd().exerciseGetSupply(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetSupply} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseGetSupply() {
    return createAndExerciseGetSupply(new GetSupply());
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGrantVisibility} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseGrantVisibility(GrantVisibility arg) {
    return createAnd().exerciseGrantVisibility(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGrantVisibility} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseGrantVisibility(String party) {
    return createAndExerciseGrantVisibility(new GrantVisibility(party));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetReservesForPool} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> createAndExerciseGetReservesForPool(
      GetReservesForPool arg) {
    return createAnd().exerciseGetReservesForPool(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetReservesForPool} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> createAndExerciseGetReservesForPool() {
    return createAndExerciseGetReservesForPool(new GetReservesForPool());
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAddLiquidity} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<LPToken.ContractId, ContractId>>> createAndExerciseAddLiquidity(
      AddLiquidity arg) {
    return createAnd().exerciseAddLiquidity(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAddLiquidity} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<LPToken.ContractId, ContractId>>> createAndExerciseAddLiquidity(
      String provider, Token.ContractId tokenACid, Token.ContractId tokenBCid, BigDecimal amountA,
      BigDecimal amountB, BigDecimal minLPTokens, Instant deadline) {
    return createAndExerciseAddLiquidity(new AddLiquidity(provider, tokenACid, tokenBCid, amountA,
        amountB, minLPTokens, deadline));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseArchiveAndUpdateReserves} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseArchiveAndUpdateReserves(
      ArchiveAndUpdateReserves arg) {
    return createAnd().exerciseArchiveAndUpdateReserves(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseArchiveAndUpdateReserves} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseArchiveAndUpdateReserves(
      BigDecimal updatedReserveA, BigDecimal updatedReserveB,
      Optional<Token.ContractId> updatedTokenACid, Optional<Token.ContractId> updatedTokenBCid) {
    return createAndExerciseArchiveAndUpdateReserves(new ArchiveAndUpdateReserves(updatedReserveA,
        updatedReserveB, updatedTokenACid, updatedTokenBCid));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetSpotPrice} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> createAndExerciseGetSpotPrice(
      GetSpotPrice arg) {
    return createAnd().exerciseGetSpotPrice(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetSpotPrice} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> createAndExerciseGetSpotPrice() {
    return createAndExerciseGetSpotPrice(new GetSpotPrice());
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseArchive} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseArchive(Archive arg) {
    return createAnd().exerciseArchive(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseArchive} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseArchive() {
    return createAndExerciseArchive(new Archive());
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseRemoveLiquidity} instead
   */
  @Deprecated
  public Update<Exercised<Tuple3<Token.ContractId, Token.ContractId, ContractId>>> createAndExerciseRemoveLiquidity(
      RemoveLiquidity arg) {
    return createAnd().exerciseRemoveLiquidity(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseRemoveLiquidity} instead
   */
  @Deprecated
  public Update<Exercised<Tuple3<Token.ContractId, Token.ContractId, ContractId>>> createAndExerciseRemoveLiquidity(
      String provider, LPToken.ContractId lpTokenCid, BigDecimal lpTokenAmount,
      BigDecimal minAmountA, BigDecimal minAmountB, Instant deadline) {
    return createAndExerciseRemoveLiquidity(new RemoveLiquidity(provider, lpTokenCid, lpTokenAmount,
        minAmountA, minAmountB, deadline));
  }

  public static Update<Created<ContractId>> create(String poolOperator, String poolParty,
      String lpIssuer, String issuerA, String issuerB, String symbolA, String symbolB, Long feeBps,
      String poolId, RelTime maxTTL, BigDecimal totalLPSupply, BigDecimal reserveA,
      BigDecimal reserveB, Optional<Token.ContractId> tokenACid,
      Optional<Token.ContractId> tokenBCid, String protocolFeeReceiver, Long maxInBps,
      Long maxOutBps) {
    return new Pool(poolOperator, poolParty, lpIssuer, issuerA, issuerB, symbolA, symbolB, feeBps,
        poolId, maxTTL, totalLPSupply, reserveA, reserveB, tokenACid, tokenBCid,
        protocolFeeReceiver, maxInBps, maxOutBps).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, Pool> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<Pool> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(18);
    fields.add(new DamlRecord.Field("poolOperator", new Party(this.poolOperator)));
    fields.add(new DamlRecord.Field("poolParty", new Party(this.poolParty)));
    fields.add(new DamlRecord.Field("lpIssuer", new Party(this.lpIssuer)));
    fields.add(new DamlRecord.Field("issuerA", new Party(this.issuerA)));
    fields.add(new DamlRecord.Field("issuerB", new Party(this.issuerB)));
    fields.add(new DamlRecord.Field("symbolA", new Text(this.symbolA)));
    fields.add(new DamlRecord.Field("symbolB", new Text(this.symbolB)));
    fields.add(new DamlRecord.Field("feeBps", new Int64(this.feeBps)));
    fields.add(new DamlRecord.Field("poolId", new Text(this.poolId)));
    fields.add(new DamlRecord.Field("maxTTL", this.maxTTL.toValue()));
    fields.add(new DamlRecord.Field("totalLPSupply", new Numeric(this.totalLPSupply)));
    fields.add(new DamlRecord.Field("reserveA", new Numeric(this.reserveA)));
    fields.add(new DamlRecord.Field("reserveB", new Numeric(this.reserveB)));
    fields.add(new DamlRecord.Field("tokenACid", DamlOptional.of(this.tokenACid.map(v$0 -> v$0.toValue()))));
    fields.add(new DamlRecord.Field("tokenBCid", DamlOptional.of(this.tokenBCid.map(v$0 -> v$0.toValue()))));
    fields.add(new DamlRecord.Field("protocolFeeReceiver", new Party(this.protocolFeeReceiver)));
    fields.add(new DamlRecord.Field("maxInBps", new Int64(this.maxInBps)));
    fields.add(new DamlRecord.Field("maxOutBps", new Int64(this.maxOutBps)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<Pool> templateValueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(18,0, recordValue$);
      String poolOperator = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String poolParty = PrimitiveValueDecoders.fromParty.decode(fields$.get(1).getValue());
      String lpIssuer = PrimitiveValueDecoders.fromParty.decode(fields$.get(2).getValue());
      String issuerA = PrimitiveValueDecoders.fromParty.decode(fields$.get(3).getValue());
      String issuerB = PrimitiveValueDecoders.fromParty.decode(fields$.get(4).getValue());
      String symbolA = PrimitiveValueDecoders.fromText.decode(fields$.get(5).getValue());
      String symbolB = PrimitiveValueDecoders.fromText.decode(fields$.get(6).getValue());
      Long feeBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(7).getValue());
      String poolId = PrimitiveValueDecoders.fromText.decode(fields$.get(8).getValue());
      RelTime maxTTL = RelTime.valueDecoder().decode(fields$.get(9).getValue());
      BigDecimal totalLPSupply = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(10).getValue());
      BigDecimal reserveA = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(11).getValue());
      BigDecimal reserveB = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(12).getValue());
      Optional<Token.ContractId> tokenACid = PrimitiveValueDecoders.fromOptional(v$0 ->
              new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected tokenACid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
          .decode(fields$.get(13).getValue());
      Optional<Token.ContractId> tokenBCid = PrimitiveValueDecoders.fromOptional(v$0 ->
              new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected tokenBCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
          .decode(fields$.get(14).getValue());
      String protocolFeeReceiver = PrimitiveValueDecoders.fromParty
          .decode(fields$.get(15).getValue());
      Long maxInBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(16).getValue());
      Long maxOutBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(17).getValue());
      return new Pool(poolOperator, poolParty, lpIssuer, issuerA, issuerB, symbolA, symbolB, feeBps,
          poolId, maxTTL, totalLPSupply, reserveA, reserveB, tokenACid, tokenBCid,
          protocolFeeReceiver, maxInBps, maxOutBps);
    } ;
  }

  public static JsonLfDecoder<Pool> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("poolOperator", "poolParty", "lpIssuer", "issuerA", "issuerB", "symbolA", "symbolB", "feeBps", "poolId", "maxTTL", "totalLPSupply", "reserveA", "reserveB", "tokenACid", "tokenBCid", "protocolFeeReceiver", "maxInBps", "maxOutBps"), name -> {
          switch (name) {
            case "poolOperator": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolParty": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "lpIssuer": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "issuerA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "issuerB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "symbolA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "symbolB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "feeBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(7, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "poolId": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(8, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "maxTTL": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(9, new com.digitalasset.quickstart.codegen.da.time.types.RelTime.JsonDecoder$().get());
            case "totalLPSupply": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(10, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "reserveA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(11, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "reserveB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(12, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "tokenACid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(13, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.optional(com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new)), java.util.Optional.empty());
            case "tokenBCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(14, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.optional(com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new)), java.util.Optional.empty());
            case "protocolFeeReceiver": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(15, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "maxInBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(16, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "maxOutBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(17, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            default: return null;
          }
        }
        , (Object[] args) -> new Pool(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6]), JsonLfDecoders.cast(args[7]), JsonLfDecoders.cast(args[8]), JsonLfDecoders.cast(args[9]), JsonLfDecoders.cast(args[10]), JsonLfDecoders.cast(args[11]), JsonLfDecoders.cast(args[12]), JsonLfDecoders.cast(args[13]), JsonLfDecoders.cast(args[14]), JsonLfDecoders.cast(args[15]), JsonLfDecoders.cast(args[16]), JsonLfDecoders.cast(args[17])));
  }

  public static Pool fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("poolOperator", apply(JsonLfEncoders::party, poolOperator)),
        JsonLfEncoders.Field.of("poolParty", apply(JsonLfEncoders::party, poolParty)),
        JsonLfEncoders.Field.of("lpIssuer", apply(JsonLfEncoders::party, lpIssuer)),
        JsonLfEncoders.Field.of("issuerA", apply(JsonLfEncoders::party, issuerA)),
        JsonLfEncoders.Field.of("issuerB", apply(JsonLfEncoders::party, issuerB)),
        JsonLfEncoders.Field.of("symbolA", apply(JsonLfEncoders::text, symbolA)),
        JsonLfEncoders.Field.of("symbolB", apply(JsonLfEncoders::text, symbolB)),
        JsonLfEncoders.Field.of("feeBps", apply(JsonLfEncoders::int64, feeBps)),
        JsonLfEncoders.Field.of("poolId", apply(JsonLfEncoders::text, poolId)),
        JsonLfEncoders.Field.of("maxTTL", apply(RelTime::jsonEncoder, maxTTL)),
        JsonLfEncoders.Field.of("totalLPSupply", apply(JsonLfEncoders::numeric, totalLPSupply)),
        JsonLfEncoders.Field.of("reserveA", apply(JsonLfEncoders::numeric, reserveA)),
        JsonLfEncoders.Field.of("reserveB", apply(JsonLfEncoders::numeric, reserveB)),
        JsonLfEncoders.Field.of("tokenACid", apply(JsonLfEncoders.optional(JsonLfEncoders::contractId), tokenACid)),
        JsonLfEncoders.Field.of("tokenBCid", apply(JsonLfEncoders.optional(JsonLfEncoders::contractId), tokenBCid)),
        JsonLfEncoders.Field.of("protocolFeeReceiver", apply(JsonLfEncoders::party, protocolFeeReceiver)),
        JsonLfEncoders.Field.of("maxInBps", apply(JsonLfEncoders::int64, maxInBps)),
        JsonLfEncoders.Field.of("maxOutBps", apply(JsonLfEncoders::int64, maxOutBps)));
  }

  public static ContractFilter<Contract> contractFilter() {
    return ContractFilter.of(COMPANION);
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof Pool)) {
      return false;
    }
    Pool other = (Pool) object;
    return Objects.equals(this.poolOperator, other.poolOperator) &&
        Objects.equals(this.poolParty, other.poolParty) &&
        Objects.equals(this.lpIssuer, other.lpIssuer) &&
        Objects.equals(this.issuerA, other.issuerA) &&
        Objects.equals(this.issuerB, other.issuerB) &&
        Objects.equals(this.symbolA, other.symbolA) &&
        Objects.equals(this.symbolB, other.symbolB) && Objects.equals(this.feeBps, other.feeBps) &&
        Objects.equals(this.poolId, other.poolId) && Objects.equals(this.maxTTL, other.maxTTL) &&
        Objects.equals(this.totalLPSupply, other.totalLPSupply) &&
        Objects.equals(this.reserveA, other.reserveA) &&
        Objects.equals(this.reserveB, other.reserveB) &&
        Objects.equals(this.tokenACid, other.tokenACid) &&
        Objects.equals(this.tokenBCid, other.tokenBCid) &&
        Objects.equals(this.protocolFeeReceiver, other.protocolFeeReceiver) &&
        Objects.equals(this.maxInBps, other.maxInBps) &&
        Objects.equals(this.maxOutBps, other.maxOutBps);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.poolOperator, this.poolParty, this.lpIssuer, this.issuerA,
        this.issuerB, this.symbolA, this.symbolB, this.feeBps, this.poolId, this.maxTTL,
        this.totalLPSupply, this.reserveA, this.reserveB, this.tokenACid, this.tokenBCid,
        this.protocolFeeReceiver, this.maxInBps, this.maxOutBps);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.pool.Pool(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
        this.poolOperator, this.poolParty, this.lpIssuer, this.issuerA, this.issuerB, this.symbolA,
        this.symbolB, this.feeBps, this.poolId, this.maxTTL, this.totalLPSupply, this.reserveA,
        this.reserveB, this.tokenACid, this.tokenBCid, this.protocolFeeReceiver, this.maxInBps,
        this.maxOutBps);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<Pool> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Pool, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<Pool> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, Pool> {
    public Contract(ContractId id, Pool data, Set<String> signatories, Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, Pool> getCompanion() {
      return COMPANION;
    }

    public static Contract fromIdAndRecord(String contractId, DamlRecord record$,
        Set<String> signatories, Set<String> observers) {
      return COMPANION.fromIdAndRecord(contractId, record$, signatories, observers);
    }

    public static Contract fromCreatedEvent(CreatedEvent event) {
      return COMPANION.fromCreatedEvent(event);
    }
  }

  public interface Exercises<Cmd> extends com.daml.ledger.javaapi.data.codegen.Exercises.Archivable<Cmd> {
    default Update<Exercised<Tuple2<Token.ContractId, ContractId>>> exerciseAtomicSwap(
        AtomicSwap arg) {
      return makeExerciseCmd(CHOICE_AtomicSwap, arg);
    }

    default Update<Exercised<Tuple2<Token.ContractId, ContractId>>> exerciseAtomicSwap(
        String trader, Token.ContractId traderInputTokenCid, String inputSymbol,
        BigDecimal inputAmount, String outputSymbol, BigDecimal minOutput, Long maxPriceImpactBps,
        Instant deadline) {
      return exerciseAtomicSwap(new AtomicSwap(trader, traderInputTokenCid, inputSymbol,
          inputAmount, outputSymbol, minOutput, maxPriceImpactBps, deadline));
    }

    default Update<Exercised<Unit>> exerciseEmitTrace(EmitTrace arg) {
      return makeExerciseCmd(CHOICE_EmitTrace, arg);
    }

    default Update<Exercised<Unit>> exerciseEmitTrace(String message) {
      return exerciseEmitTrace(new EmitTrace(message));
    }

    default Update<Exercised<Tuple2<Boolean, String>>> exerciseVerifyReserves(VerifyReserves arg) {
      return makeExerciseCmd(CHOICE_VerifyReserves, arg);
    }

    default Update<Exercised<Tuple2<Boolean, String>>> exerciseVerifyReserves() {
      return exerciseVerifyReserves(new VerifyReserves());
    }

    default Update<Exercised<BigDecimal>> exerciseGetSupply(GetSupply arg) {
      return makeExerciseCmd(CHOICE_GetSupply, arg);
    }

    default Update<Exercised<BigDecimal>> exerciseGetSupply() {
      return exerciseGetSupply(new GetSupply());
    }

    default Update<Exercised<ContractId>> exerciseGrantVisibility(GrantVisibility arg) {
      return makeExerciseCmd(CHOICE_GrantVisibility, arg);
    }

    default Update<Exercised<ContractId>> exerciseGrantVisibility(String party) {
      return exerciseGrantVisibility(new GrantVisibility(party));
    }

    default Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> exerciseGetReservesForPool(
        GetReservesForPool arg) {
      return makeExerciseCmd(CHOICE_GetReservesForPool, arg);
    }

    default Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> exerciseGetReservesForPool() {
      return exerciseGetReservesForPool(new GetReservesForPool());
    }

    default Update<Exercised<Tuple2<LPToken.ContractId, ContractId>>> exerciseAddLiquidity(
        AddLiquidity arg) {
      return makeExerciseCmd(CHOICE_AddLiquidity, arg);
    }

    default Update<Exercised<Tuple2<LPToken.ContractId, ContractId>>> exerciseAddLiquidity(
        String provider, Token.ContractId tokenACid, Token.ContractId tokenBCid, BigDecimal amountA,
        BigDecimal amountB, BigDecimal minLPTokens, Instant deadline) {
      return exerciseAddLiquidity(new AddLiquidity(provider, tokenACid, tokenBCid, amountA, amountB,
          minLPTokens, deadline));
    }

    default Update<Exercised<ContractId>> exerciseArchiveAndUpdateReserves(
        ArchiveAndUpdateReserves arg) {
      return makeExerciseCmd(CHOICE_ArchiveAndUpdateReserves, arg);
    }

    default Update<Exercised<ContractId>> exerciseArchiveAndUpdateReserves(
        BigDecimal updatedReserveA, BigDecimal updatedReserveB,
        Optional<Token.ContractId> updatedTokenACid, Optional<Token.ContractId> updatedTokenBCid) {
      return exerciseArchiveAndUpdateReserves(new ArchiveAndUpdateReserves(updatedReserveA,
          updatedReserveB, updatedTokenACid, updatedTokenBCid));
    }

    default Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> exerciseGetSpotPrice(
        GetSpotPrice arg) {
      return makeExerciseCmd(CHOICE_GetSpotPrice, arg);
    }

    default Update<Exercised<Tuple2<BigDecimal, BigDecimal>>> exerciseGetSpotPrice() {
      return exerciseGetSpotPrice(new GetSpotPrice());
    }

    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }

    default Update<Exercised<Tuple3<Token.ContractId, Token.ContractId, ContractId>>> exerciseRemoveLiquidity(
        RemoveLiquidity arg) {
      return makeExerciseCmd(CHOICE_RemoveLiquidity, arg);
    }

    default Update<Exercised<Tuple3<Token.ContractId, Token.ContractId, ContractId>>> exerciseRemoveLiquidity(
        String provider, LPToken.ContractId lpTokenCid, BigDecimal lpTokenAmount,
        BigDecimal minAmountA, BigDecimal minAmountB, Instant deadline) {
      return exerciseRemoveLiquidity(new RemoveLiquidity(provider, lpTokenCid, lpTokenAmount,
          minAmountA, minAmountB, deadline));
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Pool, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<Pool> get() {
      return jsonDecoder();
    }
  }
}
