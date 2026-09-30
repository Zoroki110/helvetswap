package com.digitalasset.quickstart.codegen.amm.swaprequest;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class PrepareSwap extends DamlRecord<PrepareSwap> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final String protocolFeeReceiver;

  public PrepareSwap(String protocolFeeReceiver) {
    this.protocolFeeReceiver = protocolFeeReceiver;
  }

  public static ValueDecoder<PrepareSwap> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(1,0,
          recordValue$);
      String protocolFeeReceiver = PrimitiveValueDecoders.fromParty
          .decode(fields$.get(0).getValue());
      return new PrepareSwap(protocolFeeReceiver);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(1);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("protocolFeeReceiver", new Party(this.protocolFeeReceiver)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<PrepareSwap> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("protocolFeeReceiver"), name -> {
          switch (name) {
            case "protocolFeeReceiver": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            default: return null;
          }
        }
        , (Object[] args) -> new PrepareSwap(JsonLfDecoders.cast(args[0])));
  }

  public static PrepareSwap fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("protocolFeeReceiver", apply(JsonLfEncoders::party, protocolFeeReceiver)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof PrepareSwap)) {
      return false;
    }
    PrepareSwap other = (PrepareSwap) object;
    return Objects.equals(this.protocolFeeReceiver, other.protocolFeeReceiver);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.protocolFeeReceiver);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.swaprequest.PrepareSwap(%s)",
        this.protocolFeeReceiver);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<PrepareSwap> get() {
      return jsonDecoder();
    }
  }
}
