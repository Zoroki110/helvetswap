package com.digitalasset.quickstart.service;

import org.springframework.stereotype.Service;

@Service
public class TxPacer {
    public void awaitSlot(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}


