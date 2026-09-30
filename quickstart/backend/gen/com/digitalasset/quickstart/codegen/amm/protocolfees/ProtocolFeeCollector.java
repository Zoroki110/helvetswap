package com.digitalasset.quickstart.codegen.amm.protocolfees;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.ContractFilter;
import com.daml.ledger.javaapi.data.CreateAndExerciseCommand;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.Identifier;
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
import com.digitalasset.quickstart.codegen.da.internal.template.Archive;
import com.digitalasset.quickstart.codegen.da.types.Tuple2;
import com.digitalasset.quickstart.codegen.token.token.Token;
import java.lang.Deprecated;
import java.lang.IllegalArgumentException;
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

public final class ProtocolFeeCollector extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-production", "AMM.ProtocolFees", "ProtocolFeeCollector");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637", "AMM.ProtocolFees", "ProtocolFeeCollector");

  public static final String PACKAGE_ID = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public static final String PACKAGE_NAME = "clearportx-amm-production";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 25});

  public static final Choice<ProtocolFeeCollector, AddProtocolFee, ContractId> CHOICE_AddProtocolFee = 
      Choice.create("AddProtocolFee", value$ -> value$.toValue(), value$ ->
        AddProtocolFee.valueDecoder().decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new AddProtocolFee.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        AddProtocolFee::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<ProtocolFeeCollector, WithdrawProtocolFees, Tuple2<Token.ContractId, ContractId>> CHOICE_WithdrawProtocolFees = 
      Choice.create("WithdrawProtocolFees", value$ -> value$.toValue(), value$ ->
        WithdrawProtocolFees.valueDecoder().decode(value$), value$ ->
        Tuple2.<com.digitalasset.quickstart.codegen.token.token.Token.ContractId,
        com.digitalasset.quickstart.codegen.amm.protocolfees.ProtocolFeeCollector.ContractId>valueDecoder(v$0 ->
          new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        v$1 ->
          new ContractId(v$1.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
        .decode(value$), new WithdrawProtocolFees.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.contractId(Token.ContractId::new), JsonLfDecoders.contractId(ContractId::new)),
        WithdrawProtocolFees::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders::contractId, JsonLfEncoders::contractId));

  public static final Choice<ProtocolFeeCollector, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<ProtocolFeeCollector, GetAccumulatedFees, BigDecimal> CHOICE_GetAccumulatedFees = 
      Choice.create("GetAccumulatedFees", value$ -> value$.toValue(), value$ ->
        GetAccumulatedFees.valueDecoder().decode(value$), value$ ->
        PrimitiveValueDecoders.fromNumeric.decode(value$),
        new GetAccumulatedFees.JsonDecoder$().get(), JsonLfDecoders.numeric(10),
        GetAccumulatedFees::jsonEncoder, JsonLfEncoders::numeric);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, ProtocolFeeCollector> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(ProtocolFeeCollector.PACKAGE_ID, ProtocolFeeCollector.PACKAGE_NAME, ProtocolFeeCollector.PACKAGE_VERSION),
        "com.digitalasset.quickstart.codegen.amm.protocolfees.ProtocolFeeCollector", TEMPLATE_ID,
        ContractId::new, v -> ProtocolFeeCollector.templateValueDecoder().decode(v),
        ProtocolFeeCollector::fromJson, Contract::new, List.of(CHOICE_AddProtocolFee,
        CHOICE_WithdrawProtocolFees, CHOICE_Archive, CHOICE_GetAccumulatedFees));

  public final String treasury;

  public final String poolId;

  public final String tokenSymbol;

  public final String tokenIssuer;

  public final BigDecimal accumulatedFees;

  public final Instant lastCollectionTime;

  public ProtocolFeeCollector(String treasury, String poolId, String tokenSymbol,
      String tokenIssuer, BigDecimal accumulatedFees, Instant lastCollectionTime) {
    this.treasury = treasury;
    this.poolId = poolId;
    this.tokenSymbol = tokenSymbol;
    this.tokenIssuer = tokenIssuer;
    this.accumulatedFees = accumulatedFees;
    this.lastCollectionTime = lastCollectionTime;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(ProtocolFeeCollector.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAddProtocolFee} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseAddProtocolFee(AddProtocolFee arg) {
    return createAnd().exerciseAddProtocolFee(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAddProtocolFee} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseAddProtocolFee(BigDecimal feeAmount,
      Instant swapTime) {
    return createAndExerciseAddProtocolFee(new AddProtocolFee(feeAmount, swapTime));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseWithdrawProtocolFees} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Token.ContractId, ContractId>>> createAndExerciseWithdrawProtocolFees(
      WithdrawProtocolFees arg) {
    return createAnd().exerciseWithdrawProtocolFees(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseWithdrawProtocolFees} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Token.ContractId, ContractId>>> createAndExerciseWithdrawProtocolFees(
      String recipient) {
    return createAndExerciseWithdrawProtocolFees(new WithdrawProtocolFees(recipient));
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
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetAccumulatedFees} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseGetAccumulatedFees(GetAccumulatedFees arg) {
    return createAnd().exerciseGetAccumulatedFees(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseGetAccumulatedFees} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseGetAccumulatedFees() {
    return createAndExerciseGetAccumulatedFees(new GetAccumulatedFees());
  }

  public static Update<Created<ContractId>> create(String treasury, String poolId,
      String tokenSymbol, String tokenIssuer, BigDecimal accumulatedFees,
      Instant lastCollectionTime) {
    return new ProtocolFeeCollector(treasury, poolId, tokenSymbol, tokenIssuer, accumulatedFees,
        lastCollectionTime).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, ProtocolFeeCollector> getCompanion(
      ) {
    return COMPANION;
  }

  public static ValueDecoder<ProtocolFeeCollector> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(6);
    fields.add(new DamlRecord.Field("treasury", new Party(this.treasury)));
    fields.add(new DamlRecord.Field("poolId", new Text(this.poolId)));
    fields.add(new DamlRecord.Field("tokenSymbol", new Text(this.tokenSymbol)));
    fields.add(new DamlRecord.Field("tokenIssuer", new Party(this.tokenIssuer)));
    fields.add(new DamlRecord.Field("accumulatedFees", new Numeric(this.accumulatedFees)));
    fields.add(new DamlRecord.Field("lastCollectionTime", Timestamp.fromInstant(this.lastCollectionTime)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<ProtocolFeeCollector> templateValueDecoder() throws
      IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(6,0, recordValue$);
      String treasury = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String poolId = PrimitiveValueDecoders.fromText.decode(fields$.get(1).getValue());
      String tokenSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      String tokenIssuer = PrimitiveValueDecoders.fromParty.decode(fields$.get(3).getValue());
      BigDecimal accumulatedFees = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(4).getValue());
      Instant lastCollectionTime = PrimitiveValueDecoders.fromTimestamp
          .decode(fields$.get(5).getValue());
      return new ProtocolFeeCollector(treasury, poolId, tokenSymbol, tokenIssuer, accumulatedFees,
          lastCollectionTime);
    } ;
  }

  public static JsonLfDecoder<ProtocolFeeCollector> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("treasury", "poolId", "tokenSymbol", "tokenIssuer", "accumulatedFees", "lastCollectionTime"), name -> {
          switch (name) {
            case "treasury": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolId": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "tokenSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "tokenIssuer": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "accumulatedFees": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "lastCollectionTime": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new ProtocolFeeCollector(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5])));
  }

  public static ProtocolFeeCollector fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("treasury", apply(JsonLfEncoders::party, treasury)),
        JsonLfEncoders.Field.of("poolId", apply(JsonLfEncoders::text, poolId)),
        JsonLfEncoders.Field.of("tokenSymbol", apply(JsonLfEncoders::text, tokenSymbol)),
        JsonLfEncoders.Field.of("tokenIssuer", apply(JsonLfEncoders::party, tokenIssuer)),
        JsonLfEncoders.Field.of("accumulatedFees", apply(JsonLfEncoders::numeric, accumulatedFees)),
        JsonLfEncoders.Field.of("lastCollectionTime", apply(JsonLfEncoders::timestamp, lastCollectionTime)));
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
    if (!(object instanceof ProtocolFeeCollector)) {
      return false;
    }
    ProtocolFeeCollector other = (ProtocolFeeCollector) object;
    return Objects.equals(this.treasury, other.treasury) &&
        Objects.equals(this.poolId, other.poolId) &&
        Objects.equals(this.tokenSymbol, other.tokenSymbol) &&
        Objects.equals(this.tokenIssuer, other.tokenIssuer) &&
        Objects.equals(this.accumulatedFees, other.accumulatedFees) &&
        Objects.equals(this.lastCollectionTime, other.lastCollectionTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.treasury, this.poolId, this.tokenSymbol, this.tokenIssuer,
        this.accumulatedFees, this.lastCollectionTime);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.protocolfees.ProtocolFeeCollector(%s, %s, %s, %s, %s, %s)",
        this.treasury, this.poolId, this.tokenSymbol, this.tokenIssuer, this.accumulatedFees,
        this.lastCollectionTime);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<ProtocolFeeCollector> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, ProtocolFeeCollector, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<ProtocolFeeCollector> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ProtocolFeeCollector> {
    public Contract(ContractId id, ProtocolFeeCollector data, Set<String> signatories,
        Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, ProtocolFeeCollector> getCompanion() {
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
    default Update<Exercised<ContractId>> exerciseAddProtocolFee(AddProtocolFee arg) {
      return makeExerciseCmd(CHOICE_AddProtocolFee, arg);
    }

    default Update<Exercised<ContractId>> exerciseAddProtocolFee(BigDecimal feeAmount,
        Instant swapTime) {
      return exerciseAddProtocolFee(new AddProtocolFee(feeAmount, swapTime));
    }

    default Update<Exercised<Tuple2<Token.ContractId, ContractId>>> exerciseWithdrawProtocolFees(
        WithdrawProtocolFees arg) {
      return makeExerciseCmd(CHOICE_WithdrawProtocolFees, arg);
    }

    default Update<Exercised<Tuple2<Token.ContractId, ContractId>>> exerciseWithdrawProtocolFees(
        String recipient) {
      return exerciseWithdrawProtocolFees(new WithdrawProtocolFees(recipient));
    }

    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }

    default Update<Exercised<BigDecimal>> exerciseGetAccumulatedFees(GetAccumulatedFees arg) {
      return makeExerciseCmd(CHOICE_GetAccumulatedFees, arg);
    }

    default Update<Exercised<BigDecimal>> exerciseGetAccumulatedFees() {
      return exerciseGetAccumulatedFees(new GetAccumulatedFees());
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, ProtocolFeeCollector, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<ProtocolFeeCollector> get() {
      return jsonDecoder();
    }
  }
}
