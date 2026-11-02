// Vanilla v160.7 as a test oracle. Never a dependency of the Shard runtime; use it from tests:
//   shard { oracleTests() }   // src/oracleTest/java, one JVM per test class (docs/development.md "Building and testing")
import com.xpdustry.shard.gradle.Oracle

plugins { id("shard.java") }

dependencies {
    /** The vanilla server jar (core + Arc, runs headless). Override with -PvanillaJar=/path/to/server-release.jar. */
    api(shard.vanillaDependency)
    implementation(libs.jackson.databind)
}

val dataDir = rootProject.layout.projectDirectory.dir("data/vanilla-v160.7")

tasks.test {
    // Vanilla keeps its state in static globals (Vars, Groups, entity id counters): one JVM per test class.
    Oracle.configure(this)
    // DumpTest checks the committed dumps are what the dumper writes now
    systemProperty("com.xpdustry.shard.oracle.data", dataDir.asFile.absolutePath)
    inputs.dir(dataDir).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}

val dump by tasks.registering(JavaExec::class) {
    group = "shard"
    description = "Dumps vanilla v160.7 content, packets, entities, buildings, rules and maps to data/vanilla-v160.7/."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.oracle.dump.Dump"
    args(dataDir.asFile.absolutePath)
    maxHeapSize = "768m"
    jvmArgs(Oracle.jvmArgs)
    systemProperty("com.xpdustry.shard.oracle.log", "warn")
    outputs.dir(dataDir)
}
