package com.xpdustry.shard.gradle

import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * The test map's raw data (W4.12, `scripts/testmap.py`): with `SHARD_TESTMAP=1` in the environment (scripts/vm-gradle.sh
 * sets it on boat VMs), every test JVM logs the classes it loads and initializes to `build/testmap/<task>/<pid>.log`,
 * and `testmap.py update` turns the logs into "which test class depends on which Shard class, at which bytes". `testmap.py
 * select` then picks the test classes a change can affect (`vm-gradle.sh --affected`).
 *
 * The flag is a JVM argument that is not a task input: every test task gets this provider, on or off, and its
 * properties are [Internal], so a task has the same cache key either way and turning the map on costs no cache hits.
 * Which of the two counts is testmap.py's business (its doc comment). The task also writes `scans.txt`: the class
 * directories its system properties name, which its tests may walk.
 */
class TestMap(@get:Internal val enabled: Boolean, @get:Internal val dir: String) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> =
        if (enabled) listOf("-Xlog:class+load=info,class+init=info:file=$dir/%p.log::filecount=0") else listOf()

    companion object {
        fun configure(task: Test) {
            val enabled = task.project.providers.environmentVariable("SHARD_TESTMAP").orNull == "1"
            val dir = task.project.layout.buildDirectory.dir("testmap/${task.name}").get().asFile
            task.jvmArgumentProviders.add(TestMap(enabled, dir.absolutePath))
            if (!enabled) return
            task.doFirst {
                dir.mkdirs()
                // class directories handed to the tests (:sim:test's shard.sim.classes): its tests scan them
                val scanned = (this as Test).systemProperties.values.flatMap { value -> value.toString().split(java.io.File.pathSeparator) }
                    .filter { path -> path.contains("/build/classes/") && java.io.File(path).isDirectory }
                java.io.File(dir, "scans.txt").writeText(scanned.joinToString("\n"))
            }
        }
    }
}
