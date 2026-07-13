package dev.wogu.temporal;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Maps each {@link RuleDefinition}'s {@code type} to the {@link TemporalRule}
 * implementation that knows how to execute it, and loads every rule definition found on
 * the classpath.
 *
 * <p>The startup sequence this drives is: load rule definitions
 * ({@link RuleDefinitionLoader}) → create rule objects (this class, via
 * {@link #FACTORIES_BY_TYPE}) → register them (the list {@link #loadDeclarativeRules}
 * returns) → execute them ({@link TemporalWorkflowValidator}, unchanged).
 *
 * <p>Adding a new declarative rule <em>type</em> (not just a new rule) means adding one
 * entry to {@link #FACTORIES_BY_TYPE}. Adding a new rule of an <em>existing</em> type
 * (e.g. another {@code forbidden-method} rule) means adding a YAML file under
 * {@code src/main/resources/rules} — nothing here changes.
 */
final class RuleRegistry {

  private static final Map<String, Function<RuleDefinition, TemporalRule>> FACTORIES_BY_TYPE =
      Map.of(
          "forbidden-method", ForbiddenMethodRule::new,
          "mutable-side-effect-equality", MutableSideEffectEqualityRule::new,
          "forbidden-catch-type", ForbiddenCatchTypeRule::new);

  private RuleRegistry() {}

  /**
   * Loads every rule definition visible to {@code classLoader} and builds the
   * {@link TemporalRule} each one describes.
   *
   * @throws IllegalArgumentException if a definition declares a {@code type} with no
   *     registered factory
   */
  static List<TemporalRule> loadDeclarativeRules(ClassLoader classLoader) {
    return new RuleDefinitionLoader(classLoader).loadAll().stream().map(RuleRegistry::create).toList();
  }

  static TemporalRule create(RuleDefinition definition) {
    Function<RuleDefinition, TemporalRule> factory = FACTORIES_BY_TYPE.get(definition.type());
    if (factory == null) {
      throw new IllegalArgumentException(
          "Rule '" + definition.id() + "' declares unknown type '" + definition.type()
              + "'. Known types: " + FACTORIES_BY_TYPE.keySet());
    }
    return factory.apply(definition);
  }
}
