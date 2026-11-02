package com.xpdustry.shard.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/**
 * Runs real `forkEvery = 1` test tasks with [TestCandidates] on and off: a test through a composed annotation and a test
 * inherited from a jar must run, and fail, either way (review 45, Major 1).
 */
class TestCandidatesFunctionalTest {
    @TempDir
    lateinit var dir: File

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun project() {
        write("settings.gradle.kts", "rootProject.name = \"candidates\"\ninclude(\":lib\", \":tools:indexer\")\n")
        write(
            "gradle/libs.versions.toml",
            """
            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version = "6.1.3" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter" }
            junit-launcher = { module = "org.junit.platform:junit-platform-launcher" }
            """.trimIndent(),
        )
        write("tools/indexer/build.gradle.kts", "plugins { id(\"shard.java\") }\n")
        // the superclass lives in a jar: the test class directories hold only the subclass
        val classes = File(dir, "jar-classes").apply { mkdirs() }
        val source = File(dir, "jar-src/p/Base.java").apply {
            parentFile.mkdirs()
            writeText("package p; public abstract class Base { @org.junit.jupiter.api.Test public void inherited() { org.junit.jupiter.api.Assertions.fail(\"inherited\"); } }")
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.path, "-cp", System.getProperty("java.class.path"), source.path))
        JarOutputStream(File(dir, "lib/base.jar").apply { parentFile.mkdirs() }.outputStream()).use { zip ->
            zip.putNextEntry(JarEntry("p/Base.class"))
            zip.write(File(classes, "p/Base.class").readBytes())
            zip.closeEntry()
        }
        write(
            "lib/build.gradle.kts",
            """
            import com.xpdustry.shard.gradle.TestCandidates
            plugins { id("shard.java") }
            repositories { mavenCentral() }
            dependencies { testImplementation(files("base.jar")) }
            tasks.test {
                forkEvery = 1
                TestCandidates.configure(this)
            }
            """.trimIndent(),
        )
        write(
            "lib/src/test/java/p/Composed.java",
            "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @org.junit.jupiter.api.Test public @interface Composed {}",
        )
        write("lib/src/test/java/p/ComposedTest.java", "package p; class ComposedTest { @Composed void composed() { org.junit.jupiter.api.Assertions.fail(\"composed\"); } }")
        write("lib/src/test/java/p/InheritingTest.java", "package p; class InheritingTest extends Base {}")
        write("lib/src/test/java/p/Helper.java", "package p; class Helper { static int one() { return 1; } }")
    }

    private fun run(vararg arguments: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withArguments(":lib:cleanTest", ":lib:test", "--continue", "--stacktrace", *arguments)
        .buildAndFail()

    private fun failures(output: String) = output.lines().filter { it.endsWith(" FAILED") && !it.startsWith("> Task") }.map { it.trim().removePrefix("p.") }.sorted()

    @Test
    fun `a composed annotation and a test inherited from a jar fail with candidates on, as with candidates off`() {
        project()
        val off = failures(run("-Pshard.testCandidates=off", "-i").output)
        val on = failures(run("-i").output)
        assertEquals(listOf("InheritingTest > inherited() FAILED", "ComposedTest > composed() FAILED").sorted(), off)
        assertEquals(off, on)
    }
}
