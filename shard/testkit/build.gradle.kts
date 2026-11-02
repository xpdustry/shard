// Scenario DSL, the differential runner (Shard vs the vanilla oracle) and shared fixtures (WP16). Tests only.
plugins { id("shard.java") }

dependencies {
    api(project(":sim"))
    api(project(":compat"))
    api(project(":server"))
    api(project(":tools:oracle"))
    implementation(libs.snakeyaml.engine)
}

// src/oracleTest: the runner tests that boot vanilla, one JVM per class
shard { oracleTests() }

// Reports of failed diffs go to build/testkit/<scenario>/
tasks.withType<Test>().configureEach {
    systemProperty("shard.testkit.out", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}
