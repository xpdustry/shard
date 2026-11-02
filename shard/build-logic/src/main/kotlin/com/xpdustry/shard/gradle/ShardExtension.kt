package com.xpdustry.shard.gradle

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.provider.ProviderFactory
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File

/** The `shard { }` block of every module that applies `shard.java`. */
abstract class ShardExtension(private val project: Project) {
    /**
     * The vanilla v160.7 server jar's path (core + Arc), for task arguments and system properties; see [Vanilla].
     * Classpaths take [vanillaDependency] instead.
     */
    val vanillaJar: Provider<String> = Vanilla.jar(project)

    /** The vanilla server jar as a dependency notation: the local file when there is one, else the release module. */
    val vanillaDependency: Any get() = Vanilla.dependency(project)

    /**
     * Puts the vanilla server jar on the `test` classpath, for tests that compare with Arc's or vanilla's pure classes
     * (`arc.math.Mathf`, `EntityGroup`...) without booting vanilla. They share the normal test JVM, so they must not
     * touch vanilla's static game state; use [oracleTests] for that.
     */
    fun vanillaJarInTests() {
        project.dependencies.add("testImplementation", vanillaDependency)
    }

    /**
     * Adds the `oracleTest` source set (`src/oracleTest/java`) and its task, wired into `check`: tests that boot
     * vanilla through `:tools:oracle`. Vanilla keeps its state in static globals, so each test class gets its own JVM;
     * keeping these tests out of `src/test` keeps the other tests in one JVM.
     */
    fun oracleTests() {
        val sourceSets = project.extensions.getByType<SourceSetContainer>()
        val main = sourceSets.getByName("main")
        val test = sourceSets.getByName("test")
        val oracle = sourceSets.create("oracleTest") {
            compileClasspath += main.output + test.output
            runtimeClasspath += main.output + test.output
        }
        project.configurations.getByName(oracle.implementationConfigurationName)
            .extendsFrom(project.configurations.getByName(test.implementationConfigurationName))
        project.configurations.getByName(oracle.runtimeOnlyConfigurationName)
            .extendsFrom(project.configurations.getByName(test.runtimeOnlyConfigurationName))
        project.dependencies.add(oracle.implementationConfigurationName, project.project(":tools:oracle"))
        if (!ModuleRules.isTool(project.path)) {
            project.dependencies.add(oracle.annotationProcessorConfigurationName, project.project(":tools:indexer"))
        }
        val excluded = ShardTests.excludedTags(project.providers)
        val task = project.tasks.register<Test>("oracleTest") {
            description = "Runs the tests that boot vanilla v160.7 (src/oracleTest), one JVM per test class."
            group = "verification"
            testClassesDirs = oracle.output.classesDirs
            classpath = oracle.runtimeClasspath
            useJUnitPlatform { excludeTags(*excluded.toTypedArray()) }
            Oracle.configure(this)
            shouldRunAfter(project.tasks.named("test"))
        }
        project.tasks.named("check") { dependsOn(task) }
    }
}

/**
 * Where the vanilla v160.7 server jar is, in this order: `-PvanillaJar=<path>`; `~/.cache/shard/vanilla/v160.7/`, where
 * `scripts/fetch-vanilla.sh` and the scripts outside Gradle keep it; else the module [MODULE], which Gradle downloads
 * from Mindustry's GitHub releases (settings.gradle.kts) and checks against its SHA-256 in
 * gradle/verification-metadata.xml.
 */
object Vanilla {
    const val VERSION = "v160.7"
    const val MODULE = "Anuken:server-release:$VERSION@jar"

    /** The local jar, if any: `-PvanillaJar`, else the cache directory when the file is there. */
    private fun local(project: Project): String? = project.providers.gradleProperty("vanillaJar").orNull
        ?: "${System.getProperty("user.home")}/.cache/shard/vanilla/$VERSION/server-release.jar".takeIf { File(it).isFile }

    fun dependency(project: Project): Any = local(project)?.let { project.files(it) } ?: MODULE

    /** The jar's path; resolving the module here is safe, since it is the asking project's own configuration. */
    fun jar(project: Project): Provider<String> = project.provider {
        local(project) ?: project.configurations.detachedConfiguration(project.dependencies.create(MODULE))
            .singleFile.absolutePath
    }
}

/** JVM settings for tests that boot vanilla (as `:tools:oracle`'s own tests always did). */
object Oracle {
    /**
     * Vanilla's LZ4 loads a native library. Without HotSpot's libm intrinsics, vanilla's `Math.sin/log/pow/...` equal
     * the `StrictMath` that Shard uses; with them, a few % of inputs differ in the last bit (W1.10).
     */
    val jvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic")

    fun configure(task: Test) {
        task.forkEvery = 1
        TestCandidates.configure(task) // a JVM per class that has tests, not per class file
        task.maxHeapSize = "768m"
        task.jvmArgs(jvmArgs)
        task.systemProperty("com.xpdustry.shard.oracle.log", "warn")
        // -Pshard.oracle.timing=<file>: each oracle JVM appends when it started, booted vanilla and exited (BootTiming)
        task.project.providers.gradleProperty("shard.oracle.timing").orNull
            ?.let { task.systemProperty("com.xpdustry.shard.oracle.timing", it) }
        // -Pshard.oracle.jvmArgs='-XX:StartFlightRecording:filename=/path/%p.jfr': extra flags, for profiling
        task.project.providers.gradleProperty("shard.oracle.jvmArgs").orNull
            ?.let { task.jvmArgs(it.split(' ').filter(String::isNotBlank)) }
    }
}

/** JUnit tags that only run on request: `-Pshard.tags=all`, or `-Pshard.tags=slow` for one of them. */
object ShardTests {
    val gated = listOf("slow", "client")

    fun excludedTags(providers: ProviderFactory): List<String> =
        excludedTags(providers.gradleProperty("shard.tags").orNull)

    fun excludedTags(property: String?): List<String> {
        val enabled = property.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
        return if ("all" in enabled) emptyList() else gated - enabled
    }
}
