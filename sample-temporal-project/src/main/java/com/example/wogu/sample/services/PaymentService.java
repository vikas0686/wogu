package com.example.wogu.sample.services;

import java.util.UUID;

public class PaymentService {

  public String executeUUIDError() {
    // Intentional WoGu demo violation, one call-graph hop away from the workflow method.
    return UUID.randomUUID().toString();
  }
}
