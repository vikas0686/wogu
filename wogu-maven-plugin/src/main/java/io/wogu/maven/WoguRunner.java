package io.wogu.maven;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationSummary;
import io.wogu.core.ValidationEngine;
import io.wogu.core.ValidatorExecutionException;
import io.wogu.report.HtmlReportGenerator;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Discovers WoGu validators, runs them against a project, and writes the HTML report.
 *
 * <p>This is the build-tool-agnostic core of the {@code wogu:validate} goal, kept free of
 * any Maven API so it can be unit tested directly and, if a Gradle-specific adapter needs
 * the same sequence, reused there too. {@link ValidateMojo} is a thin adapter that adapts
 * a {@code MavenProject} into a {@link ValidationContext} and calls {@link #run}.
 */
final class WoguRunner {

  private final ClassLoader validatorClassLoader;

  WoguRunner(ClassLoader validatorClassLoader) {
    this.validatorClassLoader = Objects.requireNonNull(validatorClassLoader, "validatorClassLoader");
  }

  /**
   * Runs every discoverable {@link io.wogu.api.WorkflowValidator} against {@code context}
   * and writes an HTML report to {@code reportDirectory}.
   *
   * @throws ValidatorExecutionException if a validator throws instead of returning a result
   * @throws IOException if the HTML report could not be written
   */
  Result run(ValidationContext context, Path reportDirectory) throws IOException {
    ValidationEngine engine = ValidationEngine.discover(validatorClassLoader);
    ValidationSummary summary = engine.run(context);
    Path reportPath = new HtmlReportGenerator().generate(summary, reportDirectory);
    return new Result(summary, reportPath);
  }

  /** Outcome of a {@link #run} call: the aggregate summary and where its report was written. */
  record Result(ValidationSummary summary, Path reportPath) {}
}
