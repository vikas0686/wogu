package com.example.wogu.sample.services;

import java.security.SecureRandom;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

public class RandomnessService {

  public double rollDiscount() {
    // Intentional WoGu demo violation (WG004): Math.random() is not replay-safe.
    return Math.random();
  }

  public int pickLuckyNumber() {
    // Intentional WoGu demo violation (WG005): both `new Random()` and `nextInt()` fire,
    // since constructing a Random and drawing from it are each their own violation.
    Random random = new Random();
    return random.nextInt(100);
  }

  public int pickFastNumber() {
    // Intentional WoGu demo violation (WG006): thread-local, not seeded from workflow history.
    return ThreadLocalRandom.current().nextInt(100);
  }

  public byte[] generateSecureToken() {
    // Intentional WoGu demo violation (WG007): both `new SecureRandom()` and `nextBytes()` fire.
    SecureRandom random = new SecureRandom();
    byte[] token = new byte[16];
    random.nextBytes(token);
    return token;
  }
}
