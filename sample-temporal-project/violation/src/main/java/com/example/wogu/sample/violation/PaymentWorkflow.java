package com.example.wogu.sample.violation;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface PaymentWorkflow {

  @WorkflowMethod
  String processPayment(String accountId);
}
