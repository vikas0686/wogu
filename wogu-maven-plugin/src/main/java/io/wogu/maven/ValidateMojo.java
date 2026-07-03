package io.wogu.maven;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.core.DefaultValidationContext;
import io.wogu.core.ValidatorExecutionException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Runs every registered WoGu {@code WorkflowValidator} against this project's source and
 * writes an HTML report.
 *
 * <p>Bound by default to the {@code verify} phase: adding this plugin to a POM is
 * sufficient for {@code mvn verify} to run WoGu automatically. The build fails when a
 * validator reports a violation whose severity blocks the build, unless
 * {@code wogu.failOnViolation} is set to {@code false}.
 */
@Mojo(
    name = "validate",
    defaultPhase = LifecyclePhase.VERIFY,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true)
public final class ValidateMojo extends AbstractMojo {

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  /** Directory the HTML report is written to. */
  @Parameter(defaultValue = "${project.build.directory}/wogu", property = "wogu.reportDirectory")
  private File reportDirectory;

  /** Skips WoGu validation entirely when set to {@code true}. */
  @Parameter(defaultValue = "false", property = "wogu.skip")
  private boolean skip;

  /** Whether a build-blocking violation should fail the build. */
  @Parameter(defaultValue = "true", property = "wogu.failOnViolation")
  private boolean failOnViolation;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    if (skip) {
      getLog().info("WoGu validation skipped (wogu.skip=true)");
      return;
    }

    getLog().info("Running WoGu...");
    getLog().info("");
    getLog().info("Scanning workflows...");
    getLog().info("");

    ValidationContext context = buildContext();

    getLog().info("Executing validators...");
    getLog().info("");

    WoguRunner.Result result;
    try {
      result = new WoguRunner(getClass().getClassLoader()).run(context, reportDirectory.toPath());
    } catch (ValidatorExecutionException e) {
      throw new MojoExecutionException("WoGu validator '" + e.validatorId() + "' failed to execute", e);
    } catch (IOException e) {
      throw new MojoExecutionException("Failed to write the WoGu HTML report", e);
    }

    ValidationSummary summary = result.summary();
    logResults(summary);
    getLog().info("WoGu report written to " + result.reportPath());
    getLog().info("");

    if (summary.hasBuildFailures()) {
      getLog().error("Build failed.");
      if (failOnViolation) {
        throw new MojoFailureException(
            "WoGu found " + summary.allViolations().size() + " violation(s). See " + result.reportPath());
      }
    }
  }

  private void logResults(ValidationSummary summary) {
    for (ValidationResult validatorResult : summary.results()) {
      getLog().info(validatorResult.validatorId());
      getLog().info("");
      getLog().info(validatorResult.passed() ? "PASSED" : "FAILED");
      getLog().info("");
      if (!validatorResult.violations().isEmpty()) {
        int count = validatorResult.violations().size();
        getLog().info(count + (count == 1 ? " violation found" : " violations found"));
        getLog().info("");
      }
    }
  }

  private ValidationContext buildContext() throws MojoExecutionException {
    List<Path> sourceRoots = project.getCompileSourceRoots().stream().map(Path::of).toList();

    List<Path> classpathElements;
    try {
      classpathElements = project.getCompileClasspathElements().stream().map(Path::of).toList();
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Failed to resolve the project's compile classpath", e);
    }

    String projectName = project.getName() != null ? project.getName() : project.getArtifactId();

    return DefaultValidationContext.builder()
        .projectName(projectName)
        .projectDirectory(project.getBasedir().toPath())
        .sourceRoots(sourceRoots)
        .classpathElements(classpathElements)
        .build();
  }
}
