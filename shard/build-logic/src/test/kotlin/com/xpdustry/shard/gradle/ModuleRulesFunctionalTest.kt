package com.xpdustry.shard.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Runs a real build with the `shard.java` plugin: a forbidden module dependency must fail `check`. */
class ModuleRulesFunctionalTest {
    @TempDir
    lateinit var dir: File

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun build(simDependency: String): GradleRunner {
        write("settings.gradle.kts", "rootProject.name = \"rules\"\ninclude(\":sim\", \":wire\", \":logic\", \":tools:indexer\")\n")
        write(
            "gradle/libs.versions.toml",
            """
            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version = "6.1.3" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
            junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
            """.trimIndent(),
        )
        for (module in listOf("wire", "logic", "tools/indexer")) write("$module/build.gradle.kts", "plugins { id(\"shard.java\") }\n")
        write("sim/build.gradle.kts", "plugins { id(\"shard.java\") }\ndependencies { implementation(project(\"$simDependency\")) }\n")
        return GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments(":sim:checkModuleDependencies", "--stacktrace")
    }

    @Test
    fun `sim depending on wire fails the build`() {
        val result = build(":wire").buildAndFail()
        assertEquals(TaskOutcome.FAILED, result.task(":sim:checkModuleDependencies")?.outcome)
        assertTrue(result.output.contains(":sim -> :wire (implementation)"), result.output)
    }

    @Test
    fun `sim depending on logic passes`() {
        val result = build(":logic").build()
        assertEquals(TaskOutcome.SUCCESS, result.task(":sim:checkModuleDependencies")?.outcome)
    }
}
