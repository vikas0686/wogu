package io.wogu.core;

import io.wogu.api.ValidationContext;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Build-tool-agnostic {@link ValidationContext} implementation.
 *
 * <p>Build tool integrations (the Maven and Gradle plugins) construct one of these from
 * their own project model via {@link #builder()}, so that validators never depend on
 * {@code MavenProject}, Gradle's {@code Project}, or any other build-tool-specific type.
 */
public final class DefaultValidationContext implements ValidationContext {

  private final String projectName;
  private final Path projectDirectory;
  private final List<Path> sourceRoots;
  private final List<Path> classpathElements;
  private final String buildTool;

  private DefaultValidationContext(Builder builder) {
    this.projectName = Objects.requireNonNull(builder.projectName, "projectName");
    this.projectDirectory = Objects.requireNonNull(builder.projectDirectory, "projectDirectory");
    this.sourceRoots = List.copyOf(builder.sourceRoots);
    this.classpathElements = List.copyOf(builder.classpathElements);
    this.buildTool = Objects.requireNonNull(builder.buildTool, "buildTool");
  }

  @Override
  public String projectName() {
    return projectName;
  }

  @Override
  public Path projectDirectory() {
    return projectDirectory;
  }

  @Override
  public List<Path> sourceRoots() {
    return sourceRoots;
  }

  @Override
  public List<Path> classpathElements() {
    return classpathElements;
  }

  @Override
  public String buildTool() {
    return buildTool;
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Builder for {@link DefaultValidationContext}. */
  public static final class Builder {

    private String projectName;
    private Path projectDirectory;
    private List<Path> sourceRoots = List.of();
    private List<Path> classpathElements = List.of();
    private String buildTool;

    private Builder() {}

    public Builder projectName(String projectName) {
      this.projectName = projectName;
      return this;
    }

    public Builder projectDirectory(Path projectDirectory) {
      this.projectDirectory = projectDirectory;
      return this;
    }

    public Builder sourceRoots(List<Path> sourceRoots) {
      this.sourceRoots = List.copyOf(sourceRoots);
      return this;
    }

    public Builder classpathElements(List<Path> classpathElements) {
      this.classpathElements = List.copyOf(classpathElements);
      return this;
    }

    public Builder buildTool(String buildTool) {
      this.buildTool = buildTool;
      return this;
    }

    public DefaultValidationContext build() {
      return new DefaultValidationContext(this);
    }
  }
}
