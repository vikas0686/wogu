package com.example.wogu.sample.activity;

import java.util.UUID;

public class PaymentActivityImpl implements PaymentActivity{
    @Override
    public void processPaymentActivity(String accountId) {
        String id =  UUID.randomUUID().toString();
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long currentTimeMillis = System.currentTimeMillis();
        System.out.println(currentTimeMillis);
    }
}
