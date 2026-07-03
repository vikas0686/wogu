package io.wogu.temporal;

import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
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
 * {@link CompilationUnit}s, shared by every Temporal validator that needs source access.
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
    List<CompilationUnit> units = new ArrayList<>();
    for (Path root : sourceRoots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      SourceRoot sourceRoot = new SourceRoot(root);
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
}
