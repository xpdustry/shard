// World, tables, phases, commands and all gameplay. Deterministic: no threads, sockets, clocks or wire bytes.
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
    api(project(":content"))
    api(project(":logic"))
    // test fixtures read the vanilla dumps in data/vanilla-v160.7/
    testImplementation(libs.jackson.databind)
}

// ActiveListTest replays vanilla's EntityGroup; DeterminismTest reads this module's compiled classes.
shard { vanillaJarInTests() }
// src/oracleTest: the tile, placement, flowfield, entity id, spatial and physics gates against a booted vanilla
shard { oracleTests() }

tasks.test {
    systemProperty("shard.sim.classes", sourceSets.main.get().output.classesDirs.asPath)
}

tasks.withType<Test>().configureEach {
    systemProperty("shard.data", rootProject.layout.projectDirectory.dir("data/vanilla-v160.7").asFile.absolutePath)
}
