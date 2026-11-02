// The `shard.java` convention plugin (src/main/kotlin/shard.java.gradle.kts) and the module dependency rules.
plugins { `kotlin-dsl` }

// Gradle runs on JDK 27, which Kotlin can't target yet (it falls back to 26 and warns): build-logic targets 25, as
// the rest of the build's class files do.
java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}
kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25 } }

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(gradleTestKit())
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "512m"
}
