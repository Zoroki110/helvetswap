package com.digitalasset.quickstart.codegen.amm.pool;

import static com.daml.ledger.javaapi.data.codegen.json.JsonLfEncoders.apply;

import com.daml.ledger.javaapi.data.Int64;
import com.daml.ledger.javaapi.data.Numeric;
import com.daml.ledger.javaapi.data.Party;
import com.daml.ledger.javaapi.data.Text;
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
import com.digitalasset.quickstart.codegen.token.token.Token;
import java.lang.IllegalArgumentException;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public class AtomicSwap extends DamlRecord<AtomicSwap> {
  public static final String _packageId = "1de9ed0e61ea23f131fd098a50761ce6f6e377f1cf4012b5de6887adfed21637";

  public final String trader;

  public final Token.ContractId traderInputTokenCid;

  public final String inputSymbol;

  public final BigDecimal inputAmount;

  public final String outputSymbol;

  public final BigDecimal minOutput;

  public final Long maxPriceImpactBps;

  public final Instant deadline;

  public AtomicSwap(String trader, Token.ContractId traderInputTokenCid, String inputSymbol,
      BigDecimal inputAmount, String outputSymbol, BigDecimal minOutput, Long maxPriceImpactBps,
      Instant deadline) {
    this.trader = trader;
    this.traderInputTokenCid = traderInputTokenCid;
    this.inputSymbol = inputSymbol;
    this.inputAmount = inputAmount;
    this.outputSymbol = outputSymbol;
    this.minOutput = minOutput;
    this.maxPriceImpactBps = maxPriceImpactBps;
    this.deadline = deadline;
  }

  public static ValueDecoder<AtomicSwap> valueDecoder() throws IllegalArgumentException {
    return value$ -> {
      Value recordValue$ = value$;
      List<com.daml.ledger.javaapi.data.DamlRecord.Field> fields$ = PrimitiveValueDecoders.recordCheck(8,0,
          recordValue$);
      String trader = PrimitiveValueDecoders.fromParty.decode(fields$.get(0).getValue());
      Token.ContractId traderInputTokenCid =
          new Token.ContractId(fields$.get(1).getValue().asContractId().orElseThrow(() -> new IllegalArgumentException("Expected traderInputTokenCid to be of type com.daml.ledger.javaapi.data.ContractId")).getValue());
      String inputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(2).getValue());
      BigDecimal inputAmount = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(3).getValue());
      String outputSymbol = PrimitiveValueDecoders.fromText.decode(fields$.get(4).getValue());
      BigDecimal minOutput = PrimitiveValueDecoders.fromNumeric.decode(fields$.get(5).getValue());
      Long maxPriceImpactBps = PrimitiveValueDecoders.fromInt64.decode(fields$.get(6).getValue());
      Instant deadline = PrimitiveValueDecoders.fromTimestamp.decode(fields$.get(7).getValue());
      return new AtomicSwap(trader, traderInputTokenCid, inputSymbol, inputAmount, outputSymbol,
          minOutput, maxPriceImpactBps, deadline);
    } ;
  }

  public com.daml.ledger.javaapi.data.DamlRecord toValue() {
    ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field> fields = new ArrayList<com.daml.ledger.javaapi.data.DamlRecord.Field>(8);
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("trader", new Party(this.trader)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("traderInputTokenCid", this.traderInputTokenCid.toValue()));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("inputSymbol", new Text(this.inputSymbol)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("inputAmount", new Numeric(this.inputAmount)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("outputSymbol", new Text(this.outputSymbol)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("minOutput", new Numeric(this.minOutput)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("maxPriceImpactBps", new Int64(this.maxPriceImpactBps)));
    fields.add(new com.daml.ledger.javaapi.data.DamlRecord.Field("deadline", Timestamp.fromInstant(this.deadline)));
    return new com.daml.ledger.javaapi.data.DamlRecord(fields);
  }

  public static JsonLfDecoder<AtomicSwap> jsonDecoder() {
    return JsonLfDecoders.record(Arrays.asList("trader", "traderInputTokenCid", "inputSymbol", "inputAmount", "outputSymbol", "minOutput", "maxPriceImpactBps", "deadline"), name -> {
          switch (name) {
            case "trader": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(0, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.party);
            case "traderInputTokenCid": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(1, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.contractId(com.digitalasset.quickstart.codegen.token.token.Token.ContractId::new));
            case "inputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(2, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "inputAmount": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(3, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "outputSymbol": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(4, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.text);
            case "minOutput": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(5, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.numeric(10));
            case "maxPriceImpactBps": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(6, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.int64);
            case "deadline": return com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.JavaArg.at(7, com.daml.ledger.javaapi.data.codegen.json.JsonLfDecoders.timestamp);
            default: return null;
          }
        }
        , (Object[] args) -> new AtomicSwap(JsonLfDecoders.cast(args[0]), JsonLfDecoders.cast(args[1]), JsonLfDecoders.cast(args[2]), JsonLfDecoders.cast(args[3]), JsonLfDecoders.cast(args[4]), JsonLfDecoders.cast(args[5]), JsonLfDecoders.cast(args[6]), JsonLfDecoders.cast(args[7])));
  }

  public static AtomicSwap fromJson(String json) throws JsonLfDecoder.Error {
    return jsonDecoder().decode(new JsonLfReader(json));
  }

  public JsonLfEncoder jsonEncoder() {
    return JsonLfEncoders.record(
        JsonLfEncoders.Field.of("trader", apply(JsonLfEncoders::party, trader)),
        JsonLfEncoders.Field.of("traderInputTokenCid", apply(JsonLfEncoders::contractId, traderInputTokenCid)),
        JsonLfEncoders.Field.of("inputSymbol", apply(JsonLfEncoders::text, inputSymbol)),
        JsonLfEncoders.Field.of("inputAmount", apply(JsonLfEncoders::numeric, inputAmount)),
        JsonLfEncoders.Field.of("outputSymbol", apply(JsonLfEncoders::text, outputSymbol)),
        JsonLfEncoders.Field.of("minOutput", apply(JsonLfEncoders::numeric, minOutput)),
        JsonLfEncoders.Field.of("maxPriceImpactBps", apply(JsonLfEncoders::int64, maxPriceImpactBps)),
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
    if (!(object instanceof AtomicSwap)) {
      return false;
    }
    AtomicSwap other = (AtomicSwap) object;
    return Objects.equals(this.trader, other.trader) &&
        Objects.equals(this.traderInputTokenCid, other.traderInputTokenCid) &&
        Objects.equals(this.inputSymbol, other.inputSymbol) &&
        Objects.equals(this.inputAmount, other.inputAmount) &&
        Objects.equals(this.outputSymbol, other.outputSymbol) &&
        Objects.equals(this.minOutput, other.minOutput) &&
        Objects.equals(this.maxPriceImpactBps, other.maxPriceImpactBps) &&
        Objects.equals(this.deadline, other.deadline);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.trader, this.traderInputTokenCid, this.inputSymbol, this.inputAmount,
        this.outputSymbol, this.minOutput, this.maxPriceImpactBps, this.deadline);
  }

  @Override
  public String toString() {
    return String.format("com.digitalasset.quickstart.codegen.amm.pool.AtomicSwap(%s, %s, %s, %s, %s, %s, %s, %s)",
        this.trader, this.traderInputTokenCid, this.inputSymbol, this.inputAmount,
        this.outputSymbol, this.minOutput, this.maxPriceImpactBps, this.deadline);
  }

  /**
   * Proxies the jsonDecoder(...) static method, to provide an alternative calling synatx, which avoids some cases in generated code where javac gets confused
   */
  public static class JsonDecoder$ {
    public JsonLfDecoder<AtomicSwap> get() {
      return jsonDecoder();
    }
  }
}
