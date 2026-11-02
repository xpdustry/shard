// Shared Java conventions live in build-logic/ (the `shard.java` plugin), not here.
plugins { base }

// `./gradlew check` also tests the build logic itself, including the module dependency rules.
tasks.check { dependsOn(gradle.includedBuild("build-logic").task(":check")) }
