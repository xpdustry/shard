// Headless protocol bots and the load generator (note 24 §6).
plugins {
    id("shard.java")
    application
}

dependencies {
    implementation(project(":net"))
    implementation(project(":wire"))
}

application {
    mainClass = "com.xpdustry.shard.tools.bots.LoadGen"
    applicationName = "shard-bots"
}
