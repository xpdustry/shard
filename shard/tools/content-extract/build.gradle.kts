// Vanilla -> YAML content pack (proto 20 Extract.java), and the oracle gates of the content system. Owner: WP10.
import com.xpdustry.shard.gradle.Oracle

plugins { id("shard.java") }

dependencies {
    implementation(project(":tools:oracle"))
    // The gates compare Shard's loader, bake and patchgen (:content) against vanilla
    testImplementation(project(":content"))
    // VanillaReplayTest replays a bundled map's world with the vanilla pack's ContentSource
    testImplementation(project(":testkit"))
}

val pack = rootProject.layout.projectDirectory.dir("content/src/main/resources/content/vanilla")

tasks.test {
    // Each test class boots its own vanilla (static globals): one JVM per class
    Oracle.configure(this)
    systemProperty("com.xpdustry.shard.content.pack", pack.asFile.absolutePath)
    inputs.dir(pack).withPathSensitivity(PathSensitivity.RELATIVE).optional()
}

val extract by tasks.registering(JavaExec::class) {
    group = "shard"
    description = "Writes the vanilla content pack to content/src/main/resources/content/vanilla/."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.tools.extract.Extract"
    args(pack.asFile.absolutePath)
    maxHeapSize = "768m"
    jvmArgs(Oracle.jvmArgs)
    systemProperty("com.xpdustry.shard.oracle.log", "warn")
    outputs.dir(pack)
}
