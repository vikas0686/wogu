/**
 * Gradle integration for WoGu.
 *
 * <p>{@link dev.wogu.gradle.WoguPlugin} registers the {@code woguValidate} task, wires it
 * to the main source set once the {@code java} plugin is applied, and attaches it to the
 * {@code build} lifecycle task. {@link dev.wogu.gradle.WoguValidateTask} runs the same
 * discover-run-report sequence as the Maven plugin, directly against {@code wogu-core}
 * and {@code wogu-report}.
 */
package dev.wogu.gradle;
