package com.digitalasset.quickstart.codegen.amm.atomicswap;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.ContractFilter;
import com.daml.ledger.javaapi.data.CreateAndExerciseCommand;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.Int64;
import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.PackageVersion;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Template;
import com.daml.ledger.javaapi.data.Text;
import com.daml.ledger.javaapi.data.Timestamp;
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
import com.digitalasset.quickstart.codegen.amm.pool.Pool;
import com.digitalasset.quickstart.codegen.amm.receipt.Receipt;
import com.digitalasset.quickstart.codegen.da.internal.template.Archive;
import com.digitalasset.quickstart.codegen.da.time.types.RelTime;
import com.digitalasset.quickstart.codegen.token.token.Token;
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
import java.util.Set;

public final class AtomicSwapProposal extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-production", "AMM.AtomicSwap", "AtomicSwapProposal");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637", "AMM.AtomicSwap", "AtomicSwapProposal");

  public static final String PACKAGE_ID = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public static final String PACKAGE_NAME = "clearportx-amm-production";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 25});

  public static final Choice<AtomicSwapProposal, ExecuteAtomicSwap, Receipt.ContractId> CHOICE_ExecuteAtomicSwap = 
      Choice.create("ExecuteAtomicSwap", value$ -> value$.toValue(), value$ ->
        ExecuteAtomicSwap.valueDecoder().decode(value$), value$ ->
        new Receipt.ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new ExecuteAtomicSwap.JsonDecoder$().get(),
        JsonLfDecoders.contractId(Receipt.ContractId::new), ExecuteAtomicSwap::jsonEncoder,
        JsonLfEncoders::contractId);

  public static final Choice<AtomicSwapProposal, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, AtomicSwapProposal> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(AtomicSwapProposal.PACKAGE_ID, AtomicSwapProposal.PACKAGE_NAME, AtomicSwapProposal.PACKAGE_VERSION),
        "com.digitalasset.quickstart.codegen.amm.atomicswap.AtomicSwapProposal", TEMPLATE_ID,
        ContractId::new, v -> AtomicSwapProposal.templateValueDecoder().decode(v),
        AtomicSwapProposal::fromJson, Contract::new, List.of(CHOICE_ExecuteAtomicSwap,
        CHOICE_Archive));

  public final String trader;

  public final Pool.ContractId poolCid;

  public final String poolParty;

  public final String poolOperator;

  public final String issuerA;

  public final String issuerB;

  public final String symbolA;

  public final String symbolB;

  public final Long feeBps;

  public final RelTime maxTTL;

  public final String protocolFeeReceiver;

  public final Token.ContractId traderInputTokenCid;

  public final String inputSymbol;

  public final BigDecimal inputAmount;

  public final String outputSymbol;

  public final BigDecimal minOutput;

  public final Long maxPriceImpactBps;

  public final Instant deadline;

  public AtomicSwapProposal(String trader, Pool.ContractId poolCid, String poolParty,
      String poolOperator, String issuerA, String issuerB, String symbolA, String symbolB,
      Long feeBps, RelTime maxTTL, String protocolFeeReceiver, Token.ContractId traderInputTokenCid,
      String inputSymbol, BigDecimal inputAmount, String outputSymbol, BigDecimal minOutput,
      Long maxPriceImpactBps, Instant deadline) {
    this.trader = trader;
    this.poolCid = poolCid;
    this.poolParty = poolParty;
    this.poolOperator = poolOperator;
    this.issuerA = issuerA;
    this.issuerB = issuerB;
    this.symbolA = symbolA;
    this.symbolB = symbolB;
    this.feeBps = feeBps;
    this.maxTTL = maxTTL;
    this.protocolFeeReceiver = protocolFeeReceiver;
    this.traderInputTokenCid = traderInputTokenCid;
    this.inputSymbol = inputSymbol;
    this.inputAmount = inputAmount;
    this.outputSymbol = outputSymbol;
    this.minOutput = minOutput;
    this.maxPriceImpactBps = maxPriceImpactBps;
    this.deadline = deadline;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(AtomicSwapProposal.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseExecuteAtomicSwap} instead
   */
  @Deprecated
  public Update<Exercised<Receipt.ContractId>> createAndExerciseExecuteAtomicSwap(
      ExecuteAtomicSwap arg) {
    return createAnd().exerciseExecuteAtomicSwap(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseExecuteAtomicSwap} instead
   */
  @Deprecated
  public Update<Exercised<Receipt.ContractId>> createAndExerciseExecuteAtomicSwap() {
    return createAndExerciseExecuteAtomicSwap(new ExecuteAtomicSwap());
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

  public static Update<Created<ContractId>> create(String trader, Pool.ContractId poolCid,
      String poolParty, String poolOperator, String issuerA, String issuerB, String symbolA,
      String symbolB, Long feeBps, RelTime maxTTL, String protocolFeeReceiver,
      Token.ContractId traderInputTokenCid, String inputSymbol, BigDecimal inputAmount,
      String outputSymbol, BigDecimal minOutput, Long maxPriceImpactBps, Instant deadline) {
    return new AtomicSwapProposal(trader, poolCid, poolParty, poolOperator, issuerA, issuerB,
        symbolA, symbolB, feeBps, maxTTL, protocolFeeReceiver, traderInputTokenCid, inputSymbol,
        inputAmount, outputSymbol, minOutput, maxPriceImpactBps, deadline).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, AtomicSwapProposal> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<AtomicSwapProposal> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(18);
    fields.add(new DamlRecord.Field("trader", new Party(this.trader)));
    fields.add(new DamlRecord.Field("poolCid", this.poolCid.toValue()));
    fields.add(new DamlRecord.Field("poolParty", new Party(this.poolParty)));
    fields.add(new DamlRecord.Field("poolOperator", new Party(this.poolOperator)));
    fields.add(new DamlRecord.Field("issuerA", new Party(this.issuerA)));
    fields.add(new DamlRecord.Field("issuerB", new Party(this.issuerB)));
    fields.add(new DamlRecord.Field("symbolA", new Text(this.symbolA)));
    fields.add(new DamlRecord.Field("symbolB", new Text(this.symbolB)));
    fields.add(new DamlRecord.Field("feeBps", new Int64(this.feeBps)));
    fields.add(new DamlRecord.Field("maxTTL", this.maxTTL.toValue()));
    fields.add(new DamlRecord.Field("protocolFeeReceiver", new Party(this.protocolFeeReceiver)));
    fields.add(new DamlRecord.Field("traderInputTokenCid", this.traderInputTokenCid.toValue()));
    fields.add(new DamlRecord.Field("inputSymbol", new Text(this.inputSymbol)));
    fields.add(new DamlRecord.Field("inputAmount", new Numeric(this.inputAmount)));
    fields.add(new DamlRecord.Field("outputSymbol", new Text(this.outputSymbol)));
    fields.add(new DamlRecord.Field("minOutput", new Numeric(this.minOutput)));
    fields.add(new DamlRecord.Field("maxPriceImpactBps", new Int64(this.maxPriceImpactBps)));
    fields.add(new DamlRecord.Field("deadline", Timestamp.fromInstant(this.deadline)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<AtomicSwapProposal> templateValueDecoder() throws
      IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(18,0, recordValue$);
      String trader = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      Pool.ContractId poolCid =
          new Pool.ContractId(fields$.get(1).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected poolCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      String poolParty = PrimitiveValueDecoders.fromParty.decode(fields$.get(2).getValue());
      String poolOperator = PrimitiveValueDecoders.fromParty.decode(fields$.get(3).getValue());
      String issuerA = PrimitiveValueDecoders.fromParty.decode(fields$.get(4).getValue());
      String issuerB = PrimitiveValueDecoders.fromParty.decode(fields$.get(5).getValue());
      String symbolA = PrimitiveValueDecoders.fromText.decode(fields$.get(6).getValue());
      String symbolB = PrimitiveValueDecoders.fromText.decode(fields$.get(7).getValue());
      Long feeBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(8).getValue());
      RelTime maxTTL = RelTime.valueDecoder().decode(fields$.get(9).getValue());
      String protocolFeeReceiver = PrimitiveValueDecoders.fromParty
          .decode(fields$.get(10).getValue());
      Token.ContractId traderInputTokenCid =
          new Token.ContractId(fields$.get(11).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected traderInputTokenCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      String inputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(12).getValue());
      BigDecimal inputAmount = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(13).getValue());
      String outputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(14).getValue());
      BigDecimal minOutput = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(15).getValue());
      Long maxPriceImpactBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(16).getValue());
      Instant deadline = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(17).getValue());
      return new AtomicSwapProposal(trader, poolCid, poolParty, poolOperator, issuerA, issuerB,
          symbolA, symbolB, feeBps, maxTTL, protocolFeeReceiver, traderInputTokenCid, inputSymbol,
          inputAmount, outputSymbol, minOutput, maxPriceImpactBps, deadline);
    } ;
  }

  public static JsonLfDecoder<AtomicSwapProposal> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("trader", "poolCid", "poolParty", "poolOperator", "issuerA", "issuerB", "symbolA", "symbolB", "feeBps", "maxTTL", "protocolFeeReceiver", "traderInputTokenCid", "inputSymbol", "inputAmount", "outputSymbol", "minOutput", "maxPriceImpactBps", "deadline"), name -> {
          switch (name) {
            case "trader": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.amm.pool.Pool.ContractId::new));
            case "poolParty": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolOperator": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "issuerA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "issuerB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "symbolA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "symbolB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(7, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "feeBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(8, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "maxTTL": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(9, new com.digitalasset.quickstart.codegen.da.time.types.RelTime.JsonDecoder$().get());
            case "protocolFeeReceiver": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(10, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "traderInputTokenCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(11, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new));
            case "inputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(12, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "inputAmount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(13, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "outputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(14, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "minOutput": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(15, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "maxPriceImpactBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(16, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "deadline": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(17, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new AtomicSwapProposal(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6]), JsonLfDecoders.cast(args[7]), JsonLfDecoders.cast(args[8]), JsonLfDecoders.cast(args[9]), JsonLfDecoders.cast(args[10]), JsonLfDecoders.cast(args[11]), JsonLfDecoders.cast(args[12]), JsonLfDecoders.cast(args[13]), JsonLfDecoders.cast(args[14]), JsonLfDecoders.cast(args[15]), JsonLfDecoders.cast(args[16]), JsonLfDecoders.cast(args[17])));
  }

  public static AtomicSwapProposal fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("trader", apply(JsonLfEncoders::party, trader)),
        JsonLfEncoders.Field.of("poolCid", apply(JsonLfEncoders::contractId, poolCid)),
        JsonLfEncoders.Field.of("poolParty", apply(JsonLfEncoders::party, poolParty)),
        JsonLfEncoders.Field.of("poolOperator", apply(JsonLfEncoders::party, poolOperator)),
        JsonLfEncoders.Field.of("issuerA", apply(JsonLfEncoders::party, issuerA)),
        JsonLfEncoders.Field.of("issuerB", apply(JsonLfEncoders::party, issuerB)),
        JsonLfEncoders.Field.of("symbolA", apply(JsonLfEncoders::text, symbolA)),
        JsonLfEncoders.Field.of("symbolB", apply(JsonLfEncoders::text, symbolB)),
        JsonLfEncoders.Field.of("feeBps", apply(JsonLfEncoders::int64, feeBps)),
        JsonLfEncoders.Field.of("maxTTL", apply(RelTime::jsonEncoder, maxTTL)),
        JsonLfEncoders.Field.of("protocolFeeReceiver", apply(JsonLfEncoders::party, protocolFeeReceiver)),
        JsonLfEncoders.Field.of("traderInputTokenCid", apply(JsonLfEncoders::contractId, traderInputTokenCid)),
        JsonLfEncoders.Field.of("inputSymbol", apply(JsonLfEncoders::text, inputSymbol)),
        JsonLfEncoders.Field.of("inputAmount", apply(JsonLfEncoders::numeric, inputAmount)),
        JsonLfEncoders.Field.of("outputSymbol", apply(JsonLfEncoders::text, outputSymbol)),
        JsonLfEncoders.Field.of("minOutput", apply(JsonLfEncoders::numeric, minOutput)),
        JsonLfEncoders.Field.of("maxPriceImpactBps", apply(JsonLfEncoders::int64, maxPriceImpactBps)),
        JsonLfEncoders.Field.of("deadline", apply(JsonLfEncoders::timestamp, deadline)));
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
    if (!(object instanceof AtomicSwapProposal)) {
      return false;
    }
    AtomicSwapProposal other = (AtomicSwapProposal) object;
    return Objects.equals(this.trader, other.trader) &&
        Objects.equals(this.poolCid, other.poolCid) &&
        Objects.equals(this.poolParty, other.poolParty) &&
        Objects.equals(this.poolOperator, other.poolOperator) &&
        Objects.equals(this.issuerA, other.issuerA) &&
        Objects.equals(this.issuerB, other.issuerB) &&
        Objects.equals(this.symbolA, other.symbolA) &&
        Objects.equals(this.symbolB, other.symbolB) && Objects.equals(this.feeBps, other.feeBps) &&
        Objects.equals(this.maxTTL, other.maxTTL) &&
        Objects.equals(this.protocolFeeReceiver, other.protocolFeeReceiver) &&
        Objects.equals(this.traderInputTokenCid, other.traderInputTokenCid) &&
        Objects.equals(this.inputSymbol, other.inputSymbol) &&
        Objects.equals(this.inputAmount, other.inputAmount) &&
        Objects.equals(this.outputSymbol, other.outputSymbol) &&
        Objects.equals(this.minOutput, other.minOutput) &&
        Objects.equals(this.maxPriceImpactBps, other.maxPriceImpactBps) &&
        Objects.equals(this.deadline, other.deadline);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.trader, this.poolCid, this.poolParty, this.poolOperator, this.issuerA,
        this.issuerB, this.symbolA, this.symbolB, this.feeBps, this.maxTTL,
        this.protocolFeeReceiver, this.traderInputTokenCid, this.inputSymbol, this.inputAmount,
        this.outputSymbol, this.minOutput, this.maxPriceImpactBps, this.deadline);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.atomicswap.AtomicSwapProposal(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
        this.trader, this.poolCid, this.poolParty, this.poolOperator, this.issuerA, this.issuerB,
        this.symbolA, this.symbolB, this.feeBps, this.maxTTL, this.protocolFeeReceiver,
        this.traderInputTokenCid, this.inputSymbol, this.inputAmount, this.outputSymbol,
        this.minOutput, this.maxPriceImpactBps, this.deadline);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<AtomicSwapProposal> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, AtomicSwapProposal, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<AtomicSwapProposal> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, AtomicSwapProposal> {
    public Contract(ContractId id, AtomicSwapProposal data, Set<String> signatories,
        Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, AtomicSwapProposal> getCompanion() {
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
    default Update<Exercised<Receipt.ContractId>> exerciseExecuteAtomicSwap(ExecuteAtomicSwap arg) {
      return makeExerciseCmd(CHOICE_ExecuteAtomicSwap, arg);
    }

    default Update<Exercised<Receipt.ContractId>> exerciseExecuteAtomicSwap() {
      return exerciseExecuteAtomicSwap(new ExecuteAtomicSwap());
    }

    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, AtomicSwapProposal, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<AtomicSwapProposal> get() {
      return jsonDecoder();
    }
  }
}
