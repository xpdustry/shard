// The server binary: config, scheduler, sessions, intents, sync, admin, chat, console, storage, ops.
plugins {
    id("shard.java")
    application
}

dependencies {
    implementation(project(":common"))
    implementation(project(":content"))
    implementation(project(":wire"))
    implementation(project(":logic"))
    implementation(project(":sim"))
    implementation(project(":compat"))
    implementation(project(":net"))
    // config (YAML), store (SQLite), ops (JSON, logs)
    implementation(libs.snakeyaml.engine)
    implementation(libs.sqlite.jdbc)
    implementation(libs.jackson.databind)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    // System.Logger (the world and scheduler packages) into logback
    runtimeOnly(libs.slf4j.platform.logging)
}

application {
    mainClass = "com.xpdustry.shard.server.Main"
    applicationName = "shard"
    // sqlite-jdbc loads a JNI library; ARCHITECTURE.md §7's flags (compact object headers are JDK 27's default).
    // Pre-touching only covers the heap's start: set -Xms to -Xmx in SHARD_OPTS on a host.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-XX:+AlwaysPreTouch")
}

// META-INF/shard/build-id for checkpoints and `shard replay`: a hash of this module's classes and its runtime jars.
val buildId by tasks.registering(com.xpdustry.shard.gradle.BuildIdTask::class) {
    code.from(sourceSets.main.map { it.output.classesDirs }, configurations.runtimeClasspath)
    outputDir = layout.buildDirectory.dir("generated/build-id")
}
sourceSets.main { resources.srcDir(buildId) }

// The unix launcher's own part (JDK check, collector, JFR, AOT cache) goes after Gradle's DEFAULT_JVM_OPTS line.
tasks.startScripts {
    val block = layout.projectDirectory.file("src/launcher/jvm.sh")
    val id = buildId.flatMap { it.outputDir.file("META-INF/shard/build-id") }
    inputs.file(block)
    inputs.file(id)
    doLast {
        val script = (this as CreateStartScripts).unixScript
        val text = script.readText()
        val line = checkNotNull(Regex("(?m)^DEFAULT_JVM_OPTS=.*$").find(text)) { "the start script has no DEFAULT_JVM_OPTS line" }
        val end = line.range.last + 1
        val launcher = block.asFile.readText().replace("@BUILD_ID@", id.get().asFile.readText().trim())
        script.writeText(text.substring(0, end) + "\n" + launcher.trimEnd() + text.substring(end))
    }
}

// bin/shard --aot-train runs src/launcher/aot-train.sh, which drives the server with tools/bots' load generator. The
// install carries the generator's jars in tools/bots/lib/: a packaging-only configuration, on none of the server's
// classpaths (ModuleRules.packagingOnly).
val aotTrainBots by configurations.registering {
    isCanBeConsumed = false
}
dependencies { aotTrainBots(project(":tools:bots")) }
distributions.main {
    contents {
        into("libexec") { from("src/launcher/aot-train.sh") }
        into("tools/bots/lib") { from(aotTrainBots) }
    }
}

// admin.KickReasonTest compares the kick reasons with vanilla's enum; sync's Call order is gated against a vanilla
// server in src/oracleTest
shard {
    vanillaJarInTests()
    oracleTests()
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // sqlite-jdbc's native library and JUnit's @TempDir go here, not to /tmp (RAM on the dev box)
    val testTmp = layout.buildDirectory.dir("tmp/test-io")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("org.sqlite.tmpdir", testTmp.get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
}
