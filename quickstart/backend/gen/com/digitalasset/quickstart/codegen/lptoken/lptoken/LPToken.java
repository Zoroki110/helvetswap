package com.digitalasset.quickstart.codegen.lptoken.lptoken;

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
import java.lang.Deprecated;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class LPToken extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-production", "LPToken.LPToken", "LPToken");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637", "LPToken.LPToken", "LPToken");

  public static final String PACKAGE_ID = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public static final String PACKAGE_NAME = "clearportx-amm-production";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 25});

  public static final Choice<LPToken, Transfer, ContractId> CHOICE_Transfer = 
      Choice.create("Transfer", value$ -> value$.toValue(), value$ -> Transfer.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Transfer.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Transfer::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<LPToken, Credit, ContractId> CHOICE_Credit = 
      Choice.create("Credit", value$ -> value$.toValue(), value$ -> Credit.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Credit.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Credit::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<LPToken, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<LPToken, Burn, BigDecimal> CHOICE_Burn = 
      Choice.create("Burn", value$ -> value$.toValue(), value$ -> Burn.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromNumeric.decode(value$),
        new Burn.JsonDecoder$().get(), JsonLfDecoders.numeric(10), Burn::jsonEncoder,
        JsonLfEncoders::numeric);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, LPToken> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(LPToken.PACKAGE_ID, LPToken.PACKAGE_NAME, LPToken.PACKAGE_VERSION),
        "com.digitalasset.quickstart.codegen.lptoken.lptoken.LPToken", TEMPLATE_ID, ContractId::new,
        v -> LPToken.templateValueDecoder().decode(v), LPToken::fromJson, Contract::new,
        List.of(CHOICE_Transfer, CHOICE_Credit, CHOICE_Archive, CHOICE_Burn));

  public final String issuer;

  public final String owner;

  public final String poolId;

  public final BigDecimal amount;

  public LPToken(String issuer, String owner, String poolId, BigDecimal amount) {
    this.issuer = issuer;
    this.owner = owner;
    this.poolId = poolId;
    this.amount = amount;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(LPToken.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseTransfer} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseTransfer(Transfer arg) {
    return createAnd().exerciseTransfer(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseTransfer} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseTransfer(String recipient, BigDecimal qty) {
    return createAndExerciseTransfer(new Transfer(recipient, qty));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseCredit} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseCredit(Credit arg) {
    return createAnd().exerciseCredit(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseCredit} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseCredit(BigDecimal qty) {
    return createAndExerciseCredit(new Credit(qty));
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
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseBurn} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseBurn(Burn arg) {
    return createAnd().exerciseBurn(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseBurn} instead
   */
  @Deprecated
  public Update<Exercised<BigDecimal>> createAndExerciseBurn(BigDecimal qty) {
    return createAndExerciseBurn(new Burn(qty));
  }

  public static Update<Created<ContractId>> create(String issuer, String owner, String poolId,
      BigDecimal amount) {
    return new LPToken(issuer, owner, poolId, amount).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, LPToken> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<LPToken> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(4);
    fields.add(new DamlRecord.Field("issuer", new Party(this.issuer)));
    fields.add(new DamlRecord.Field("owner", new Party(this.owner)));
    fields.add(new DamlRecord.Field("poolId", new Text(this.poolId)));
    fields.add(new DamlRecord.Field("amount", new Numeric(this.amount)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<LPToken> templateValueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(4,0, recordValue$);
      String issuer = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String owner = PrimitiveValueDecoders.fromParty.decode(fields$.get(1).getValue());
      String poolId = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      BigDecimal amount = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(3).getValue());
      return new LPToken(issuer, owner, poolId, amount);
    } ;
  }

  public static JsonLfDecoder<LPToken> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("issuer", "owner", "poolId", "amount"), name -> {
          switch (name) {
            case "issuer": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "owner": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "poolId": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "amount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            default: return null;
          }
        }
        , (Object[] args) -> new LPToken(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3])));
  }

  public static LPToken fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("issuer", apply(JsonLfEncoders::party, issuer)),
        JsonLfEncoders.Field.of("owner", apply(JsonLfEncoders::party, owner)),
        JsonLfEncoders.Field.of("poolId", apply(JsonLfEncoders::text, poolId)),
        JsonLfEncoders.Field.of("amount", apply(JsonLfEncoders::numeric, amount)));
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
    if (!(object instanceof LPToken)) {
      return false;
    }
    LPToken other = (LPToken) object;
    return Objects.equals(this.issuer, other.issuer) && Objects.equals(this.owner, other.owner) &&
        Objects.equals(this.poolId, other.poolId) && Objects.equals(this.amount, other.amount);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.issuer, this.owner, this.poolId, this.amount);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.lptoken.lptoken.LPToken(%s, %s, %s, %s)",
        this.issuer, this.owner, this.poolId, this.amount);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<LPToken> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, LPToken, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<LPToken> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, LPToken> {
    public Contract(ContractId id, LPToken data, Set<String> signatories, Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, LPToken> getCompanion() {
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
    default Update<Exercised<ContractId>> exerciseTransfer(Transfer arg) {
      return makeExerciseCmd(CHOICE_Transfer, arg);
    }

    default Update<Exercised<ContractId>> exerciseTransfer(String recipient, BigDecimal qty) {
      return exerciseTransfer(new Transfer(recipient, qty));
    }

    default Update<Exercised<ContractId>> exerciseCredit(Credit arg) {
      return makeExerciseCmd(CHOICE_Credit, arg);
    }

    default Update<Exercised<ContractId>> exerciseCredit(BigDecimal qty) {
      return exerciseCredit(new Credit(qty));
    }

    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }

    default Update<Exercised<BigDecimal>> exerciseBurn(Burn arg) {
      return makeExerciseCmd(CHOICE_Burn, arg);
    }

    default Update<Exercised<BigDecimal>> exerciseBurn(BigDecimal qty) {
      return exerciseBurn(new Burn(qty));
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, LPToken, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<LPToken> get() {
      return jsonDecoder();
    }
  }
}
