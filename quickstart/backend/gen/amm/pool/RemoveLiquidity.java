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
import lptoken.lptoken.LPToken;

public class RemoveLiquidity extends DamlRecord<RemoveLiquidity> {
  public static final String _packageId = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public final String provider;

  public final LPToken.ContractId lpTokenCid;

  public final BigDecimal lpTokenAmount;

  public final BigDecimal minAmountA;

  public final BigDecimal minAmountB;

  public final Instant deadline;

  public RemoveLiquidity(String provider, LPToken.ContractId lpTokenCid, BigDecimal lpTokenAmount,
      BigDecimal minAmountA, BigDecimal minAmountB, Instant deadline) {
    this.provider = provider;
    this.lpTokenCid = lpTokenCid;
    this.lpTokenAmount = lpTokenAmount;
    this.minAmountA = minAmountA;
    this.minAmountB = minAmountB;
    this.deadline = deadline;
  }

  public static ValueDecoder<RemoveLiquidity> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(6,0,
          recordValue$);
      String provider = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      LPToken.ContractId lpTokenCid =
          new LPToken.ContractId(fields$.get(1).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected lpTokenCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      BigDecimal lpTokenAmount = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(2).getValue());
      BigDecimal minAmountA = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(3).getValue());
      BigDecimal minAmountB = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(4).getValue());
      Instant deadline = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(5).getValue());
      return new RemoveLiquidity(provider, lpTokenCid, lpTokenAmount, minAmountA, minAmountB,
          deadline);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(6);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("provider", new Party(this.provider)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("lpTokenCid", this.lpTokenCid.toValue()));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("lpTokenAmount", new Numeric(this.lpTokenAmount)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("minAmountA", new Numeric(this.minAmountA)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("minAmountB", new Numeric(this.minAmountB)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("deadline", Timestamp.fromInstant(this.deadline)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<RemoveLiquidity> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("provider", "lpTokenCid", "lpTokenAmount", "minAmountA", "minAmountB", "deadline"), name -> {
          switch (name) {
            case "provider": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "lpTokenCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(lptoken.lptoken.LPToken.ContractId::new));
            case "lpTokenAmount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "minAmountA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "minAmountB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "deadline": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new RemoveLiquidity(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5])));
  }

  public static RemoveLiquidity fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("provider", apply(JsonLfEncoders::party, provider)),
        JsonLfEncoders.Field.of("lpTokenCid", apply(JsonLfEncoders::contractId, lpTokenCid)),
        JsonLfEncoders.Field.of("lpTokenAmount", apply(JsonLfEncoders::numeric, lpTokenAmount)),
        JsonLfEncoders.Field.of("minAmountA", apply(JsonLfEncoders::numeric, minAmountA)),
        JsonLfEncoders.Field.of("minAmountB", apply(JsonLfEncoders::numeric, minAmountB)),
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
    if (!(object instanceof RemoveLiquidity)) {
      return false;
    }
    RemoveLiquidity other = (RemoveLiquidity) object;
    return Objects.equals(this.provider, other.provider) &&
        Objects.equals(this.lpTokenCid, other.lpTokenCid) &&
        Objects.equals(this.lpTokenAmount, other.lpTokenAmount) &&
        Objects.equals(this.minAmountA, other.minAmountA) &&
        Objects.equals(this.minAmountB, other.minAmountB) &&
        Objects.equals(this.deadline, other.deadline);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.provider, this.lpTokenCid, this.lpTokenAmount, this.minAmountA,
        this.minAmountB, this.deadline);
  }

  @Override
  public String toString() {
    return String.format("amm.pool.RemoveLiquidity(%s, %s, %s, %s, %s, %s)", this.provider,
        this.lpTokenCid, this.lpTokenAmount, this.minAmountA, this.minAmountB, this.deadline);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<RemoveLiquidity> get() {
      return jsonDecoder();
    }
  }
}
