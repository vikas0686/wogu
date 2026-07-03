package com.example.wogu.sample.clean;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderWorkflow {

  @WorkflowMethod
  String placeOrder(String customerId);
}
