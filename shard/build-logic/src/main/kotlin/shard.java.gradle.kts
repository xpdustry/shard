// Conventions for every Shard Java module: `plugins { id("shard.java") }`. See shard/README.md "Building and testing".
import com.xpdustry.shard.gradle.ModuleDependencyCheck
import com.xpdustry.shard.gradle.ModuleRules
import com.xpdustry.shard.gradle.RegistryIndexes
import com.xpdustry.shard.gradle.ShardExtension
import com.xpdustry.shard.gradle.ShardTests
import com.xpdustry.shard.gradle.TestMap
import com.xpdustry.shard.gradle.TickMathCheck

plugins { `java-library` }

extensions.create<ShardExtension>("shard", project)
val libs = the<VersionCatalogsExtension>().named("libs")

// JDK 27 compiles, tests and runs Shard (ARCHITECTURE.md §7); class files stay at 25.
java { toolchain.languageVersion = JavaLanguageVersion.of(27) }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial,-processing", "-parameters"))
}

// Same sources, same jar bytes: the server's build id (BuildIdTask) hashes the jars.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-launcher").get())
    // The registration index (docs/development.md "Registration"), for main code and tests alike. Tools don't register.
    if (!ModuleRules.isTool(path)) {
        annotationProcessor(project(":tools:indexer"))
        testAnnotationProcessor(project(":tools:indexer"))
    }
}

// The class each source set's registration index is generated as: com.xpdustry.shard.sim.SimRegistryIndex for :sim's
// main code, SimTestRegistryIndex for its tests, and so on (docs/development.md "Registration").
if (!ModuleRules.isTool(path)) {
    val module = name
    the<SourceSetContainer>().configureEach {
        val index = RegistryIndexes.className(module, name)
        tasks.named<JavaCompile>(compileJavaTaskName) { options.compilerArgs.add("-Ashard.registry.index=$index") }
    }
}

val excludedTags = ShardTests.excludedTags(providers)
// 1 on the shared dev box; scripts/vm-gradle.sh sets SHARD_TEST_FORKS on boat VMs from their CPUs and memory
val testForks = providers.environmentVariable("SHARD_TEST_FORKS").orNull?.toIntOrNull() ?: 1

tasks.withType<Test>().configureEach {
    maxParallelForks = testForks
    TestMap.configure(this) // with SHARD_TESTMAP=1, each test JVM logs the classes it loads (scripts/testmap.py)
    // vm-gradle.sh --affected names test classes; one whose tests are all tag-excluded (slow, client) matches nothing
    if (providers.gradleProperty("shard.affected").orNull == "true") filter.isFailOnNoMatchingTests = false
}

tasks.test {
    useJUnitPlatform { excludeTags(*excludedTags.toTypedArray()) }
    maxHeapSize = "1g"
}

val modulePath = path // the project's; inside the task block below, `path` would be the task's
val checkModuleDependencies by tasks.registering(ModuleDependencyCheck::class) {
    description = "Checks this module's project dependencies against ARCHITECTURE.md §2."
    group = "verification"
    projectPath = modulePath
    declared = provider {
        configurations.flatMap { configuration ->
            configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} ${it.path}" }
        }
    }
    report = layout.buildDirectory.file("reports/module-dependencies.txt")
}

tasks.check { dependsOn(checkModuleDependencies) }

// Per-tick code computes transcendentals with StrictMath, never Math's CPU-dependent intrinsics (docs/development.md
// "Determinism rules").
if (modulePath in TickMathCheck.perTick) {
    val mainClasses = the<SourceSetContainer>().named("main").map { it.output.classesDirs }
    val checkTickMath by tasks.registering(TickMathCheck::class) {
        description = "Checks that per-tick code calls StrictMath's transcendentals, never Math's."
        group = "verification"
        classes.from(mainClasses)
        report = layout.buildDirectory.file("reports/tick-math.txt")
    }
    tasks.check { dependsOn(checkTickMath) }
}
