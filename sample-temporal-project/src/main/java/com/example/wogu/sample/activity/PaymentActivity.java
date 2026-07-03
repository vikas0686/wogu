package com.example.wogu.sample.activity;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;

@ActivityInterface
public interface PaymentActivity {
    @ActivityMethod
    public void processPaymentActivity(String accountId);
}
