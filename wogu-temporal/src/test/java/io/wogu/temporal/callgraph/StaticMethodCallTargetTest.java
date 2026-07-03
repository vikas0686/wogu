package io.wogu.temporal.callgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.junit.jupiter.api.Test;

class StaticMethodCallTargetTest {

  private static MethodCallExpr onlyCall(CompilationUnit unit) {
    var calls = unit.findAll(MethodCallExpr.class);
    assertThat(calls).hasSize(1);
    return calls.get(0);
  }

  @Test
  void matchesAnImportedSimpleClassNameCall() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.UUID;
            class Foo {
              void run() {
                UUID.randomUUID();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isTrue();
    assertThat(target.describe(onlyCall(unit))).isEqualTo("UUID.randomUUID()");
  }

  @Test
  void matchesAWildcardImportedClass() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.*;
            class Foo {
              void run() {
                UUID.randomUUID();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isTrue();
  }

  @Test
  void matchesAFullyQualifiedInlineCallWithNoImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                java.util.UUID.randomUUID();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isTrue();
  }

  @Test
  void doesNotMatchWithoutAnImportWhenTheClassIsNotInJavaLang() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                UUID.randomUUID();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isFalse();
  }

  @Test
  void matchesAStaticallyImportedMethod() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import static java.util.UUID.randomUUID;
            class Foo {
              void run() {
                randomUUID();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isTrue();
  }

  @Test
  void matchesAJavaLangClassWithoutRequiringAnImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() throws InterruptedException {
                Thread.sleep(1000);
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.lang.Thread", "sleep");

    assertThat(target.matches(onlyCall(unit), unit)).isTrue();
    assertThat(target.describe(onlyCall(unit))).isEqualTo("Thread.sleep()");
  }

  @Test
  void doesNotMatchAJavaLangClassNameShadowedByAConflictingImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import com.example.custom.Thread;
            class Foo {
              void run() {
                Thread.sleep(1000);
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.lang.Thread", "sleep");

    assertThat(target.matches(onlyCall(unit), unit)).isFalse();
  }

  @Test
  void doesNotMatchADifferentMethodOnTheSameClass() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.UUID;
            class Foo {
              void run() {
                UUID.fromString("x");
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.util.UUID", "randomUUID");

    assertThat(target.matches(onlyCall(unit), unit)).isFalse();
  }

  @Test
  void doesNotMatchTheSameMethodNameOnADifferentClass() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                Workflow.currentTimeMillis();
              }
            }
            """);
    StaticMethodCallTarget target = new StaticMethodCallTarget("java.lang.System", "currentTimeMillis");

    assertThat(target.matches(onlyCall(unit), unit)).isFalse();
  }
}
