package io.wogu.temporal;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import com.github.javaparser.utils.SourceRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses every {@code .java} file under a set of source roots into JavaParser
 * {@link CompilationUnit}s, shared by every Temporal rule that needs source access.
 *
 * <p>Parsing is configured with a {@link JavaSymbolSolver} backed by the same source
 * roots (plus the JDK, via {@link ReflectionTypeSolver}), so that a
 * {@code MethodCallExpr}'s {@code .resolve()} can follow calls like
 * {@code orderService.createOrder()} to their declaration elsewhere in the project — this
 * is what {@link CallGraphAnalyzer} relies on to traverse across classes. It does not
 * include the project's compiled classpath (jars), so calls into third-party libraries
 * simply fail to resolve, which {@link CallGraphAnalyzer} treats as a traversal boundary
 * rather than an error.
 *
 * <p>A file that fails to parse (e.g. a syntax error, or code using a language feature
 * JavaParser's default configuration does not understand) is logged and skipped rather
 * than failing the whole scan: one broken file should not prevent WoGu from validating
 * the rest of the project.
 */
final class SourceRootParser {

  private static final Logger LOG = LoggerFactory.getLogger(SourceRootParser.class);

  private SourceRootParser() {}

  static List<CompilationUnit> parse(List<Path> sourceRoots) {
    ParserConfiguration configuration = new ParserConfiguration().setSymbolResolver(symbolResolverFor(sourceRoots));

    List<CompilationUnit> units = new ArrayList<>();
    for (Path root : sourceRoots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      SourceRoot sourceRoot = new SourceRoot(root, configuration);
      List<ParseResult<CompilationUnit>> results;
      try {
        results = sourceRoot.tryToParse();
      } catch (IOException e) {
        LOG.warn("Failed to read source root {}: {}", root, e.getMessage());
        continue;
      }
      for (ParseResult<CompilationUnit> result : results) {
        if (result.isSuccessful() && result.getResult().isPresent()) {
          units.add(result.getResult().get());
        } else {
          LOG.warn("Failed to parse a source file under {}: {}", root, result.getProblems());
        }
      }
    }
    return units;
  }

  private static JavaSymbolSolver symbolResolverFor(List<Path> sourceRoots) {
    // The CombinedTypeSolver, JavaSymbolSolver, and ParserConfiguration below are
    // mutually referential: a JavaParserTypeSolver must be constructed with a
    // ParserConfiguration that already has the *same* symbol solver attached, or files it
    // parses internally while resolving a cross-file type (e.g. looking up OrderService to
    // resolve orderService.createOrder()) end up with no symbol resolver of their own,
    // and resolving any call inside them later fails with "Symbol resolution not
    // configured". This is safe because nothing here is actually consulted until a real
    // .resolve() call happens, by which point every type solver has been added.
    CombinedTypeSolver typeSolver = new CombinedTypeSolver();
    JavaSymbolSolver symbolSolver = new JavaSymbolSolver(typeSolver);
    ParserConfiguration typeSolvingConfiguration = new ParserConfiguration().setSymbolResolver(symbolSolver);

    typeSolver.add(new ReflectionTypeSolver());
    for (Path root : sourceRoots) {
      if (Files.isDirectory(root)) {
        typeSolver.add(new JavaParserTypeSolver(root, typeSolvingConfiguration));
      }
    }
    return symbolSolver;
  }
}
