package com.digitalasset.quickstart.dto;

import java.math.BigDecimal;
import java.util.List;

public class HoldingUtxoDto {
    public String contractId;
    public String instrumentAdmin;
    public String instrumentId;
    public BigDecimal amount;
    public Integer decimals;
    public String owner;
    public List<String> observers;
    public HoldingLockDto lock;        // null = unlocked

    public HoldingUtxoDto() {}

    public HoldingUtxoDto(String contractId, String instrumentAdmin, String instrumentId,
                          BigDecimal amount, Integer decimals, String owner, List<String> observers) {
        this.contractId = contractId;
        this.instrumentAdmin = instrumentAdmin;
        this.instrumentId = instrumentId;
        this.amount = amount;
        this.decimals = decimals;
        this.owner = owner;
        this.observers = observers;
    }

    public HoldingUtxoDto(String contractId, String instrumentAdmin, String instrumentId,
                          BigDecimal amount, Integer decimals, String owner, List<String> observers,
                          HoldingLockDto lock) {
        this.contractId = contractId;
        this.instrumentAdmin = instrumentAdmin;
        this.instrumentId = instrumentId;
        this.amount = amount;
        this.decimals = decimals;
        this.owner = owner;
        this.observers = observers;
        this.lock = lock;
    }
}
