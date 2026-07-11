package com.example.wogu.sample.services;

public class CachedBalance {

  // Intentionally no equals()/hashCode() override — this is what WG012 flags on the
  // Workflow.mutableSideEffect() call in PaymentService.refreshCachedBalance(): every call
  // is reported as "changed" regardless of whether the amount actually moved.
  double amount;

  CachedBalance(double amount) {
    this.amount = amount;
  }
}
