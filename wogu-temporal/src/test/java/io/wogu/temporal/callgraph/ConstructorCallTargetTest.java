package io.wogu.temporal.callgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import org.junit.jupiter.api.Test;

class ConstructorCallTargetTest {

  private static ObjectCreationExpr onlyCreation(CompilationUnit unit) {
    var creations = unit.findAll(ObjectCreationExpr.class);
    assertThat(creations).hasSize(1);
    return creations.get(0);
  }

  @Test
  void neverMatchesAMethodCall() {
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matches(null, null)).isFalse();
  }

  @Test
  void matchesAnImportedSimpleClassNameConstructor() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.Random;
            class Foo {
              void run() {
                new Random();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isTrue();
    assertThat(target.describeConstructor(onlyCreation(unit))).isEqualTo("new Random()");
  }

  @Test
  void matchesAWildcardImportedClassConstructor() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.*;
            class Foo {
              void run() {
                new Random();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isTrue();
  }

  @Test
  void matchesAFullyQualifiedInlineConstructorWithNoImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                new java.util.Random();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isTrue();
  }

  @Test
  void doesNotMatchWithoutAnImportWhenTheClassIsNotInJavaLang() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                new Random();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isFalse();
  }

  @Test
  void matchesAJavaLangClassWithoutRequiringAnImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            class Foo {
              void run() {
                new Thread(() -> {});
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.lang.Thread");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isTrue();
    assertThat(target.describeConstructor(onlyCreation(unit))).isEqualTo("new Thread()");
  }

  @Test
  void doesNotMatchAJavaLangClassNameShadowedByAConflictingImport() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import com.example.custom.Thread;
            class Foo {
              void run() {
                new Thread();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.lang.Thread");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isFalse();
  }

  @Test
  void doesNotMatchADifferentClass() {
    CompilationUnit unit =
        StaticJavaParser.parse(
            """
            import java.util.Random;
            import java.security.SecureRandom;
            class Foo {
              void run() {
                new SecureRandom();
              }
            }
            """);
    ConstructorCallTarget target = new ConstructorCallTarget("java.util.Random");

    assertThat(target.matchesConstructor(onlyCreation(unit), unit)).isFalse();
  }
}
