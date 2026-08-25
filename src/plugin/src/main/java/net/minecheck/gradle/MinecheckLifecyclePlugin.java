package net.minecheck.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.TaskProvider;

/**
 * Minecheck Gradle plugin.
 *
 * <p>Provides the {@link MinecheckExtension} configuration surface and the {@link
 * MinecheckLifecycleTest} task type, plus {@link #wireLaunchDescriptor} so a build can register one
 * lifecycle test per loader (e.g. {@code testFabric}, {@code testForge}, {@code testQuilt}) and
 * have {@code ./gradlew test} run them in series.
 *
 * <p>The plugin never ships to the game and holds no dependency on Minecraft or the mod's game
 * classes. Its only coupling to the mod is: - a {@code minecheck/scripts/*.lua} tree to stage into
 * the run dir, - a JSON run result written to {@code <run>/config/minecheck/run-result.json}.
 *
 * <p>Typical usage (root build script of the mod being tested):
 *
 * <pre>
 * plugins { id("net.minecheck") }
 *
 * tasks.register&lt;MinecheckLifecycleTest&gt;("testFabric") {
 *     loaderTask       = ":fabric-mod:runClient"
 *     coordinator      = "coordinator.lua"
 *     serverScript     = "server-wait-stop.lua"
 *     serverLoaderTask = ":fabric-mod:runServer"
 *     scriptsDir       = layout.projectDirectory.dir("tests/scripts").asFile.absolutePath
 *     runDir           = layout.projectDirectory.dir("src/fabric/mod/run/client").asFile.absolutePath
 *     serverRunDir     = layout.projectDirectory.dir("src/fabric/mod/run/server").asFile.absolutePath
 *     xvfbRun          = true
 *     MinecheckLifecyclePlugin.wireLaunchDescriptor(project, loaderTask.get(),
 *             "devHelperClientLaunch", "client-launch.txt", clientLaunchFile, this)
 * }
 * tasks.named("test") { dependsOn("testFabric") }
 * </pre>
 */
public class MinecheckLifecyclePlugin implements Plugin<Project> {

  @Override
  public void apply(Project project) {
    project.getExtensions().create("devHelper", MinecheckExtension.class);
  }

  /**
   * Couples a lifecycle task to a loader's {@code devHelper*Launch} descriptor: makes it depend on
   * that task and feed its output file in as a launch input, so the orchestrator can run the game
   * as a direct {@code java} process.
   *
   * @param root the root project (used to resolve the loader subproject)
   * @param gradleTask the loader's run task path, e.g. {@code :fabric-mod:runClient}
   * @param descriptorTaskName the descriptor task name on the loader project
   * @param descriptorFileName the descriptor file name under {@code build/minecheck/}
   * @param fileProp the lifecycle task's client/server launch file property
   * @param t the lifecycle task to wire
   */
  public static void wireLaunchDescriptor(
      Project root,
      String gradleTask,
      String descriptorTaskName,
      String descriptorFileName,
      RegularFileProperty fileProp,
      MinecheckLifecycleTest t) {
    int idx = gradleTask.lastIndexOf(':');
    if (idx < 0 || idx >= gradleTask.length() - 1) {
      return;
    }
    String projectPath = gradleTask.substring(0, idx);
    if (projectPath.isEmpty() || projectPath.equals(":")) {
      projectPath = ":";
    }
    Project sub = root.findProject(projectPath);
    if (sub == null) {
      return;
    }
    TaskProvider<?> provider = sub.getTasks().named(descriptorTaskName);
    t.dependsOn(provider);
    fileProp.set(sub.getLayout().getBuildDirectory().file("minecheck/" + descriptorFileName));
  }
}
