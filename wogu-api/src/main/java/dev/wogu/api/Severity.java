package dev.wogu.api;

/**
 * The severity of a single {@link Violation}.
 *
 * <p>Severity determines whether a violation causes a build to fail. A validator may
 * report violations of any severity; {@link #blocksBuild()} is what build tool
 * integrations consult, via {@link ValidationSummary#hasBuildFailures()}, to decide
 * whether to fail {@code mvn verify} / {@code gradle build}.
 */
public enum Severity {

  /**
   * A deterministic-safety or correctness violation. Blocks the build.
   */
  ERROR,

  /**
   * A likely problem that does not block the build but should be surfaced prominently.
   */
  WARNING,

  /**
   * Informational finding, surfaced only in reports.
   */
  INFO;

  /**
   * Whether a violation of this severity should fail the overall build.
   *
   * @return {@code true} only for {@link #ERROR}
   */
  public boolean blocksBuild() {
    return this == ERROR;
  }
}
