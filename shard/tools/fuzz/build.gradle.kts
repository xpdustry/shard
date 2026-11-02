// Client RPC fuzzer (WP93): an in-process server, many loopback clients sending mutated client packets, and invariants
// checked while they do. README.md has the commands.
plugins {
    id("shard.java")
    application
}

dependencies {
    implementation(project(":common"))
    implementation(project(":content"))
    implementation(project(":wire"))
    implementation(project(":sim"))
    implementation(project(":compat"))
    implementation(project(":net"))
    implementation(project(":server"))
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
}

shard {
    // the server hosts the default maps out of the vanilla jar; the fuzzer reads nothing else from it
    vanillaJarInTests()
}

application {
    mainClass = "com.xpdustry.shard.tools.fuzz.Fuzz"
    applicationName = "shard-fuzz"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// ./gradlew :tools:fuzz:fuzz -Pargs="--packets 10000000 --worlds 4 --clients 64 --out build/fuzz"
tasks.register<JavaExec>("fuzz") {
    description = "Runs the client RPC fuzzer against an in-process server; -Pargs=\"...\" (README.md)."
    group = "shard"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.tools.fuzz.Fuzz"
    maxHeapSize = providers.gradleProperty("fuzzHeap").orElse("4g").get()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val tmp = layout.buildDirectory.dir("tmp/fuzz-io")
    doFirst { tmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", tmp.get().asFile.absolutePath)
    systemProperty("org.sqlite.tmpdir", tmp.get().asFile.absolutePath)
    systemProperty("com.xpdustry.shard.fuzz.vanillaJar", shard.vanillaJar.get())
    args((providers.gradleProperty("args").orNull ?: "").split(' ').filter(String::isNotBlank))
    workingDir = layout.projectDirectory.asFile
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "1536m"
    val testTmp = layout.buildDirectory.dir("tmp/test-io")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("org.sqlite.tmpdir", testTmp.get().asFile.absolutePath)
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
}

// -Pfindings=true runs the reproductions of open findings (they fail until their owners fix them)
tasks.test {
    providers.gradleProperty("findings").orNull?.let { systemProperty("com.xpdustry.shard.fuzz.findings", it) }
}

// ./gradlew :tools:fuzz:crashLog -Pargs="crash-300-0.zip [filter]": the commands a failed world ran
tasks.register<JavaExec>("crashLog") {
    description = "Prints a world crash bundle's error and command log; -Pargs=\"bundle.zip [filter]\"."
    group = "shard"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.tools.fuzz.CrashLog"
    maxHeapSize = "1g"
    args((providers.gradleProperty("args").orNull ?: "").split(' ').filter(String::isNotBlank))
}

// ./gradlew :tools:fuzz:shrink -Pargs="crash-300-0.zip": the fewest logged inputs that still fail the world
tasks.register<JavaExec>("shrink") {
    description = "Delta-debugs a world crash bundle's log down to the inputs that still fail it; -Pargs=\"bundle.zip\"."
    group = "shard"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.tools.fuzz.Shrink"
    maxHeapSize = "2g"
    args((providers.gradleProperty("args").orNull ?: "").split(' ').filter(String::isNotBlank))
}
