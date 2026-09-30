package amm.pool;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Timestamp;
import com.daml.ledger.javaapi.data.Value;
import com.daml.ledger.javaapi.data.codegen.DamlRecord;
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders;
import com.daml.ledger.javaapi.data.codegen.ValueDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader;
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
import token.token.Token;

public class AddLiquidity extends DamlRecord<AddLiquidity> {
  public static final String _packageId = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public final String provider;

  public final Token.ContractId tokenACid;

  public final Token.ContractId tokenBCid;

  public final BigDecimal amountA;

  public final BigDecimal amountB;

  public final BigDecimal minLPTokens;

  public final Instant deadline;

  public AddLiquidity(String provider, Token.ContractId tokenACid, Token.ContractId tokenBCid,
      BigDecimal amountA, BigDecimal amountB, BigDecimal minLPTokens, Instant deadline) {
    this.provider = provider;
    this.tokenACid = tokenACid;
    this.tokenBCid = tokenBCid;
    this.amountA = amountA;
    this.amountB = amountB;
    this.minLPTokens = minLPTokens;
    this.deadline = deadline;
  }

  public static ValueDecoder<AddLiquidity> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(7,0,
          recordValue$);
      String provider = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      Token.ContractId tokenACid =
          new Token.ContractId(fields$.get(1).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected tokenACid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      Token.ContractId tokenBCid =
          new Token.ContractId(fields$.get(2).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected tokenBCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      BigDecimal amountA = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(3).getValue());
      BigDecimal amountB = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(4).getValue());
      BigDecimal minLPTokens = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(5).getValue());
      Instant deadline = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(6).getValue());
      return new AddLiquidity(provider, tokenACid, tokenBCid, amountA, amountB, minLPTokens,
          deadline);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(7);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("provider", new Party(this.provider)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("tokenACid", this.tokenACid.toValue()));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("tokenBCid", this.tokenBCid.toValue()));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("amountA", new Numeric(this.amountA)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("amountB", new Numeric(this.amountB)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("minLPTokens", new Numeric(this.minLPTokens)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("deadline", Timestamp.fromInstant(this.deadline)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<AddLiquidity> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("provider", "tokenACid", "tokenBCid", "amountA", "amountB", "minLPTokens", "deadline"), name -> {
          switch (name) {
            case "provider": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "tokenACid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(token.token.Token.ContractId::new));
            case "tokenBCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(token.token.Token.ContractId::new));
            case "amountA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "amountB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "minLPTokens": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "deadline": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new AddLiquidity(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6])));
  }

  public static AddLiquidity fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("provider", apply(JsonLfEncoders::party, provider)),
        JsonLfEncoders.Field.of("tokenACid", apply(JsonLfEncoders::contractId, tokenACid)),
        JsonLfEncoders.Field.of("tokenBCid", apply(JsonLfEncoders::contractId, tokenBCid)),
        JsonLfEncoders.Field.of("amountA", apply(JsonLfEncoders::numeric, amountA)),
        JsonLfEncoders.Field.of("amountB", apply(JsonLfEncoders::numeric, amountB)),
        JsonLfEncoders.Field.of("minLPTokens", apply(JsonLfEncoders::numeric, minLPTokens)),
        JsonLfEncoders.Field.of("deadline", apply(JsonLfEncoders::timestamp, deadline)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof AddLiquidity)) {
      return false;
    }
    AddLiquidity other = (AddLiquidity) object;
    return Objects.equals(this.provider, other.provider) &&
        Objects.equals(this.tokenACid, other.tokenACid) &&
        Objects.equals(this.tokenBCid, other.tokenBCid) &&
        Objects.equals(this.amountA, other.amountA) &&
        Objects.equals(this.amountB, other.amountB) &&
        Objects.equals(this.minLPTokens, other.minLPTokens) &&
        Objects.equals(this.deadline, other.deadline);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.provider, this.tokenACid, this.tokenBCid, this.amountA, this.amountB,
        this.minLPTokens, this.deadline);
  }

  @Override
  public String toString() {
    return String.format("amm.pool.AddLiquidity(%s, %s, %s, %s, %s, %s, %s)", this.provider,
        this.tokenACid, this.tokenBCid, this.amountA, this.amountB, this.minLPTokens,
        this.deadline);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<AddLiquidity> get() {
      return jsonDecoder();
    }
  }
}
