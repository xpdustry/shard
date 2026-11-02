package com.xpdustry.shard.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

/** Which class files [TestCandidates] lets Gradle skip: never one that has a test. */
class TestCandidatesTest {
    @TempDir
    lateinit var dir: File

    private val src by lazy { File(dir, "src") }
    private val out by lazy { File(dir, "classes").apply { mkdirs() } }
    private val base by lazy { File(dir, "base").apply { mkdirs() } }

    private fun source(name: String, text: String) = File(src, "p/$name.java").apply { parentFile.mkdirs(); writeText(text) }

    private fun compile(to: File, vararg files: File) {
        val jars = dir.listFiles { f -> f.name.endsWith(".jar") }.orEmpty().joinToString("") { File.pathSeparator + it }
        val classpath = System.getProperty("java.class.path") + File.pathSeparator + base + jars
        val code = ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-d", to.path, "-cp", classpath, *files.map { it.path }.toTypedArray())
        assertEquals(0, code)
    }

    /** The class files under [out] Gradle would still fork for, as binary names. */
    private fun kept(vararg patterns: String, dirs: List<File> = listOf(base)): List<String> {
        val decision = TestCandidates.Decision(dirs, listOf(patterns.toSet()))
        return out.walk().filter { it.isFile && it.name.endsWith(".class") }
            .filter { !decision.runsNoTest(it, out) }
            .map { it.relativeTo(out).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .sorted().toList()
    }

    private fun fixture() {
        compile(
            out,
            source("RouterOracleTest", """
                package p;
                public class RouterOracleTest {
                    static final class Run { void run() {} }
                    record Arrival(int frame) {}
                    @org.junit.jupiter.api.Test void chain() { new Run().run(); }
                }
            """.trimIndent()),
            source("Helpers", "package p; public class Helpers { static int twice(int x) { return 2 * x; } }"),
            source("Cases", """
                package p;
                public class Cases {
                    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints = {1}) void conveyorMoves(int n) {}
                }
            """.trimIndent()),
            source("Contract", "package p; public interface Contract { @org.junit.jupiter.api.Test default void holds() {} }"),
            source("Implementer", "package p; public class Implementer implements Contract, Runnable { public void run() {} }"),
        )
    }

    @Test
    fun `only classes with tests get a JVM`() {
        fixture()
        assertEquals(listOf("p.Cases", "p.Contract", "p.Implementer", "p.RouterOracleTest"), kept())
    }

    @Test
    fun `a wildcard filter keeps the classes it can match, method names included`() {
        fixture()
        assertEquals(listOf("p.RouterOracleTest"), kept("*.RouterOracleTest"))
        assertEquals(listOf("p.RouterOracleTest"), kept("RouterOracleTest"))
        assertEquals(listOf("p.Cases"), kept("*conveyor*"))
        assertEquals(listOf("p.RouterOracleTest"), kept("*.chain"))
        assertEquals(listOf("p.Cases", "p.Contract", "p.Implementer", "p.RouterOracleTest"), kept("p.*"))
        assertEquals(listOf("p.Cases", "p.Contract", "p.Implementer", "p.RouterOracleTest"), kept("p"))
        assertEquals(listOf<String>(), kept("q.*"))
    }

    @Test
    fun `a test inherited from another source set's class keeps its subclass`() {
        compile(base, source("Base", "package p; public abstract class Base { @org.junit.jupiter.api.Test void inherited() {} }"))
        File(src, "p/Base.java").delete()
        compile(out, source("Sub", "package p; public class Sub extends Base {}"), source("Plain", "package p; public class Plain extends Thread {}"))
        assertEquals(listOf("p.Sub"), kept())
        assertEquals(listOf("p.Sub"), kept("*.inherited"))
    }

    /** A jar of the classes compiled from [files], as a library on the classpath. */
    private fun jar(name: String, vararg files: File): File {
        val classes = File(dir, "jar-$name").apply { mkdirs() }
        compile(classes, *files)
        val jar = File(dir, "$name.jar")
        JarOutputStream(jar.outputStream()).use { zip ->
            classes.walk().filter { it.isFile }.forEach {
                zip.putNextEntry(JarEntry(it.relativeTo(classes).path.replace(File.separatorChar, '/')))
                zip.write(it.readBytes())
                zip.closeEntry()
            }
        }
        return jar
    }

    @Test
    fun `a test inherited from a jar keeps its subclass`() {
        val library = jar("lib", source("Base", "package p; public abstract class Base { @org.junit.jupiter.api.Test public void inherited() {} }"), source("Util", "package p; public class Util {}"))
        File(src, "p").deleteRecursively()
        compile(
            out,
            source("Sub", "package p; public class Sub extends Base {}"),
            source("Deep", "package p; public class Deep extends Sub {}"),
            source("UsesUtil", "package p; public class UsesUtil extends Util {}"),
        )
        // Util is in the jar and has no test: pruned, as a jar's main code always was
        assertEquals(listOf("p.Deep", "p.Sub"), kept(dirs = listOf(library)))
        assertEquals(listOf("p.Deep", "p.Sub"), kept("*.inherited", dirs = listOf(library)))
    }

    @Test
    fun `a supertype found nowhere keeps its subclass`() {
        compile(base, source("Gone", "package p; public class Gone {}"))
        compile(out, source("Orphan", "package p; public class Orphan extends Gone {}"), source("Plain", "package p; public class Plain extends Thread {}"))
        File(base, "p/Gone.class").delete()
        assertEquals(listOf("p.Orphan"), kept())
    }

    @Test
    fun `a composed annotation makes a test, however deep`() {
        compile(
            out,
            source("Composed", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @org.junit.jupiter.api.Test public @interface Composed {}"),
            source("Deeper", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) @Composed public @interface Deeper {}"),
            source("Plain", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Plain {}"),
            source("Direct", "package p; public class Direct { @Composed void fails() {} }"),
            source("Indirect", "package p; public class Indirect { @Deeper void fails() {} }"),
            source("OnClass", "package p; @Composed public class OnClass {}"),
            source("Marked", "package p; public class Marked { @Plain void helper() {} @Deprecated void old() {} }"),
        )
        // the annotation types are classes too, and carry the test annotation themselves
        assertEquals(listOf("p.Composed", "p.Deeper", "p.Direct", "p.Indirect", "p.OnClass"), kept())
        assertEquals(listOf("p.Direct"), kept("*Direct"))
    }

    @Test
    fun `an annotation found nowhere keeps its class`() {
        compile(base, source("Mystery", "package p; @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME) public @interface Mystery {}"))
        compile(out, source("Tagged", "package p; public class Tagged { @Mystery void m() {} }"))
        File(base, "p/Mystery.class").delete()
        assertEquals(listOf("p.Tagged"), kept())
    }
}
