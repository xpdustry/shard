// Lifts wire/schema/v160.7.yaml from the vanilla jar's bytecode (WireLift) and compiles it into the codecs under
// wire/src/generated/java (WireGen). Owner: WP14. Compiling :wire reads neither the jar nor the schema; `check` runs
// `relift` and `checkWireGen`, which fail when the committed schema or generated code is stale. :wire's RegistryTest
// cross-checks the generated ids and tables against a booted vanilla.
plugins { id("shard.java") }

dependencies {
    implementation(libs.snakeyaml.engine)
}

val schema = rootProject.layout.projectDirectory.file("wire/schema/v160.7.yaml")
val generated = rootProject.layout.projectDirectory.dir("wire/src/generated/java")
val generatedPackage = "com.xpdustry.shard.wire.v160_7"

tasks.test {
    systemProperty("com.xpdustry.shard.wirelift.schema", schema.asFile.absolutePath)
    systemProperty("com.xpdustry.shard.wirelift.generated", generated.asFile.absolutePath)
    inputs.file(schema).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(generated).withPathSensitivity(PathSensitivity.RELATIVE)
}

fun JavaExec.tool(vararg arguments: Any) {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.xpdustry.shard.tools.wirelift.Main"
    maxHeapSize = "700m"
    args(*arguments)
}

/** A verification run that passed leaves a marker, so it is up to date until its inputs change. */
fun JavaExec.passes(name: String) {
    val marker = layout.buildDirectory.file("wirelift/$name.ok")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("ok\n") }
}

tasks.register<JavaExec>("lift") {
    group = "shard"
    description = "Lifts the wire schema from the vanilla jar into wire/schema/v160.7.yaml, with layout reports in build/wirelift."
    val jar = shard.vanillaJar.get()
    tool("lift", jar, schema.asFile, layout.buildDirectory.dir("wirelift").get().asFile)
    inputs.file(jar)
    outputs.file(schema)
}

val relift = tasks.register<JavaExec>("relift") {
    group = "verification"
    description = "Lifts the vanilla jar again and fails if the committed wire/schema/v160.7.yaml differs."
    val jar = shard.vanillaJar.get()
    tool("relift", jar, schema.asFile)
    inputs.file(jar)
    inputs.file(schema)
    passes("relift")
}

tasks.register<JavaExec>("generateWire") {
    group = "shard"
    description = "Generates the codecs in wire/src/generated/java from wire/schema/v160.7.yaml."
    tool("gen", schema.asFile, generated.asFile, generatedPackage)
    inputs.file(schema)
    outputs.dir(generated)
}

val checkWireGen = tasks.register<JavaExec>("checkWireGen") {
    group = "verification"
    description = "Generates the codecs again and fails if the committed wire/src/generated/java differs."
    tool("regen", schema.asFile, generated.asFile, generatedPackage)
    inputs.file(schema)
    inputs.dir(generated)
    passes("checkWireGen")
}

tasks.check { dependsOn(relift, checkWireGen) }
