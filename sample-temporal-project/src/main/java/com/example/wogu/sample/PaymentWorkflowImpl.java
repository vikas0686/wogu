package com.example.wogu.sample;

import com.example.wogu.sample.services.PaymentService;

public class PaymentWorkflowImpl implements PaymentWorkflow {

  private final PaymentService paymentService = new PaymentService();

  @Override
  public String processPayment(String accountId) {
    String paymentId = paymentService.executeUUIDError();
    return "Payment " + paymentId + " processed for account " + accountId;
  }
}
