package com.example.wogu.sample.services;

import java.util.UUID;

public class PaymentService {

  public String executeUUIDError() {
    // Intentional WoGu demo violation (WG001), one call-graph hop away from the workflow method.
    return UUID.randomUUID().toString();
  }

  public void waitForSettlement() {
    // Intentional WoGu demo violation (WG002): blocks the worker thread.
    try {
      Thread.sleep(5000);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public long recordTimestamp() {
    // Intentional WoGu demo violation (WG003): reads the non-deterministic wall clock.
    return System.currentTimeMillis();
  }
}
