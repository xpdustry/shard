// YAML prototypes -> frozen ContentEpoch, wire ids, kind keys (WP10).
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
    implementation(libs.snakeyaml.engine)
}
