package com.digitalasset.quickstart.codegen.token.token;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.Party;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class Transfer extends DamlRecord<Transfer> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final String recipient;

  public final BigDecimal qty;

  public Transfer(String recipient, BigDecimal qty) {
    this.recipient = recipient;
    this.qty = qty;
  }

  public static ValueDecoder<Transfer> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(2,0,
          recordValue$);
      String recipient = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      BigDecimal qty = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(1).getValue());
      return new Transfer(recipient, qty);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(2);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("recipient", new Party(this.recipient)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("qty", new Numeric(this.qty)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<Transfer> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("recipient", "qty"), name -> {
          switch (name) {
            case "recipient": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "qty": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            default: return null;
          }
        }
        , (Object[] args) -> new Transfer(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1])));
  }

  public static Transfer fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("recipient", apply(JsonLfEncoders::party, recipient)),
        JsonLfEncoders.Field.of("qty", apply(JsonLfEncoders::numeric, qty)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof Transfer)) {
      return false;
    }
    Transfer other = (Transfer) object;
    return Objects.equals(this.recipient, other.recipient) && Objects.equals(this.qty, other.qty);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.recipient, this.qty);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.token.token.Transfer(%s, %s)",
        this.recipient, this.qty);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<Transfer> get() {
      return jsonDecoder();
    }
  }
}
