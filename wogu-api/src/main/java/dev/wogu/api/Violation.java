package dev.wogu.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * A single finding reported for one {@link Rule}: that rule broken at one location in one
 * source file, optionally with the full call path from a workflow entry point down to the
 * offending code.
 *
 * <p>Instances are immutable and safe to share across threads. Use {@link #builder()}
 * to construct one.
 */
public final class Violation {

  private final Rule rule;
  private final Path file;
  private final String className;
  private final int line;
  private final String message;
  private final String suggestedFix;
  private final List<CallPathFrame> callPath;

  private Violation(Builder builder) {
    this.rule = Objects.requireNonNull(builder.rule, "rule");
    this.file = Objects.requireNonNull(builder.file, "file");
    this.className = Objects.requireNonNull(builder.className, "className");
    this.message = Objects.requireNonNull(builder.message, "message");
    this.suggestedFix = Objects.requireNonNull(builder.suggestedFix, "suggestedFix");
    this.callPath = List.copyOf(Objects.requireNonNull(builder.callPath, "callPath"));
    if (builder.line < 1) {
      throw new IllegalArgumentException("line must be >= 1, was " + builder.line);
    }
    this.line = builder.line;
  }

  /** The rule this is a violation of. */
  public Rule rule() {
    return rule;
  }

  /** Severity of this violation; always the owning {@link #rule()}'s severity. */
  public Severity severity() {
    return rule.severity();
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

  /** Human-readable, teaching-style explanation of what is wrong and why it matters. */
  public String message() {
    return message;
  }

  /** Human-readable suggestion for how to fix the violation. */
  public String suggestedFix() {
    return suggestedFix;
  }

  /**
   * The execution path from a workflow entry point down to this violation, one frame per
   * method boundary crossed, ending with the flagged code itself. Empty if the rule that
   * produced this violation does not perform call-path analysis.
   */
  public List<CallPathFrame> callPath() {
    return callPath;
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
        && rule.equals(other.rule)
        && file.equals(other.file)
        && className.equals(other.className)
        && message.equals(other.message)
        && suggestedFix.equals(other.suggestedFix)
        && callPath.equals(other.callPath);
  }

  @Override
  public int hashCode() {
    return Objects.hash(rule, file, className, line, message, suggestedFix, callPath);
  }

  @Override
  public String toString() {
    return "%s:%d [%s] %s: %s".formatted(file, line, rule.severity(), className, message);
  }

  /** Builder for {@link Violation}. */
  public static final class Builder {

    private Rule rule;
    private Path file;
    private String className;
    private int line;
    private String message;
    private String suggestedFix;
    private List<CallPathFrame> callPath = List.of();

    private Builder() {}

    public Builder rule(Rule rule) {
      this.rule = rule;
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

    public Builder callPath(List<CallPathFrame> callPath) {
      this.callPath = List.copyOf(callPath);
      return this;
    }

    public Violation build() {
      return new Violation(this);
    }
  }
}
