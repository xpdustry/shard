// mlog assembler and interpreter, and the LogicHost SPI that :sim implements (WP18).
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
    api(project(":content"))
}

shard {
    vanillaJarInTests()
    oracleTests()
}

// The VM uses StrictMath. HotSpot's libm intrinsics make vanilla's Math.sin/log/pow/... differ from StrictMath in the
// last bit for a few % of inputs; without them, Math is StrictMath, so tests compare with Arc bit for bit. Oracle tests
// get the same flags from build-logic's Oracle.jvmArgs.
tasks.test { jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-UseLibmIntrinsic") }
tasks.named<Test>("oracleTest") {
    // the realistic programs and the fuzz run vanilla and Shard side by side
    maxHeapSize = "1g"
}

// Prints µs/tick for the prototype's scenarios (note 28 §3): ./gradlew :logic:bench, on a boat VM.
tasks.register<JavaExec>("bench") {
    description = "Benchmarks the interpreter against vanilla's LExecutor on the note 28 scenarios."
    group = "verification"
    val oracle = sourceSets["oracleTest"]
    classpath = oracle.runtimeClasspath
    mainClass = "com.xpdustry.shard.logic.oracle.Bench"
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Xms2g", "-Xmx2g")
    systemProperty("com.xpdustry.shard.oracle.log", "warn")
    args(providers.gradleProperty("bench.args").orElse("").get().split(' ').filter { it.isNotBlank() })
}
