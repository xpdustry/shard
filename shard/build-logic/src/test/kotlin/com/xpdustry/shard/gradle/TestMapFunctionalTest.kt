package com.xpdustry.shard.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs `scripts/testmap.py` against a real build: `--affected` must select a test class that only inherits its tests, and
 * run it (review 45, Major 2). The task holds an ordinary test and a subclass of an abstract test class; only the
 * subclass initializes the changed code, since each class has its own JVM.
 */
class TestMapFunctionalTest {
    @TempDir
    lateinit var dir: File

    /** Outside the project: testmap.py hashes every non-Java file under it (rule 4). */
    @TempDir
    lateinit var aside: File

    private val pkg = "com.xpdustry.shard.fixture"
    private val script = File("../scripts/testmap.py").canonicalFile

    private fun write(path: String, text: String) = File(dir, path).apply { parentFile.mkdirs() }.writeText(text)

    private fun java(name: String, text: String, set: String = "test") = write("lib/src/$set/java/${pkg.replace('.', '/')}/$name.java", "package $pkg;\n$text")

    private fun project() {
        write("settings.gradle.kts", "rootProject.name = \"map\"\ninclude(\":lib\", \":tools:indexer\")\n")
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
        write(
            "lib/build.gradle.kts",
            """
            import com.xpdustry.shard.gradle.TestCandidates
            plugins { id("shard.java") }
            repositories { mavenCentral() }
            tasks.test {
                forkEvery = 1
                TestCandidates.configure(this)
            }
            """.trimIndent(),
        )
        java("Engine", "public class Engine { public static int value() { return 1; } }", "main")
        java("Base", "public abstract class Base { @org.junit.jupiter.api.Test void engineRuns() { org.junit.jupiter.api.Assertions.assertTrue(Engine.value() > 0); } }")
        java("InheritedTest", "class InheritedTest extends Base {}")
        java("OrdinaryTest", "class OrdinaryTest { @org.junit.jupiter.api.Test void plain() { org.junit.jupiter.api.Assertions.assertEquals(2, 1 + 1); } }")
    }

    private fun gradle(vararg arguments: String) = GradleRunner.create()
        .withProjectDir(dir)
        .withPluginClasspath()
        .withEnvironment(System.getenv() + ("SHARD_TESTMAP" to "1"))
        .withArguments(*arguments, "--stacktrace")
        .build()

    private fun testmap(vararg arguments: String): String {
        val process = ProcessBuilder("python3", "-I", script.path, *arguments)
            .directory(dir)
            .also { it.environment()["SHARD_TESTMAP_FILE"] = File(aside, "map.json.gz").path }
            .also { it.environment()["SHARD_TESTMAP_WHY"] = "100" }
            .redirectError(File(aside, "testmap.err"))
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(true, process.waitFor(2, TimeUnit.MINUTES))
        assertEquals(0, process.exitValue(), File(aside, "testmap.err").readText())
        return output.trim()
    }

    /** The test classes whose results the last `:lib:test` wrote. */
    private fun ran() = File(dir, "lib/build/test-results/test").listFiles { f -> f.name.startsWith("TEST-") }.orEmpty()
        .map { it.name.removePrefix("TEST-$pkg.").removeSuffix(".xml") }.sorted()

    /** A full run recorded in the map, then [change] to the sources: what `--affected` runs. */
    private fun affected(change: () -> Unit): List<String> {
        project()
        val since = System.currentTimeMillis() / 1000 - 1
        gradle(":lib:test")
        assertEquals(listOf("InheritedTest", "OrdinaryTest"), ran())
        testmap("update", "--since", since.toString(), "--full")
        change()
        gradle(":lib:testClasses")
        val selected = testmap("select")
        File(dir, "lib/build/test-results").deleteRecursively() // so only the selected run's results are left
        gradle(*selected.split(' ').filter { it.isNotEmpty() }.toTypedArray(), "-Pshard.affected=true")
        return ran()
    }

    @Test
    fun `code only an inherited test initializes selects and runs that subclass alone`() {
        val ran = affected { java("Engine", "public class Engine { public static int value() { return 2; } }", "main") }
        assertEquals(listOf("InheritedTest"), ran)
    }

    @Test
    fun `a new inherited-only subclass is selected and run`() {
        val ran = affected { java("NewInheritedTest", "class NewInheritedTest extends Base {}") }
        assertEquals(listOf("NewInheritedTest"), ran)
    }
}
