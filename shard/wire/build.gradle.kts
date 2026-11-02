// Wire DTOs and codecs generated from wire/schema/v160.7.yaml, TypeIO, packet registry (WP14). No sockets.
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
}

// Codecs generated from wire/schema/v160.7.yaml by :tools:wirelift (./gradlew :tools:wirelift:generateWire). They are
// committed: :wire can't depend on a tool, and the normal build must not need the vanilla jar.
sourceSets.main { java.srcDir("src/generated/java") }

shard { oracleTests() }

// OldRevisionsTest reads vanilla-written saves with compat's MsavReader
dependencies { "oracleTestImplementation"(project(":testkit")) }

// L4's budget (the slow fuzz tests): -Pshard.fuzzCases=N cases per decoder, -Pshard.fuzzMinutes=M minutes per direction
tasks.named<Test>("oracleTest") {
    // a hot NullPointerException loses its stack, which tells a JSON rejection from others (ServerDecode.Verdict.json)
    jvmArgs("-XX:-OmitStackTraceInFastThrow")
    for (name in listOf("fuzzCases", "fuzzMinutes")) {
        providers.gradleProperty("shard.$name").orNull?.let { systemProperty("com.xpdustry.shard.wire.$name", it) }
    }
}
