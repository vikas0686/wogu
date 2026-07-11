package com.example.wogu.sample.services;

import io.temporal.workflow.Workflow;
import java.net.Socket;
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

  public String recordAuditId() {
    // Not a WoGu violation: UUID.randomUUID() here is reachable only from inside
    // Workflow.sideEffect(...)'s callback, so WG001 is suppressed in that execution
    // context. Temporal runs the callback exactly once and replays its recorded result
    // thereafter, so the value is stable across replay despite being non-deterministic.
    return Workflow.sideEffect(String.class, () -> UUID.randomUUID().toString());
  }

  public String fetchAccountTier() {
    // Intentional WoGu demo violation (WG011): unlike recordAuditId() above,
    // Workflow.sideEffect() does not make I/O safe. The callback still runs synchronously
    // on the workflow thread, with no Activity-style timeout, retry, or heartbeat behind
    // it, so a real network call here is an availability hazard even though it's "only"
    // reachable from inside a side effect.
    return Workflow.sideEffect(String.class, () -> {
      try (Socket socket = new Socket("pricing.example.com", 443)) {
        return "gold";
      } catch (Exception e) {
        return "default";
      }
    });
  }
}
