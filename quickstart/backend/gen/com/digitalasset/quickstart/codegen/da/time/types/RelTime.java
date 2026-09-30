package com.digitalasset.quickstart.codegen.da.time.types;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.Int64;
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
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class RelTime extends DamlRecord<RelTime> {
  public static final String _packageId = "b70db8369e1c461d5c70f1c86f526a29e9776c655e6ffc2560f95b05ccb8b946";

  public final Long microseconds;

  public RelTime(Long microseconds) {
    this.microseconds = microseconds;
  }

  public static ValueDecoder<RelTime> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(1,0,
          recordValue$);
      Long microseconds = PrimitiveValueDecoders.fromInt64.decode(fields$.get(0).getValue());
      return new RelTime(microseconds);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(1);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("microseconds", new Int64(this.microseconds)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<RelTime> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("microseconds"), name -> {
          switch (name) {
            case "microseconds": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            default: return null;
          }
        }
        , (Object[] args) -> new RelTime(JsonLfDecoders.cast(args[0])));
  }

  public static RelTime fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("microseconds", apply(JsonLfEncoders::int64, microseconds)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof RelTime)) {
      return false;
    }
    RelTime other = (RelTime) object;
    return Objects.equals(this.microseconds, other.microseconds);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.microseconds);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.da.time.types.RelTime(%s)",
        this.microseconds);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<RelTime> get() {
      return jsonDecoder();
    }
  }
}
