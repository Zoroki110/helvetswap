package com.digitalasset.quickstart.codegen.amm.protocolfees;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.Numeric;
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

public class AddProtocolFee extends DamlRecord<AddProtocolFee> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final BigDecimal feeAmount;

  public final Instant swapTime;

  public AddProtocolFee(BigDecimal feeAmount, Instant swapTime) {
    this.feeAmount = feeAmount;
    this.swapTime = swapTime;
  }

  public static ValueDecoder<AddProtocolFee> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(2,0,
          recordValue$);
      BigDecimal feeAmount = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(0).getValue());
      Instant swapTime = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(1).getValue());
      return new AddProtocolFee(feeAmount, swapTime);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(2);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("feeAmount", new Numeric(this.feeAmount)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("swapTime", Timestamp.fromInstant(this.swapTime)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<AddProtocolFee> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("feeAmount", "swapTime"), name -> {
          switch (name) {
            case "feeAmount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "swapTime": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new AddProtocolFee(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1])));
  }

  public static AddProtocolFee fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("feeAmount", apply(JsonLfEncoders::numeric, feeAmount)),
        JsonLfEncoders.Field.of("swapTime", apply(JsonLfEncoders::timestamp, swapTime)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof AddProtocolFee)) {
      return false;
    }
    AddProtocolFee other = (AddProtocolFee) object;
    return Objects.equals(this.feeAmount, other.feeAmount) &&
        Objects.equals(this.swapTime, other.swapTime);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.feeAmount, this.swapTime);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.protocolfees.AddProtocolFee(%s, %s)",
        this.feeAmount, this.swapTime);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<AddProtocolFee> get() {
      return jsonDecoder();
    }
  }
}
