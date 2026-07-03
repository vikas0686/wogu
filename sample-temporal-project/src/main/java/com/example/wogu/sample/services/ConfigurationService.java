package com.example.wogu.sample.services;

public class ConfigurationService {

  public String region() {
    // Intentional WoGu demo violation (WG008): environment variables can differ across replay.
    return System.getenv("REGION");
  }

  public String userHome() {
    // Intentional WoGu demo violation (WG009): system properties can differ across replay.
    return System.getProperty("user.home");
  }
}
