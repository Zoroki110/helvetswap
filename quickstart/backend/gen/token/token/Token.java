package token.token;

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
import da.internal.template.Archive;
import da.types.Tuple2;
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
import java.util.Optional;
import java.util.Set;

public final class Token extends Template {
  public static final Identifier TEMPLATE_ID = new Identifier("#clearportx-amm-drain-credit", "Token.Token", "Token");

  public static final Identifier TEMPLATE_ID_WITH_PACKAGE_ID = new Identifier("cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66", "Token.Token", "Token");

  public static final String PACKAGE_ID = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public static final String PACKAGE_NAME = "clearportx-amm-drain-credit";

  public static final PackageVersion PACKAGE_VERSION = new PackageVersion(new int[] {1, 0, 0});

  public static final Choice<Token, TransferSplit, Tuple2<Optional<ContractId>, ContractId>> CHOICE_TransferSplit = 
      Choice.create("TransferSplit", value$ -> value$.toValue(), value$ ->
        TransferSplit.valueDecoder().decode(value$), value$ ->
        Tuple2.<java.util.Optional<token.token.Token.ContractId>,
        token.token.Token.ContractId>valueDecoder(PrimitiveValueDecoders.fromOptional(v$0 ->
            new ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue())),
        v$1 ->
          new ContractId(v$1.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
        .decode(value$), new TransferSplit.JsonDecoder$().get(),
        new Tuple2.JsonDecoder$().get(JsonLfDecoders.optional(JsonLfDecoders.contractId(ContractId::new)), JsonLfDecoders.contractId(ContractId::new)),
        TransferSplit::jsonEncoder,
        _x0 -> _x0.jsonEncoder(JsonLfEncoders.optional(JsonLfEncoders::contractId), JsonLfEncoders::contractId));

  public static final Choice<Token, Credit, ContractId> CHOICE_Credit = 
      Choice.create("Credit", value$ -> value$.toValue(), value$ -> Credit.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Credit.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Credit::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<Token, Transfer, ContractId> CHOICE_Transfer = 
      Choice.create("Transfer", value$ -> value$.toValue(), value$ -> Transfer.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Transfer.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Transfer::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<Token, Archive, Unit> CHOICE_Archive = 
      Choice.create("Archive", value$ -> value$.toValue(), value$ -> Archive.valueDecoder()
        .decode(value$), value$ -> PrimitiveValueDecoders.fromUnit.decode(value$),
        new Archive.JsonDecoder$().get(), JsonLfDecoders.unit, Archive::jsonEncoder,
        JsonLfEncoders::unit);

  public static final Choice<Token, Drain, ContractId> CHOICE_Drain = 
      Choice.create("Drain", value$ -> value$.toValue(), value$ -> Drain.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Drain.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Drain::jsonEncoder, JsonLfEncoders::contractId);

  public static final Choice<Token, Merge, ContractId> CHOICE_Merge = 
      Choice.create("Merge", value$ -> value$.toValue(), value$ -> Merge.valueDecoder()
        .decode(value$), value$ ->
        new ContractId(value$.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected value$ to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()),
        new Merge.JsonDecoder$().get(), JsonLfDecoders.contractId(ContractId::new),
        Merge::jsonEncoder, JsonLfEncoders::contractId);

  public static final ContractCompanion.WithoutKey<Contract, ContractId, Token> COMPANION = 
      new ContractCompanion.WithoutKey<>(new ContractTypeCompanion.Package(Token.PACKAGE_ID, Token.PACKAGE_NAME, Token.PACKAGE_VERSION),
        "token.token.Token", TEMPLATE_ID, ContractId::new,
        v -> Token.templateValueDecoder().decode(v), Token::fromJson, Contract::new,
        List.of(CHOICE_Drain, CHOICE_Merge, CHOICE_Credit, CHOICE_TransferSplit, CHOICE_Archive,
        CHOICE_Transfer));

  public final String issuer;

  public final String owner;

  public final String symbol;

  public final BigDecimal amount;

  public Token(String issuer, String owner, String symbol, BigDecimal amount) {
    this.issuer = issuer;
    this.owner = owner;
    this.symbol = symbol;
    this.amount = amount;
  }

  @Override
  public Update<Created<ContractId>> create() {
    return new Update.CreateUpdate<ContractId, Created<ContractId>>(new CreateCommand(Token.TEMPLATE_ID, this.toValue()), x -> x, ContractId::new);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseTransferSplit} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Optional<ContractId>, ContractId>>> createAndExerciseTransferSplit(
      TransferSplit arg) {
    return createAnd().exerciseTransferSplit(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseTransferSplit} instead
   */
  @Deprecated
  public Update<Exercised<Tuple2<Optional<ContractId>, ContractId>>> createAndExerciseTransferSplit(
      String recipient, BigDecimal qty) {
    return createAndExerciseTransferSplit(new TransferSplit(recipient, qty));
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
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseDrain} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseDrain(Drain arg) {
    return createAnd().exerciseDrain(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseDrain} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseDrain(BigDecimal qty) {
    return createAndExerciseDrain(new Drain(qty));
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseMerge} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseMerge(Merge arg) {
    return createAnd().exerciseMerge(arg);
  }

  /**
   * @deprecated since Daml 2.3.0; use {@code createAnd().exerciseMerge} instead
   */
  @Deprecated
  public Update<Exercised<ContractId>> createAndExerciseMerge(ContractId otherTokenCid) {
    return createAndExerciseMerge(new Merge(otherTokenCid));
  }

  public static Update<Created<ContractId>> create(String issuer, String owner, String symbol,
      BigDecimal amount) {
    return new Token(issuer, owner, symbol, amount).create();
  }

  @Override
  public CreateAnd createAnd() {
    return new CreateAnd(this);
  }

  @Override
  protected ContractCompanion.WithoutKey<Contract, ContractId, Token> getCompanion() {
    return COMPANION;
  }

  public static ValueDecoder<Token> valueDecoder() throws IllegalArgumentException {
    return ContractCompanion.valueDecoder(COMPANION);
  }

  public DamlRecord toValue() {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(4);
    fields.add(new DamlRecord.Field("issuer", new Party(this.issuer)));
    fields.add(new DamlRecord.Field("owner", new Party(this.owner)));
    fields.add(new DamlRecord.Field("symbol", new Text(this.symbol)));
    fields.add(new DamlRecord.Field("amount", new Numeric(this.amount)));
    return new DamlRecord(fields);
  }

  private static ValueDecoder<Token> templateValueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(4,0, recordValue$);
      String issuer = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      String owner = PrimitiveValueDecoders.fromParty.decode(fields$.get(1).getValue());
      String symbol = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      BigDecimal amount = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(3).getValue());
      return new Token(issuer, owner, symbol, amount);
    } ;
  }

  public static JsonLfDecoder<Token> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("issuer", "owner", "symbol", "amount"), name -> {
          switch (name) {
            case "issuer": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "owner": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "symbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "amount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            default: return null;
          }
        }
        , (Object[] args) -> new Token(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3])));
  }

  public static Token fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("issuer", apply(JsonLfEncoders::party, issuer)),
        JsonLfEncoders.Field.of("owner", apply(JsonLfEncoders::party, owner)),
        JsonLfEncoders.Field.of("symbol", apply(JsonLfEncoders::text, symbol)),
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
    if (!(object instanceof Token)) {
      return false;
    }
    Token other = (Token) object;
    return Objects.equals(this.issuer, other.issuer) && Objects.equals(this.owner, other.owner) &&
        Objects.equals(this.symbol, other.symbol) && Objects.equals(this.amount, other.amount);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.issuer, this.owner, this.symbol, this.amount);
  }

  @Override
  public String toString() {
    return String.format("token.token.Token(%s, %s, %s, %s)", this.issuer, this.owner, this.symbol,
        this.amount);
  }

  public static final class ContractId extends com.daml.ledger.javaapi.data.codegen.ContractId<Token> implements Exercises<ExerciseCommand> {
    public ContractId(String contractId) {
      super(contractId);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Token, ?> getCompanion(
        ) {
      return COMPANION;
    }

    public static ContractId fromContractId(
        com.daml.ledger.javaapi.data.codegen.ContractId<Token> contractId) {
      return COMPANION.toContractId(contractId);
    }
  }

  public static class Contract extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, Token> {
    public Contract(ContractId id, Token data, Set<String> signatories, Set<String> observers) {
      super(id, data, signatories, observers);
    }

    @Override
    protected ContractCompanion<Contract, ContractId, Token> getCompanion() {
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
    default Update<Exercised<Tuple2<Optional<ContractId>, ContractId>>> exerciseTransferSplit(
        TransferSplit arg) {
      return makeExerciseCmd(CHOICE_TransferSplit, arg);
    }

    default Update<Exercised<Tuple2<Optional<ContractId>, ContractId>>> exerciseTransferSplit(
        String recipient, BigDecimal qty) {
      return exerciseTransferSplit(new TransferSplit(recipient, qty));
    }

    default Update<Exercised<ContractId>> exerciseCredit(Credit arg) {
      return makeExerciseCmd(CHOICE_Credit, arg);
    }

    default Update<Exercised<ContractId>> exerciseCredit(BigDecimal qty) {
      return exerciseCredit(new Credit(qty));
    }

    default Update<Exercised<ContractId>> exerciseTransfer(Transfer arg) {
      return makeExerciseCmd(CHOICE_Transfer, arg);
    }

    default Update<Exercised<ContractId>> exerciseTransfer(String recipient, BigDecimal qty) {
      return exerciseTransfer(new Transfer(recipient, qty));
    }

    default Update<Exercised<Unit>> exerciseArchive(Archive arg) {
      return makeExerciseCmd(CHOICE_Archive, arg);
    }

    default Update<Exercised<Unit>> exerciseArchive() {
      return exerciseArchive(new Archive());
    }

    default Update<Exercised<ContractId>> exerciseDrain(Drain arg) {
      return makeExerciseCmd(CHOICE_Drain, arg);
    }

    default Update<Exercised<ContractId>> exerciseDrain(BigDecimal qty) {
      return exerciseDrain(new Drain(qty));
    }

    default Update<Exercised<ContractId>> exerciseMerge(Merge arg) {
      return makeExerciseCmd(CHOICE_Merge, arg);
    }

    default Update<Exercised<ContractId>> exerciseMerge(ContractId otherTokenCid) {
      return exerciseMerge(new Merge(otherTokenCid));
    }
  }

  public static final class CreateAnd extends com.daml.ledger.javaapi.data.codegen.CreateAnd implements Exercises<CreateAndExerciseCommand> {
    CreateAnd(Template createArguments) {
      super(createArguments);
    }

    @Override
    protected ContractTypeCompanion<? extends com.daml.ledger.javaapi.data.codegen.Contract<ContractId, ?>, ContractId, Token, ?> getCompanion(
        ) {
      return COMPANION;
    }
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<Token> get() {
      return jsonDecoder();
    }
  }
}
