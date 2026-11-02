// Replay side quest: the view model (what a replay viewer sees each frame), the view stream encoder, and recorders
// from vanilla (through :tools:oracle) and from a Shard World. A browser viewer is not part of this repository.
import com.xpdustry.shard.gradle.Oracle

plugins { id("shard.java") }

dependencies {
    implementation(project(":sim"))
    implementation(project(":compat"))
    implementation(project(":tools:oracle"))
    implementation(libs.jackson.databind)
}

fun JavaExec.recorder(main: String) {
    group = "shard"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = main
    maxHeapSize = "768m"
    jvmArgs(Oracle.jvmArgs)
    systemProperty("com.xpdustry.shard.oracle.log", "warn")
    // -Pargs="--map triad --mode pvp --minutes 5 --out ../replay/recordings/triad.sview"
    args((providers.gradleProperty("args").orNull ?: "").split(' ').filter(String::isNotBlank))
    workingDir = rootProject.layout.projectDirectory.asFile
}

tasks.register<JavaExec>("recordVanilla") {
    description = "Plays a scripted match on vanilla v160.7 (the oracle) and writes its view stream; -Pargs=\"...\"."
    recorder("com.xpdustry.shard.tools.replay.vanilla.RecordVanilla")
}

tasks.register<JavaExec>("recordShard") {
    description = "Loads a bundled map into a Shard World, flies scripted units and writes its view stream; -Pargs=\"...\"."
    recorder("com.xpdustry.shard.tools.replay.shard.RecordShard")
}

tasks.register<JavaExec>("replayService") {
    description = "Records a Shard match, then serves its view stream by re-simulating segments from checkpoints; -Pargs=\"--check\" verifies them."
    recorder("com.xpdustry.shard.tools.replay.shard.ReplayService")
}

tasks.register<JavaExec>("dumpView") {
    description = "Prints a view stream's decoded frames as JSON lines, for checking other decoders; -Pargs=\"file.sview [every]\"."
    recorder("com.xpdustry.shard.tools.replay.view.DumpView")
}

tasks.register<JavaExec>("splitView") {
    description = "Splits a view stream into header.json, terrain and segments/<i> for static lazy hosting; -Pargs=\"file.sview outDir\"."
    recorder("com.xpdustry.shard.tools.replay.view.SplitView")
}

tasks.test {
    Oracle.configure(this)
}
