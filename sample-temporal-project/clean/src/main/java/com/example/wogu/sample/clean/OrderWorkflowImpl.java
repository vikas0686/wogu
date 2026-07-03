package com.example.wogu.sample.clean;

import io.temporal.workflow.Workflow;

public class OrderWorkflowImpl implements OrderWorkflow {

  @Override
  public String placeOrder(String customerId) {
    String orderId = Workflow.randomUUID().toString();
    return "Order " + orderId + " placed for customer " + customerId;
  }
}
