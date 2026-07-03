package io.wogu.gradle;

import io.wogu.api.ValidationContext;
import io.wogu.api.ValidationSummary;
import io.wogu.core.ConsoleReportRenderer;
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
 * Gradle task backing the {@code woguValidate} task: runs every discoverable WoGu rule
 * against the project's main source set and writes an HTML report, failing the build on a
 * build-blocking violation.
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

    ValidationContext context = buildContext();
    ValidationEngine engine = ValidationEngine.discover(getClass().getClassLoader());
    ValidationSummary summary = engine.run(context);

    Path reportPath;
    try {
      reportPath = new HtmlReportGenerator().generate(summary, getReportDirectory().get().getAsFile().toPath());
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the WoGu HTML report", e);
    }

    for (String line : ConsoleReportRenderer.render(summary, reportPath)) {
      getLogger().lifecycle(line);
    }

    if (summary.hasBuildFailures() && Boolean.TRUE.equals(getFailOnViolation().getOrElse(true))) {
      throw new GradleException("WoGu found " + summary.allViolations().size() + " violation(s). See " + reportPath);
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
        .buildTool("Gradle")
        .build();
  }
}
