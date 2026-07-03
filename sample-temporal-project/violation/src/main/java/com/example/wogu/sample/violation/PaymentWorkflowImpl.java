package com.example.wogu.sample.violation;

import java.util.UUID;

public class PaymentWorkflowImpl implements PaymentWorkflow {

  @Override
  public String processPayment(String accountId) {
    // Intentional WoGu demo violation: non-deterministic, breaks workflow replay.
    String transactionId = UUID.randomUUID().toString();
    return "Payment " + transactionId + " processed for account " + accountId;
  }
}
