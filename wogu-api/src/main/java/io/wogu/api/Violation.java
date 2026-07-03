package io.wogu.api;

import java.nio.file.Path;
import java.util.Objects;

/**
 * A single finding reported by a {@link WorkflowValidator}: one rule broken at one
 * location in one source file.
 *
 * <p>Instances are immutable and safe to share across threads. Use {@link #builder()}
 * to construct one.
 */
public final class Violation {

  private final String validatorId;
  private final Severity severity;
  private final Path file;
  private final String className;
  private final int line;
  private final String message;
  private final String suggestedFix;

  private Violation(Builder builder) {
    this.validatorId = Objects.requireNonNull(builder.validatorId, "validatorId");
    this.severity = Objects.requireNonNull(builder.severity, "severity");
    this.file = Objects.requireNonNull(builder.file, "file");
    this.className = Objects.requireNonNull(builder.className, "className");
    this.message = Objects.requireNonNull(builder.message, "message");
    this.suggestedFix = Objects.requireNonNull(builder.suggestedFix, "suggestedFix");
    if (builder.line < 1) {
      throw new IllegalArgumentException("line must be >= 1, was " + builder.line);
    }
    this.line = builder.line;
  }

  /** Id of the {@link WorkflowValidator} that reported this violation. */
  public String validatorId() {
    return validatorId;
  }

  /** Severity of this violation. */
  public Severity severity() {
    return severity;
  }

  /** Source file the violation was found in. */
  public Path file() {
    return file;
  }

  /** Fully qualified name of the class containing the violation. */
  public String className() {
    return className;
  }

  /** 1-based source line number the violation was found at. */
  public int line() {
    return line;
  }

  /** Human-readable description of what is wrong. */
  public String message() {
    return message;
  }

  /** Human-readable suggestion for how to fix the violation. */
  public String suggestedFix() {
    return suggestedFix;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Violation other)) {
      return false;
    }
    return line == other.line
        && validatorId.equals(other.validatorId)
        && severity == other.severity
        && file.equals(other.file)
        && className.equals(other.className)
        && message.equals(other.message)
        && suggestedFix.equals(other.suggestedFix);
  }

  @Override
  public int hashCode() {
    return Objects.hash(validatorId, severity, file, className, line, message, suggestedFix);
  }

  @Override
  public String toString() {
    return "%s:%d [%s] %s: %s".formatted(file, line, severity, className, message);
  }

  /** Builder for {@link Violation}. */
  public static final class Builder {

    private String validatorId;
    private Severity severity;
    private Path file;
    private String className;
    private int line;
    private String message;
    private String suggestedFix;

    private Builder() {}

    public Builder validatorId(String validatorId) {
      this.validatorId = validatorId;
      return this;
    }

    public Builder severity(Severity severity) {
      this.severity = severity;
      return this;
    }

    public Builder file(Path file) {
      this.file = file;
      return this;
    }

    public Builder className(String className) {
      this.className = className;
      return this;
    }

    public Builder line(int line) {
      this.line = line;
      return this;
    }

    public Builder message(String message) {
      this.message = message;
      return this;
    }

    public Builder suggestedFix(String suggestedFix) {
      this.suggestedFix = suggestedFix;
      return this;
    }

    public Violation build() {
      return new Violation(this);
    }
  }
}
