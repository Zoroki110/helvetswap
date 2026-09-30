package com.digitalasset.quickstart.dto;

public class PayoutRequest {
    public String receiverParty;
    public String amount; // decimal string
    public Long executeBeforeSeconds; // optional, default 7200
    public String memo; // optional
}

