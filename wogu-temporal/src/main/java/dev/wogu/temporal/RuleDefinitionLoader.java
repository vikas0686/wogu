package dev.wogu.temporal;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.yaml.snakeyaml.Yaml;

/**
 * Loads {@link RuleDefinition}s from YAML files on the classpath under {@code rules/}.
 *
 * <p>This is the only class in WoGu that knows the configuration format is YAML —
 * everything downstream ({@link RuleRegistry}, {@link ForbiddenMethodRule}, ...) only
 * ever sees the resulting typed {@link RuleDefinition}. Supporting a different format
 * later (or alongside YAML) means changing this class, not the rest of the engine.
 *
 * <p>Rule definitions are discovered by scanning every classpath root for a {@code rules}
 * directory, which works whether {@code rules/} is an exploded directory (running from
 * {@code target/classes}, e.g. during tests) or packaged inside a jar (a real Maven/Gradle
 * build using the published plugin) — either way, adding a new rule is "add a YAML file",
 * never "register a filename somewhere in Java".
 */
final class RuleDefinitionLoader {

  private static final String RULES_DIRECTORY = "rules";

  private final ClassLoader classLoader;

  RuleDefinitionLoader(ClassLoader classLoader) {
    this.classLoader = classLoader;
  }

  /** Loads every rule definition found on the classpath under {@code rules/}. */
  List<RuleDefinition> loadAll() {
    List<RuleDefinition> definitions = new ArrayList<>();
    for (String resourceName : listYamlResourceNames()) {
      definitions.add(parse(readResource(resourceName), resourceName));
    }
    return definitions;
  }

  /**
   * Parses a single YAML document into a validated {@link RuleDefinition}, throwing a
   * clear, resource-attributed error if a required field is missing.
   *
   * @param yaml the YAML document text
   * @param sourceName identifies the source in error messages, e.g. {@code "rules/wg001.yaml"}
   */
  @SuppressWarnings("unchecked")
  RuleDefinition parse(String yaml, String sourceName) {
    Map<String, Object> data;
    try {
      data = new Yaml().load(yaml);
    } catch (RuntimeException e) {
      throw new IllegalStateException("Failed to parse rule definition '" + sourceName + "': " + e.getMessage(), e);
    }
    if (data == null) {
      throw new IllegalStateException("Rule definition '" + sourceName + "' is empty");
    }
    return new RuleDefinition(
        requiredString(data, "id", sourceName),
        requiredString(data, "type", sourceName),
        requiredString(data, "title", sourceName),
        requiredString(data, "description", sourceName),
        requiredString(data, "category", sourceName),
        requiredString(data, "severity", sourceName),
        requiredString(data, "engine", sourceName),
        requiredString(data, "since", sourceName),
        requiredString(data, "documentation", sourceName),
        requiredString(data, "replacement", sourceName),
        stringList(data, "methods"),
        stringList(data, "constructors"),
        stringList(data, "suppressedContexts"),
        stringList(data, "requiredContexts"),
        stringList(data, "tags"));
  }

  private String readResource(String resourceName) {
    try (InputStream input = classLoader.getResourceAsStream(resourceName)) {
      if (input == null) {
        throw new IllegalStateException("Rule definition resource disappeared while loading: " + resourceName);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read rule definition: " + resourceName, e);
    }
  }

  private static String requiredString(Map<String, Object> data, String key, String sourceName) {
    Object value = data.get(key);
    if (value == null) {
      throw new IllegalStateException("Rule definition '" + sourceName + "' is missing required field '" + key + "'");
    }
    return value.toString();
  }

  @SuppressWarnings("unchecked")
  private static List<String> stringList(Map<String, Object> data, String key) {
    Object value = data.get(key);
    if (value == null) {
      return List.of();
    }
    return ((List<Object>) value).stream().map(Object::toString).toList();
  }

  private List<String> listYamlResourceNames() {
    List<String> names = new ArrayList<>();
    try {
      Enumeration<URL> roots = classLoader.getResources(RULES_DIRECTORY);
      while (roots.hasMoreElements()) {
        names.addAll(listYamlResourceNamesUnder(roots.nextElement()));
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to list rule definitions under '" + RULES_DIRECTORY + "'", e);
    }
    return names;
  }

  private List<String> listYamlResourceNamesUnder(URL root) throws IOException {
    return switch (root.getProtocol()) {
      case "file" -> listFromDirectory(root);
      case "jar" -> listFromJar(root);
      default -> throw new IllegalStateException("Unsupported classpath URL for rule definitions: " + root);
    };
  }

  private static List<String> listFromDirectory(URL root) throws IOException {
    Path directory;
    try {
      directory = Path.of(root.toURI());
    } catch (URISyntaxException e) {
      throw new IOException("Malformed rule definitions directory URL: " + root, e);
    }
    try (var entries = Files.list(directory)) {
      return entries.filter(RuleDefinitionLoader::isYamlFile).map(path -> RULES_DIRECTORY + "/" + path.getFileName()).toList();
    }
  }

  private static List<String> listFromJar(URL root) throws IOException {
    JarURLConnection connection = (JarURLConnection) root.openConnection();
    File jarFile;
    try {
      jarFile = new File(connection.getJarFileURL().toURI());
    } catch (URISyntaxException e) {
      throw new IOException("Malformed jar URL for rule definitions: " + root, e);
    }
    List<String> names = new ArrayList<>();
    String prefix = RULES_DIRECTORY + "/";
    try (JarFile jar = new JarFile(jarFile)) {
      Enumeration<JarEntry> entries = jar.entries();
      while (entries.hasMoreElements()) {
        String name = entries.nextElement().getName();
        if (name.startsWith(prefix) && isYamlFile(Path.of(name))) {
          names.add(name);
        }
      }
    }
    return names;
  }

  private static boolean isYamlFile(Path path) {
    String name = path.getFileName().toString();
    return name.endsWith(".yaml") || name.endsWith(".yml");
  }
}
