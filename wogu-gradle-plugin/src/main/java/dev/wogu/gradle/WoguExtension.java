package dev.wogu.gradle;

import org.gradle.api.provider.Property;

/**
 * Project-level configuration for WoGu, applied via the {@code wogu {}} block:
 *
 * <pre>{@code
 * wogu {
 *   skip = false
 *   failOnViolation = true
 * }
 * }</pre>
 */
public abstract class WoguExtension {

  /** Skips WoGu validation entirely when set to {@code true}. Defaults to {@code false}. */
  public abstract Property<Boolean> getSkip();

  /**
   * Whether a build-blocking violation should fail the build. Defaults to {@code true}.
   */
  public abstract Property<Boolean> getFailOnViolation();
}
