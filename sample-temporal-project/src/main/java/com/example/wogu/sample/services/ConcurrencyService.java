package com.example.wogu.sample.services;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConcurrencyService {

  public ExecutorService createNotificationPool() {
    // Intentional WoGu demo violation (WG010): workflows must not create or manage their own threads.
    return Executors.newFixedThreadPool(2);
  }
}
