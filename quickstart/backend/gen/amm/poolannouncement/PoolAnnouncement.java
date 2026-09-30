package amm.poolannouncement;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.ContractFilter;
import com.daml.ledger.javaapi.data.CreateAndExerciseCommand;
import com.daml.ledger.javaapi.data.CreateCommand;
import com.daml.ledger.javaapi.data.CreatedEvent;
import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.ExerciseCommand;
import com.daml.ledger.javaapi.data.Identifier;
import com.daml.ledger.javaapi.data.Int64;
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
import da.time.types.RelTime;
import java.lang.Deprecated;
import java.lang.IllegalArgumentException;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class PoolAnnouncement extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-drain-credit", "AMM.PoolAnnouncement", "PoolAnnouncement");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66", "AMM.PoolAnnouncement", "PoolAnnouncement");

  public static final String PACKAGE_ID = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public static final String PACKAGE_NAME = "clearportx-amm-drain-credit";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 0});

  public static final Choice<PoolAnnouncement, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, PoolAnnouncement> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(PoolAnnouncement.PACKAGE_ID, PoolAnnouncement.PACKAGE_NAME, PoolAnnouncement.PACKAGE_VERSION),
        "amm.poolannouncement.PoolAnnouncement", TEMPLATE_ID, ContractId::new,
        v -> PoolAnnouncement.templateValueDecoder().decode(v), PoolAnnouncement::fromJson,
        Contract::new, List.of(CHOICE_Archive));

  public final String poolOperator;

  public final String poolId;

  public final String symbolA;

  public final String issuerA;

  public final String symbolB;

  public final String issuerB;

  public final Long feeBps;

  public final RelTime maxTTL;

  public final Instant createdAt;

  public PoolAnnouncement(String poolOperator, String poolId, String symbolA, String issuerA,
      String symbolB, String issuerB, Long feeBps, RelTime maxTTL, Instant createdAt) {
    this.poolOperator = poolOperator;
    this.poolId = poolId;
    this.symbolA = symbolA;
    this.issuerA = issuerA;
    this.symbolB = symbolB;
    this.issuerB = issuerB;
    this.feeBps = feeBps;
    this.maxTTL = maxTTL;
    this.createdAt = createdAt;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(PoolAnnouncement.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
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

  public static Update<Created<ContractId>> create(String poolOperator, String poolId,
      String symbolA, String issuerA, String symbolB, String issuerB, Long feeBps, RelTime maxTTL,
      Instant createdAt) {
    return new PoolAnnouncement(poolOperator, poolId, symbolA, issuerA, symbolB, issuerB, feeBps,
        maxTTL, createdAt).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, PoolAnnouncement> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<PoolAnnouncement> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(9);
    fields.add(new DamlRecord.Field("poolOperator", new Party(this.poolOperator)));
    fields.add(new DamlRecord.Field("poolId", new Text(this.poolId)));
    fields.add(new DamlRecord.Field("symbolA", new Text(this.symbolA)));
    fields.add(new DamlRecord.Field("issuerA", new Party(this.issuerA)));
    fields.add(new DamlRecord.Field("symbolB", new Text(this.symbolB)));
    fields.add(new DamlRecord.Field("issuerB", new Party(this.issuerB)));
    fields.add(new DamlRecord.Field("feeBps", new Int64(this.feeBps)));
    fields.add(new DamlRecord.Field("maxTTL", this.maxTTL.toValue()));
    fields.add(new DamlRecord.Field("createdAt", Timestamp.fromInstant(this.createdAt)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<PoolAnnouncement> templateValueDecoder() throws
      IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(9,0, recordValue$);
      String poolOperator = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String poolId = PrimitiveValueDecoders.fromText.decode(fields$.get(1).getValue());
      String symbolA = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      String issuerA = PrimitiveValueDecoders.fromParty.decode(fields$.get(3).getValue());
      String symbolB = PrimitiveValueDecoders.fromText.decode(fields$.get(4).getValue());
      String issuerB = PrimitiveValueDecoders.fromParty.decode(fields$.get(5).getValue());
      Long feeBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(6).getValue());
      RelTime maxTTL = RelTime.valueDecoder().decode(fields$.get(7).getValue());
      Instant createdAt = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(8).getValue());
      return new PoolAnnouncement(poolOperator, poolId, symbolA, issuerA, symbolB, issuerB, feeBps,
          maxTTL, createdAt);
    } ;
  }

  public static JsonLfDecoder<PoolAnnouncement> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("poolOperator", "poolId", "symbolA", "issuerA", "symbolB", "issuerB", "feeBps", "maxTTL", "createdAt"), name -> {
          switch (name) {
            case "poolOperator": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolId": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "symbolA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "issuerA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "symbolB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "issuerB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "feeBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "maxTTL": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(7, new da.time.types.RelTime.JsonDecoder$().get());
            case "createdAt": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(8, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new PoolAnnouncement(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6]), JsonLfDecoders.cast(args[7]), JsonLfDecoders.cast(args[8])));
  }

  public static PoolAnnouncement fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("poolOperator", apply(JsonLfEncoders::party, poolOperator)),
        JsonLfEncoders.Field.of("poolId", apply(JsonLfEncoders::text, poolId)),
        JsonLfEncoders.Field.of("symbolA", apply(JsonLfEncoders::text, symbolA)),
        JsonLfEncoders.Field.of("issuerA", apply(JsonLfEncoders::party, issuerA)),
        JsonLfEncoders.Field.of("symbolB", apply(JsonLfEncoders::text, symbolB)),
        JsonLfEncoders.Field.of("issuerB", apply(JsonLfEncoders::party, issuerB)),
        JsonLfEncoders.Field.of("feeBps", apply(JsonLfEncoders::int64, feeBps)),
        JsonLfEncoders.Field.of("maxTTL", apply(RelTime::jsonEncoder, maxTTL)),
        JsonLfEncoders.Field.of("createdAt", apply(JsonLfEncoders::timestamp, createdAt)));
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
    if (!(object instanceof PoolAnnouncement)) {
      return false;
    }
    PoolAnnouncement other = (PoolAnnouncement) object;
    return Objects.equals(this.poolOperator, other.poolOperator) &&
        Objects.equals(this.poolId, other.poolId) && Objects.equals(this.symbolA, other.symbolA) &&
        Objects.equals(this.issuerA, other.issuerA) &&
        Objects.equals(this.symbolB, other.symbolB) &&
        Objects.equals(this.issuerB, other.issuerB) && Objects.equals(this.feeBps, other.feeBps) &&
        Objects.equals(this.maxTTL, other.maxTTL) &&
        Objects.equals(this.createdAt, other.createdAt);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.poolOperator, this.poolId, this.symbolA, this.issuerA, this.symbolB,
        this.issuerB, this.feeBps, this.maxTTL, this.createdAt);
  }

  @Override
  public String toString() {
    return String.format("amm.poolannouncement.PoolAnnouncement(%s, %s, %s, %s, %s, %s, %s, %s, %s)",
        this.poolOperator, this.poolId, this.symbolA, this.issuerA, this.symbolB, this.issuerB,
        this.feeBps, this.maxTTL, this.createdAt);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<PoolAnnouncement> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, PoolAnnouncement, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<PoolAnnouncement> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, PoolAnnouncement> {
    public Contract(ContractId id, PoolAnnouncement data, Set<String> signatories,
        Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, PoolAnnouncement> getCompanion() {
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
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, PoolAnnouncement, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<PoolAnnouncement> get() {
      return jsonDecoder();
    }
  }
}
