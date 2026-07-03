package io.wogu.api;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a {@link WorkflowValidator} needs to know about the project being validated,
 * independent of which build tool (Maven, Gradle, or a future integration) produced it.
 *
 * <p>Implementations are provided by build-tool integrations (e.g. the Maven and Gradle
 * plugins), which adapt their own project model ({@code MavenProject}, Gradle's
 * {@code Project}) into this build-tool-agnostic view. Validators never depend on Maven
 * or Gradle APIs directly.
 */
public interface ValidationContext {

  /** Human-readable name of the project being validated. */
  String projectName();

  /** Root directory of the project being validated. */
  Path projectDirectory();

  /**
   * Java source roots to scan, e.g. {@code src/main/java}. Validators that perform
   * source-level analysis read from these directories.
   */
  List<Path> sourceRoots();

  /**
   * Compiled classpath elements (jars and class directories) available to the project,
   * used for type resolution when a validator needs to reason about inherited or
   * external types.
   */
  List<Path> classpathElements();
}
