package com.example.wogu.sample;

import com.example.wogu.sample.activity.PaymentActivity;
import com.example.wogu.sample.activity.PaymentActivityImpl;
import com.example.wogu.sample.services.ConcurrencyService;
import com.example.wogu.sample.services.ConfigurationService;
import com.example.wogu.sample.services.PaymentService;
import com.example.wogu.sample.services.RandomnessService;

public class PaymentWorkflowImpl implements PaymentWorkflow {

  private final PaymentService paymentService = new PaymentService();
  private final RandomnessService randomnessService = new RandomnessService();
  private final ConfigurationService configurationService = new ConfigurationService();
  private final ConcurrencyService concurrencyService = new ConcurrencyService();
  private final PaymentActivity activity = new PaymentActivityImpl();

  @Override
  public String processPayment(String accountId) {
    String paymentId = paymentService.executeUUIDError();
    paymentService.waitForSettlement();
    long processedAt = paymentService.recordTimestamp();
    String auditId = paymentService.recordAuditId();
    paymentService.fetchAccountTier();


    randomnessService.rollDiscount();
    randomnessService.pickLuckyNumber();
    randomnessService.pickFastNumber();
    randomnessService.generateSecureToken();
    randomnessService.recordAuditId();

    configurationService.region();
    configurationService.userHome();

    concurrencyService.createNotificationPool();

    activity.processPaymentActivity(accountId);
    return "Payment " + paymentId + " (audit " + auditId + ") processed for account " + accountId + " at " + processedAt;
  }
}
