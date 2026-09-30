package amm.receipt;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import amm.pool.Pool;
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
import da.internal.template.Archive;
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
import token.token.Token;

public final class Receipt extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-drain-credit", "AMM.Receipt", "Receipt");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66", "AMM.Receipt", "Receipt");

  public static final String PACKAGE_ID = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public static final String PACKAGE_NAME = "clearportx-amm-drain-credit";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 0});

  public static final Choice<Receipt, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<Receipt, AcknowledgeReceipt, Unit> CHOICE_AcknowledgeReceipt = 
      Choice.create("AcknowledgeReceipt", value$ -> value$.toValue(), value$ ->
        AcknowledgeReceipt.valueDecoder().decode(value$), value$ -> PrimitiveValueDecoders.fromUnit
        .decode(value$), new AcknowledgeReceipt.JsonDecoder$().get(), JsonLfDecoders.unit,
        AcknowledgeReceipt::jsonEncoder, JsonLfEncoders::unit);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, Receipt> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(Receipt.PACKAGE_ID, Receipt.PACKAGE_NAME, Receipt.PACKAGE_VERSION),
        "amm.receipt.Receipt", TEMPLATE_ID, ContractId::new,
        v -> Receipt.templateValueDecoder().decode(v), Receipt::fromJson, Contract::new,
        List.of(CHOICE_Archive, CHOICE_AcknowledgeReceipt));

  public final String trader;

  public final String poolParty;

  public final String inputSymbol;

  public final String outputSymbol;

  public final BigDecimal amountIn;

  public final BigDecimal amountOut;

  public final BigDecimal protocolFee;

  public final BigDecimal price;

  public final Token.ContractId outputTokenCid;

  public final Pool.ContractId newPoolCid;

  public final Instant timestamp;

  public Receipt(String trader, String poolParty, String inputSymbol, String outputSymbol,
      BigDecimal amountIn, BigDecimal amountOut, BigDecimal protocolFee, BigDecimal price,
      Token.ContractId outputTokenCid, Pool.ContractId newPoolCid, Instant timestamp) {
    this.trader = trader;
    this.poolParty = poolParty;
    this.inputSymbol = inputSymbol;
    this.outputSymbol = outputSymbol;
    this.amountIn = amountIn;
    this.amountOut = amountOut;
    this.protocolFee = protocolFee;
    this.price = price;
    this.outputTokenCid = outputTokenCid;
    this.newPoolCid = newPoolCid;
    this.timestamp = timestamp;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(Receipt.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
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
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAcknowledgeReceipt} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseAcknowledgeReceipt(AcknowledgeReceipt arg) {
    return createAnd().exerciseAcknowledgeReceipt(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseAcknowledgeReceipt} instead
   */
  @Deprecated
  public Update<Exercised<Unit>> createAndExerciseAcknowledgeReceipt() {
    return createAndExerciseAcknowledgeReceipt(new AcknowledgeReceipt());
  }

  public static Update<Created<ContractId>> create(String trader, String poolParty,
      String inputSymbol, String outputSymbol, BigDecimal amountIn, BigDecimal amountOut,
      BigDecimal protocolFee, BigDecimal price, Token.ContractId outputTokenCid,
      Pool.ContractId newPoolCid, Instant timestamp) {
    return new Receipt(trader, poolParty, inputSymbol, outputSymbol, amountIn, amountOut,
        protocolFee, price, outputTokenCid, newPoolCid, timestamp).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, Receipt> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<Receipt> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(11);
    fields.add(new DamlRecord.Field("trader", new Party(this.trader)));
    fields.add(new DamlRecord.Field("poolParty", new Party(this.poolParty)));
    fields.add(new DamlRecord.Field("inputSymbol", new Text(this.inputSymbol)));
    fields.add(new DamlRecord.Field("outputSymbol", new Text(this.outputSymbol)));
    fields.add(new DamlRecord.Field("amountIn", new Numeric(this.amountIn)));
    fields.add(new DamlRecord.Field("amountOut", new Numeric(this.amountOut)));
    fields.add(new DamlRecord.Field("protocolFee", new Numeric(this.protocolFee)));
    fields.add(new DamlRecord.Field("price", new Numeric(this.price)));
    fields.add(new DamlRecord.Field("outputTokenCid", this.outputTokenCid.toValue()));
    fields.add(new DamlRecord.Field("newPoolCid", this.newPoolCid.toValue()));
    fields.add(new DamlRecord.Field("timestamp", Timestamp.fromInstant(this.timestamp)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<Receipt> templateValueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(11,0, recordValue$);
      String trader = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String poolParty = PrimitiveValueDecoders.fromParty.decode(fields$.get(1).getValue());
      String inputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      String outputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(3).getValue());
      BigDecimal amountIn = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(4).getValue());
      BigDecimal amountOut = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(5).getValue());
      BigDecimal protocolFee = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(6).getValue());
      BigDecimal price = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(7).getValue());
      Token.ContractId outputTokenCid =
          new Token.ContractId(fields$.get(8).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected outputTokenCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      Pool.ContractId newPoolCid =
          new Pool.ContractId(fields$.get(9).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected newPoolCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      Instant timestamp = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(10).getValue());
      return new Receipt(trader, poolParty, inputSymbol, outputSymbol, amountIn, amountOut,
          protocolFee, price, outputTokenCid, newPoolCid, timestamp);
    } ;
  }

  public static JsonLfDecoder<Receipt> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("trader", "poolParty", "inputSymbol", "outputSymbol", "amountIn", "amountOut", "protocolFee", "price", "outputTokenCid", "newPoolCid", "timestamp"), name -> {
          switch (name) {
            case "trader": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolParty": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "inputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "outputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "amountIn": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "amountOut": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "protocolFee": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "price": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(7, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "outputTokenCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(8, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(token.token.Token.ContractId::new));
            case "newPoolCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(9, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(amm.pool.Pool.ContractId::new));
            case "timestamp": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(10, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new Receipt(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6]), JsonLfDecoders.cast(args[7]), JsonLfDecoders.cast(args[8]), JsonLfDecoders.cast(args[9]), JsonLfDecoders.cast(args[10])));
  }

  public static Receipt fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("trader", apply(JsonLfEncoders::party, trader)),
        JsonLfEncoders.Field.of("poolParty", apply(JsonLfEncoders::party, poolParty)),
        JsonLfEncoders.Field.of("inputSymbol", apply(JsonLfEncoders::text, inputSymbol)),
        JsonLfEncoders.Field.of("outputSymbol", apply(JsonLfEncoders::text, outputSymbol)),
        JsonLfEncoders.Field.of("amountIn", apply(JsonLfEncoders::numeric, amountIn)),
        JsonLfEncoders.Field.of("amountOut", apply(JsonLfEncoders::numeric, amountOut)),
        JsonLfEncoders.Field.of("protocolFee", apply(JsonLfEncoders::numeric, protocolFee)),
        JsonLfEncoders.Field.of("price", apply(JsonLfEncoders::numeric, price)),
        JsonLfEncoders.Field.of("outputTokenCid", apply(JsonLfEncoders::contractId, outputTokenCid)),
        JsonLfEncoders.Field.of("newPoolCid", apply(JsonLfEncoders::contractId, newPoolCid)),
        JsonLfEncoders.Field.of("timestamp", apply(JsonLfEncoders::timestamp, timestamp)));
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
    if (!(object instanceof Receipt)) {
      return false;
    }
    Receipt other = (Receipt) object;
    return Objects.equals(this.trader, other.trader) &&
        Objects.equals(this.poolParty, other.poolParty) &&
        Objects.equals(this.inputSymbol, other.inputSymbol) &&
        Objects.equals(this.outputSymbol, other.outputSymbol) &&
        Objects.equals(this.amountIn, other.amountIn) &&
        Objects.equals(this.amountOut, other.amountOut) &&
        Objects.equals(this.protocolFee, other.protocolFee) &&
        Objects.equals(this.price, other.price) &&
        Objects.equals(this.outputTokenCid, other.outputTokenCid) &&
        Objects.equals(this.newPoolCid, other.newPoolCid) &&
        Objects.equals(this.timestamp, other.timestamp);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.trader, this.poolParty, this.inputSymbol, this.outputSymbol,
        this.amountIn, this.amountOut, this.protocolFee, this.price, this.outputTokenCid,
        this.newPoolCid, this.timestamp);
  }

  @Override
  public String toString() {
    return String.format("amm.receipt.Receipt(%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
        this.trader, this.poolParty, this.inputSymbol, this.outputSymbol, this.amountIn,
        this.amountOut, this.protocolFee, this.price, this.outputTokenCid, this.newPoolCid,
        this.timestamp);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<Receipt> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Receipt, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<Receipt> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, Receipt> {
    public Contract(ContractId id, Receipt data, Set<String> signatories, Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, Receipt> getCompanion() {
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
    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }

    default Update<Exercised<Unit>> exerciseAcknowledgeReceipt(AcknowledgeReceipt arg) {
      return makeExerciseCmd(CHOICE_AcknowledgeReceipt, arg);
    }

    default Update<Exercised<Unit>> exerciseAcknowledgeReceipt() {
      return exerciseAcknowledgeReceipt(new AcknowledgeReceipt());
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Receipt, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<Receipt> get() {
      return jsonDecoder();
    }
  }
}
