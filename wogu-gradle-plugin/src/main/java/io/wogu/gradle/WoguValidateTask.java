package io.wogu.gradle;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationResult;
import io.wogu.api.ValidationSummary;
import io.wogu.core.DefaultValidationContext;
import io.wogu.core.ValidationEngine;
import io.wogu.report.HtmlReportGenerator;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Gradle task backing the {@code woguValidate} task: runs every discoverable WoGu
 * {@code WorkflowValidator} against the project's main source set and writes an HTML
 * report, failing the build on a build-blocking violation.
 *
 * <p>{@link WoguPlugin} registers and wires this task; it is not intended to be created
 * directly.
 */
public abstract class WoguValidateTask extends DefaultTask {

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  public abstract ConfigurableFileCollection getSourceRoots();

  @Classpath
  public abstract ConfigurableFileCollection getRuntimeClasspath();

  @Input
  public abstract Property<String> getProjectNameProperty();

  @Internal
  public abstract DirectoryProperty getProjectDirectory();

  @Input
  public abstract Property<Boolean> getSkip();

  @Input
  public abstract Property<Boolean> getFailOnViolation();

  @OutputDirectory
  public abstract DirectoryProperty getReportDirectory();

  @TaskAction
  public void validate() {
    if (Boolean.TRUE.equals(getSkip().getOrElse(false))) {
      getLogger().lifecycle("WoGu validation skipped (wogu.skip=true)");
      return;
    }

    getLogger().lifecycle("Running WoGu...");
    getLogger().lifecycle("");
    getLogger().lifecycle("Scanning workflows...");
    getLogger().lifecycle("");

    ValidationContext context = buildContext();

    getLogger().lifecycle("Executing validators...");
    getLogger().lifecycle("");

    ValidationEngine engine = ValidationEngine.discover(getClass().getClassLoader());
    ValidationSummary summary = engine.run(context);

    Path reportPath;
    try {
      reportPath = new HtmlReportGenerator().generate(summary, getReportDirectory().get().getAsFile().toPath());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the WoGu HTML report", e);
    }

    logResults(summary);
    getLogger().lifecycle("WoGu report written to " + reportPath);
    getLogger().lifecycle("");

    if (summary.hasBuildFailures()) {
      getLogger().error("Build failed.");
      if (Boolean.TRUE.equals(getFailOnViolation().getOrElse(true))) {
        throw new GradleException(
            "WoGu found " + summary.allViolations().size() + " violation(s). See " + reportPath);
      }
    }
  }

  private void logResults(ValidationSummary summary) {
    for (ValidationResult result : summary.results()) {
      getLogger().lifecycle(result.validatorId());
      getLogger().lifecycle("");
      getLogger().lifecycle(result.passed() ? "PASSED" : "FAILED");
      getLogger().lifecycle("");
      if (!result.violations().isEmpty()) {
        int count = result.violations().size();
        getLogger().lifecycle(count + (count == 1 ? " violation found" : " violations found"));
        getLogger().lifecycle("");
      }
    }
  }

  private ValidationContext buildContext() {
    List<Path> sourceRoots = getSourceRoots().getFiles().stream().map(File::toPath).toList();
    List<Path> classpathElements = getRuntimeClasspath().getFiles().stream().map(File::toPath).toList();

    return DefaultValidationContext.builder()
        .projectName(getProjectNameProperty().get())
        .projectDirectory(getProjectDirectory().get().getAsFile().toPath())
        .sourceRoots(sourceRoots)
        .classpathElements(classpathElements)
        .build();
  }
}
