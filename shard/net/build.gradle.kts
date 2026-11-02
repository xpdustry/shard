// Arc-net TCP+UDP transport on virtual threads, framework messages, LZ4, stream chunking (WP15). No Netty.
plugins { id("shard.java") }

dependencies {
    api(project(":common"))
}

// The tests compare with lz4-java and drive the transport with Arc's own client, both from the vanilla jar.
shard { vanillaJarInTests() }
