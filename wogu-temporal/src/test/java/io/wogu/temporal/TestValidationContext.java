package io.wogu.temporal;

import io.wogu.api.ValidationContext;
import java.nio.file.Path;
import java.util.List;

/**
 * Minimal {@link ValidationContext} for tests, kept local to this module so
 * {@code wogu-temporal}'s tests don't need a dependency on {@code wogu-core}.
 */
record TestValidationContext(String projectName, Path projectDirectory, List<Path> sourceRoots)
    implements ValidationContext {

  @Override
  public List<Path> classpathElements() {
    return List.of();
  }
}
