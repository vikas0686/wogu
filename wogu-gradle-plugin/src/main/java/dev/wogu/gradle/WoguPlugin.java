package dev.wogu.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskProvider;

/**
 * Registers the {@code woguValidate} task and, once the {@code java} plugin is present,
 * wires it to the project's main source set and to the {@code build} lifecycle task.
 *
 * <p>Applying this plugin is sufficient for {@code gradle build} to run WoGu
 * automatically:
 *
 * <pre>{@code
 * plugins {
 *   id("dev.wogu") version "1.0.0"
 * }
 * }</pre>
 */
public final class WoguPlugin implements Plugin<Project> {

  static final String TASK_NAME = "woguValidate";
  static final String EXTENSION_NAME = "wogu";

  @Override
  public void apply(Project project) {
    WoguExtension extension = project.getExtensions().create(EXTENSION_NAME, WoguExtension.class);
    extension.getSkip().convention(false);
    extension.getFailOnViolation().convention(true);

    TaskProvider<WoguValidateTask> validateTask =
        project.getTasks().register(TASK_NAME, WoguValidateTask.class, task -> {
          task.setGroup("verification");
          task.setDescription("Runs WoGu workflow validators and fails the build on violations.");
          task.getProjectNameProperty().convention(project.getName());
          task.getProjectDirectory().convention(project.getLayout().getProjectDirectory());
          task.getReportDirectory().convention(project.getLayout().getBuildDirectory().dir("reports/wogu"));
          task.getSkip().convention(extension.getSkip());
          task.getFailOnViolation().convention(extension.getFailOnViolation());
        });

    project.getPluginManager().withPlugin("java", appliedPlugin -> {
      JavaPluginExtension javaExtension = project.getExtensions().getByType(JavaPluginExtension.class);
      SourceSet mainSourceSet = javaExtension.getSourceSets().getByName(SourceSet.MAIN_SOURCE_SET_NAME);

      validateTask.configure(task -> {
        task.getSourceRoots().from(mainSourceSet.getAllJava().getSourceDirectories());
        task.getRuntimeClasspath().from(mainSourceSet.getCompileClasspath());
      });

      project.getTasks().named("build", buildTask -> buildTask.dependsOn(validateTask));
    });
  }
}
