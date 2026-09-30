package com.digitalasset.quickstart.codegen.da.types;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.DamlRecord;
import com.daml.ledger.javaapi.data.Value;
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders;
import com.daml.ledger.javaapi.data.codegen.ValueDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.lang.IllegalArgumentException;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public class Tuple3<t1, t2, t3> {
  public static final String _packageId = "5aee9b21b8e9a4c4975b5f4c4198e6e6e8469df49e2010820e792f393db870f4";

  public final t1 _1;

  public final t2 _2;

  public final t3 _3;

  public Tuple3(t1 _1, t2 _2, t3 _3) {
    this._1 = _1;
    this._2 = _2;
    this._3 = _3;
  }

  public static <t1, t2, t3> ValueDecoder<Tuple3<t1, t2, t3>> valueDecoder(
      ValueDecoder<t1> fromValuet1, ValueDecoder<t2> fromValuet2, ValueDecoder<t3> fromValuet3)
      throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(3,0, recordValue$);
      t1 _1 = fromValuet1.decode(fields$.get(0).getValue());
      t2 _2 = fromValuet2.decode(fields$.get(1).getValue());
      t3 _3 = fromValuet3.decode(fields$.get(2).getValue());
      return new Tuple3<t1, t2, t3>(_1, _2, _3);
    } ;
  }

  public DamlRecord toValue(Function<t1, Value> toValuet1, Function<t2, Value> toValuet2,
      Function<t3, Value> toValuet3) {
    ArrayList<DamlRecord.Field> fields = new ArrayList<DamlRecord.Field>(3);
    fields.add(new DamlRecord.Field("_1", toValuet1.apply(this._1)));
    fields.add(new DamlRecord.Field("_2", toValuet2.apply(this._2)));
    fields.add(new DamlRecord.Field("_3", toValuet3.apply(this._3)));
    return new DamlRecord(fields);
  }

  public static <t1, t2, t3> JsonLfDecoder<Tuple3<t1, t2, t3>> jsonDecoder(
      JsonLfDecoder<t1> decodet1, JsonLfDecoder<t2> decodet2, JsonLfDecoder<t3> decodet3) {
    return JsonLfDecoders.record(Arrays.asList("_1", "_2", "_3"), name -> {
          switch (name) {
            case "_1": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, decodet1);
            case "_2": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, decodet2);
            case "_3": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, decodet3);
            default: return null;
          }
        }
        , (Object[] args) -> new Tuple3<t1, t2, t3>(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2])));
  }

  public static <t1, t2, t3> Tuple3<t1, t2, t3> fromJson(String json, JsonLfDecoder<t1> decodet1,
      JsonLfDecoder<t2> decodet2, JsonLfDecoder<t3> decodet3) throws JsonLfDecoder.Error {
    return jsonDecoder(decodet1, decodet2, decodet3).decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder(Function<t1, JsonLfEncoder> makeEncoder_t1,
      Function<t2, JsonLfEncoder> makeEncoder_t2, Function<t3, JsonLfEncoder> makeEncoder_t3) {
    return JsonLfEncoders.record(JsonLfEncoders.Field.of("_1", apply(makeEncoder_t1, _1)),
        JsonLfEncoders.Field.of("_2", apply(makeEncoder_t2, _2)),
        JsonLfEncoders.Field.of("_3", apply(makeEncoder_t3, _3)));
  }

  public String toJson(Function<t1, JsonLfEncoder> makeEncoder_t1,
      Function<t2, JsonLfEncoder> makeEncoder_t2, Function<t3, JsonLfEncoder> makeEncoder_t3) {
    var w = new StringWriter();
    try {
      this.jsonEncoder(makeEncoder_t1, makeEncoder_t2, makeEncoder_t3).encode(new JsonLfWriter(w));
    } catch (IOException e) {
      // Not expected with StringWriter
      throw new UncheckedIOException(e);
    }
    return w.toString();
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof Tuple3<?, ?, ?>)) {
      return false;
    }
    Tuple3<?, ?, ?> other = (Tuple3<?, ?, ?>) object;
    return Objects.equals(this._1, other._1) && Objects.equals(this._2, other._2) &&
        Objects.equals(this._3, other._3);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this._1, this._2, this._3);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.da.types.Tuple3(%s, %s, %s)", this._1,
        this._2, this._3);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public <t1, t2, t3> JsonLfDecoder<Tuple3<t1, t2, t3>> get(JsonLfDecoder<t1> decodet1,
        JsonLfDecoder<t2> decodet2, JsonLfDecoder<t3> decodet3) {
      return jsonDecoder(decodet1, decodet2, decodet3);
    }
  }
}
