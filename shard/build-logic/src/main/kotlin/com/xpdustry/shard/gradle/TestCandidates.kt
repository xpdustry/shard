package com.xpdustry.shard.gradle

import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTreeElement
import org.gradle.api.specs.Spec
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.TestFilter
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.Serializable
import java.util.zip.ZipFile

/**
 * Keeps a `forkEvery = 1` test task from starting a JVM for a class that can't run a test (W4.12).
 *
 * Gradle hands every class file of the test source set to the forks, and with `forkEvery = 1` each one costs a JVM:
 * helper classes, records and nested classes (`RouterOracleTest$Run`) included. With a `--tests` pattern that starts
 * with a wildcard (`*.PayloadBlocksOracleTest`, `*Conveyor*`), Gradle can't rule out any class either, since the
 * pattern's last part could be a method name: one fixture run started ~70 empty JVMs, 27 s for a 3 s class.
 *
 * [exclude] answers, for each class file, whether it surely runs no test. Gradle's own filter still decides what runs;
 * this only drops what it would have run nothing in, so it must never drop a class that has a test:
 * - A class runs tests only if it or a supertype carries a JUnit Jupiter test annotation (`@Test`, `@ParameterizedTest`,
 *   `@RepeatedTest`, `@TestFactory`, `@TestTemplate`, `@Nested`), or an annotation meta-annotated with one: it is kept
 *   when its constant pool names one, or when it is reachable through the supertypes and annotations below.
 *   Supertypes and annotation types are looked up in the class directories and jars of the task's classpath (this source
 *   set, and `test`'s for an `oracleTest`, and the libraries); a composed annotation is followed into its own
 *   annotations. The platform (`java/`, `javax/`, `jdk/`, `sun/`) carries no tests. A supertype or an annotation type
 *   that is found nowhere else is assumed to have tests: the class is kept (a missing dependency must not drop a test).
 * - With include patterns (the build's or `--tests`), a class is kept when a pattern could match its name, its simple
 *   name, or its name followed by any identifier in its or its supertypes' constant pools (a superset of its method
 *   names, inherited ones included). `*` matches
 *   anything, and a pattern also matches whatever starts with it and a dot.
 */
class TestCandidates(private val filter: TestFilter, private val classpath: FileCollection) : Spec<FileTreeElement>, Serializable {
    @Transient private var decision: Decision? = null

    override fun isSatisfiedBy(element: FileTreeElement): Boolean {
        if (element.isDirectory || !element.name.endsWith(".class")) return false
        var root = element.file
        repeat(element.relativePath.segments.size) { root = root.parentFile }
        val decide = decision ?: Decision(classpath.files.toList(), listOf(filter.includePatterns, commandLinePatterns()))
            .also { decision = it }
        return decide.runsNoTest(element.file, root)
    }

    /** `--tests` patterns: Gradle keeps them on its internal DefaultTestFilter. Unknown filter: no pruning. */
    private fun commandLinePatterns(): Set<String> = try {
        @Suppress("UNCHECKED_CAST")
        filter.javaClass.getMethod("getCommandLineIncludePatterns").invoke(filter) as Set<String>
    } catch (e: ReflectiveOperationException) {
        emptySet()
    }

    /**
     * The decision for one run: [dirs] are where supertypes are looked up (besides the class's own root), and
     * [patternGroups] the include pattern sets that all apply (empty ones are ignored).
     */
    class Decision(private val classpath: List<File>, patternGroups: List<Set<String>>) {
        private val groups = patternGroups.filter { it.isNotEmpty() }
            .map { patterns -> patterns.map { it to Regex(it.split('*').joinToString(".*") { part -> Regex.escape(part) }) } }
        private val jarEntries = mutableMapOf<File, Set<String>>()
        private val found = mutableMapOf<String, ClassInfo?>()

        /** True when the class file surely runs no test: Gradle needn't fork a JVM for it. */
        fun runsNoTest(file: File, root: File): Boolean {
            val info = ClassInfo.read(file) ?: return false
            val chain = Chain(root)
            chain.add(info, 0)
            return !chain.tests || !matchesPatterns(info.name, chain.strings)
        }

        /** The class [name] (internal form) from the classpath, `null` when none holds it. Jars are indexed once. */
        private fun lookup(name: String, root: File): ClassInfo? {
            val own = File(root, "$name.class")
            if (own.isFile) return ClassInfo.read(own)
            return found.getOrPut(name) {
                classpath.firstNotNullOfOrNull { entry ->
                    if (entry.isDirectory) {
                        File(entry, "$name.class").takeIf { it.isFile }?.let { ClassInfo.read(it) }
                    } else if (entry.isFile) {
                        val entries = jarEntries.getOrPut(entry) {
                            try { ZipFile(entry).use { zip -> zip.entries().asSequence().map { it.name }.toSet() } } catch (e: Exception) { emptySet() }
                        }
                        if ("$name.class" in entries) {
                            try { ZipFile(entry).use { zip -> zip.getInputStream(zip.getEntry("$name.class")).buffered().use { ClassInfo.read(it) } } } catch (e: Exception) { null }
                        } else null
                    } else null
                }
            }
        }

        /** A class, its supertypes and the annotations on it and its methods, as far as they can be found: whether any carries a test, and all the class strings. */
        private inner class Chain(val root: File) {
            var tests = false
            val strings = mutableSetOf<String>()
            private val seen = mutableSetOf<String>()

            fun add(info: ClassInfo, depth: Int) {
                if (!seen.add(info.name)) return
                strings += info.utf8
                if (info.utf8.any { it in TEST_ANNOTATIONS } || depth > 32) tests = true
                for (type in listOfNotNull(info.superName) + info.interfaces) resolve(type)?.let { add(it, depth + 1) }
                for (type in info.annotations) resolve(type)?.let { annotation(it, depth + 1) }
            }

            /** An annotation type is followed like a class (it may be meta-annotated), but its strings aren't names a pattern could match. */
            private fun annotation(info: ClassInfo, depth: Int) {
                if (!seen.add(info.name)) return
                if (info.utf8.any { it in TEST_ANNOTATIONS } || depth > 32) tests = true
                for (type in info.annotations) resolve(type)?.let { annotation(it, depth + 1) }
            }

            /** The type's class, or `null`: the platform (no tests), or a type found nowhere, which sets [tests]. */
            private fun resolve(type: String): ClassInfo? {
                if (PLATFORM.any { type.startsWith(it) }) return null
                return lookup(type, root) ?: run { tests = true; null }
            }
        }

        private fun matchesPatterns(internalName: String, strings: Set<String>): Boolean {
            if (groups.isEmpty()) return true
            val name = internalName.replace('/', '.')
            val simple = name.substringAfterLast('.')
            val members = strings.filter { IDENTIFIER.matches(it) }
            return groups.all { patterns ->
                patterns.any { (pattern, regex) ->
                    fun hit(candidate: String) = regex.matches(candidate) || candidate.startsWith("$pattern.")
                    listOf(name, simple).any { base -> hit(base) || members.any { hit("$base.$it") } }
                }
            }
        }
    }

    /**
     * The parts of a class file this needs: its name, supertypes, constant pool strings, and the types of the
     * annotations on the class and its methods (nested annotation values included).
     */
    class ClassInfo(val name: String, val superName: String?, val interfaces: List<String>, val utf8: Set<String>, val annotations: Set<String>) {
        companion object {
            fun read(file: File): ClassInfo? = try {
                file.inputStream().buffered().use { read(it) }
            } catch (e: Exception) {
                null
            }

            fun read(stream: InputStream): ClassInfo? = try {
                read(DataInputStream(stream))
            } catch (e: Exception) {
                null
            }

            private fun read(input: DataInputStream): ClassInfo? {
                if (input.readInt() != 0xCAFEBABE.toInt()) return null
                input.readUnsignedShort(); input.readUnsignedShort()
                val count = input.readUnsignedShort()
                val strings = arrayOfNulls<String>(count)
                val classes = IntArray(count)
                var i = 1
                while (i < count) {
                    when (input.readUnsignedByte()) {
                        1 -> strings[i] = input.readUTF()
                        7 -> classes[i] = input.readUnsignedShort()
                        8, 16, 19, 20 -> input.readUnsignedShort()
                        15 -> { input.readUnsignedByte(); input.readUnsignedShort() }
                        3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                        5, 6 -> { input.readLong(); i++ }
                        else -> return null
                    }
                    i++
                }
                input.readUnsignedShort()
                val self = input.readUnsignedShort()
                val parent = input.readUnsignedShort()
                val name = strings[classes[self]] ?: return null
                val superName = if (parent == 0) null else strings[classes[parent]]
                val interfaces = List(input.readUnsignedShort()) { strings[classes[input.readUnsignedShort()]] ?: "?" }
                val annotations = mutableSetOf<String>()
                repeat(input.readUnsignedShort()) { skipMember(input, strings, null) } // fields
                repeat(input.readUnsignedShort()) { skipMember(input, strings, annotations) } // methods
                attributes(input, strings, annotations)
                // annotation descriptors are `Lpkg/Name;`: the internal name is what lookups use
                val types = annotations.filter { it.startsWith("L") && it.endsWith(";") }.map { it.substring(1, it.length - 1) }.toSet()
                return ClassInfo(name, superName, interfaces, strings.filterNotNull().toSet(), types)
            }

            private fun skipMember(input: DataInputStream, strings: Array<String?>, annotations: MutableSet<String>?) {
                input.readUnsignedShort(); input.readUnsignedShort(); input.readUnsignedShort()
                attributes(input, strings, annotations)
            }

            /** Reads an attribute table, collecting the annotation types of `RuntimeVisibleAnnotations` into [annotations] when given. */
            private fun attributes(input: DataInputStream, strings: Array<String?>, annotations: MutableSet<String>?) {
                repeat(input.readUnsignedShort()) {
                    val attribute = strings[input.readUnsignedShort()]
                    val length = input.readInt()
                    if (annotations != null && attribute == "RuntimeVisibleAnnotations") {
                        repeat(input.readUnsignedShort()) { annotation(input, strings, annotations) }
                    } else {
                        input.skipNBytes(length.toLong())
                    }
                }
            }

            private fun annotation(input: DataInputStream, strings: Array<String?>, out: MutableSet<String>) {
                strings[input.readUnsignedShort()]?.let { out += it }
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    value(input, strings, out)
                }
            }

            private fun value(input: DataInputStream, strings: Array<String?>, out: MutableSet<String>) {
                when (input.readUnsignedByte().toChar()) {
                    'e' -> { input.readUnsignedShort(); input.readUnsignedShort() }
                    '@' -> annotation(input, strings, out)
                    '[' -> repeat(input.readUnsignedShort()) { value(input, strings, out) }
                    else -> input.readUnsignedShort() // a constant, a string or a class
                }
            }
        }
    }

    companion object {
        private val TEST_ANNOTATIONS = setOf(
            "Lorg/junit/jupiter/api/Test;",
            "Lorg/junit/jupiter/params/ParameterizedTest;",
            "Lorg/junit/jupiter/api/RepeatedTest;",
            "Lorg/junit/jupiter/api/TestFactory;",
            "Lorg/junit/jupiter/api/TestTemplate;",
            "Lorg/junit/jupiter/api/Nested;",
        )
        private val PLATFORM = listOf("java/", "javax/", "jdk/", "sun/")
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** Applies it to a task (see the class comment), unless `-Pshard.testCandidates=off`, which forks for every class file. */
        fun configure(task: Test) {
            if (task.project.providers.gradleProperty("shard.testCandidates").orNull == "off") return
            task.exclude(TestCandidates(task.filter, task.classpath))
        }
    }
}
