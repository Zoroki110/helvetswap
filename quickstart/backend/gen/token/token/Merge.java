package token.token;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Merge extends DamlRecord<Merge> {
  public static final String _packageId = "cdd2ef4c7b9ee84c219cb631e037555546b7c8dfdcea51f985c5c6ad41686f66";

  public final Token.ContractId otherTokenCid;

  public Merge(Token.ContractId otherTokenCid) {
    this.otherTokenCid = otherTokenCid;
  }

  public static ValueDecoder<Merge> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(1,0,
          recordValue$);
      Token.ContractId otherTokenCid =
          new Token.ContractId(fields$.get(0).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected otherTokenCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      return new Merge(otherTokenCid);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(1);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("otherTokenCid", this.otherTokenCid.toValue()));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<Merge> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("otherTokenCid"), name -> {
          switch (name) {
            case "otherTokenCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(token.token.Token.ContractId::new));
            default: return null;
          }
        }
        , (Object[] args) -> new Merge(JsonLfDecoders.cast(args[0])));
  }

  public static Merge fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("otherTokenCid", apply(JsonLfEncoders::contractId, otherTokenCid)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof Merge)) {
      return false;
    }
    Merge other = (Merge) object;
    return Objects.equals(this.otherTokenCid, other.otherTokenCid);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.otherTokenCid);
  }

  @Override
  public String toString() {
    return String.format("token.token.Merge(%s)", this.otherTokenCid);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<Merge> get() {
      return jsonDecoder();
    }
  }
}
