// Every v160.7 byte format: building codecs, entity sync adapters, world stream, msav, rules JSON, patches.
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
    api(project(":content"))
    api(project(":wire"))
    api(project(":sim"))
    // the bundled-map helpers
    testImplementation(project(":testkit"))
}

// Bundled maps for the msav tests, and vanilla-backed tests in src/oracleTest (W1.7).
shard {
    vanillaJarInTests()
    oracleTests()
}
