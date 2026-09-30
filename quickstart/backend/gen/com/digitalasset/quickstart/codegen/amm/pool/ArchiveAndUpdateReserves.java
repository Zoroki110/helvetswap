package com.digitalasset.quickstart.codegen.amm.pool;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.DamlOptional;
import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.Value;
import com.daml.ledger.javaapi.data.codegen.DamlRecord;
import com.daml.ledger.javaapi.data.codegen.PrimitiveValueDecoders;
import com.daml.ledger.javaapi.data.codegen.ValueDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoder;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders;
import com.daml.ledger.javaapi.data.codegen.json.JsonLfReader;
import com.digitalasset.quickstart.codegen.token.token.Token;
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

public class ArchiveAndUpdateReserves extends DamlRecord<ArchiveAndUpdateReserves> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final BigDecimal updatedReserveA;

  public final BigDecimal updatedReserveB;

  public final Optional<Token.ContractId> updatedTokenACid;

  public final Optional<Token.ContractId> updatedTokenBCid;

  public ArchiveAndUpdateReserves(BigDecimal updatedReserveA, BigDecimal updatedReserveB,
      Optional<Token.ContractId> updatedTokenACid, Optional<Token.ContractId> updatedTokenBCid) {
    this.updatedReserveA = updatedReserveA;
    this.updatedReserveB = updatedReserveB;
    this.updatedTokenACid = updatedTokenACid;
    this.updatedTokenBCid = updatedTokenBCid;
  }

  public static ValueDecoder<ArchiveAndUpdateReserves> valueDecoder() throws
      IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(4,2,
          recordValue$);
      BigDecimal updatedReserveA = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(0).getValue());
      BigDecimal updatedReserveB = PrimitiveValueDecoders.fromNumeric
          .decode(fields$.get(1).getValue());
      Optional<Token.ContractId> updatedTokenACid = PrimitiveValueDecoders.fromOptional(v$0 ->
              new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected updatedTokenACid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
          .decode(fields$.get(2).getValue());
      Optional<Token.ContractId> updatedTokenBCid = PrimitiveValueDecoders.fromOptional(v$0 ->
              new Token.ContractId(v$0.asContractId().orElseThrow(() -> new IllegalArgumentException("Expected updatedTokenBCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue()))
          .decode(fields$.get(3).getValue());
      return new ArchiveAndUpdateReserves(updatedReserveA, updatedReserveB, updatedTokenACid,
          updatedTokenBCid);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(4);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("updatedReserveA", new Numeric(this.updatedReserveA)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("updatedReserveB", new Numeric(this.updatedReserveB)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("updatedTokenACid", DamlOptional.of(this.updatedTokenACid.map(v$0 -> v$0.toValue()))));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("updatedTokenBCid", DamlOptional.of(this.updatedTokenBCid.map(v$0 -> v$0.toValue()))));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<ArchiveAndUpdateReserves> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("updatedReserveA", "updatedReserveB", "updatedTokenACid", "updatedTokenBCid"), name -> {
          switch (name) {
            case "updatedReserveA": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "updatedReserveB": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "updatedTokenACid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.optional(com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new)), java.util.Optional.empty());
            case "updatedTokenBCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.optional(com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new)), java.util.Optional.empty());
            default: return null;
          }
        }
        , (Object[] args) -> new ArchiveAndUpdateReserves(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3])));
  }

  public static ArchiveAndUpdateReserves fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("updatedReserveA", apply(JsonLfEncoders::numeric, updatedReserveA)),
        JsonLfEncoders.Field.of("updatedReserveB", apply(JsonLfEncoders::numeric, updatedReserveB)),
        JsonLfEncoders.Field.of("updatedTokenACid", apply(JsonLfEncoders.optional(JsonLfEncoders::contractId), updatedTokenACid)),
        JsonLfEncoders.Field.of("updatedTokenBCid", apply(JsonLfEncoders.optional(JsonLfEncoders::contractId), updatedTokenBCid)));
  }

  @Override
  public boolean equals(Object object) {
    if (this == object) {
      return true;
    }
    if (object == null) {
      return false;
    }
    if (!(object instanceof ArchiveAndUpdateReserves)) {
      return false;
    }
    ArchiveAndUpdateReserves other = (ArchiveAndUpdateReserves) object;
    return Objects.equals(this.updatedReserveA, other.updatedReserveA) &&
        Objects.equals(this.updatedReserveB, other.updatedReserveB) &&
        Objects.equals(this.updatedTokenACid, other.updatedTokenACid) &&
        Objects.equals(this.updatedTokenBCid, other.updatedTokenBCid);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.updatedReserveA, this.updatedReserveB, this.updatedTokenACid,
        this.updatedTokenBCid);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.pool.ArchiveAndUpdateReserves(%s, %s, %s, %s)",
        this.updatedReserveA, this.updatedReserveB, this.updatedTokenACid, this.updatedTokenBCid);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<ArchiveAndUpdateReserves> get() {
      return jsonDecoder();
    }
  }
}
