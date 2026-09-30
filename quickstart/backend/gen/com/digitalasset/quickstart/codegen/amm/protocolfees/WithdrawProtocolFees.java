package com.digitalasset.quickstart.codegen.amm.protocolfees;

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

public class WithdrawProtocolFees extends DamlRecord<WithdrawProtocolFees> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final String recipient;

  public WithdrawProtocolFees(String recipient) {
    this.recipient = recipient;
  }

  public static ValueDecoder<WithdrawProtocolFees> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(1,0,
          recordValue$);
      String recipient = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      return new WithdrawProtocolFees(recipient);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(1);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("recipient", new Party(this.recipient)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<WithdrawProtocolFees> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("recipient"), name -> {
          switch (name) {
            case "recipient": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            default: return null;
          }
        }
        , (Object[] args) -> new WithdrawProtocolFees(JsonLfDecoders.cast(args[0])));
  }

  public static WithdrawProtocolFees fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("recipient", apply(JsonLfEncoders::party, recipient)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof WithdrawProtocolFees)) {
      return false;
    }
    WithdrawProtocolFees other = (WithdrawProtocolFees) object;
    return Objects.equals(this.recipient, other.recipient);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.recipient);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.protocolfees.WithdrawProtocolFees(%s)",
        this.recipient);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<WithdrawProtocolFees> get() {
      return jsonDecoder();
    }
  }
}
