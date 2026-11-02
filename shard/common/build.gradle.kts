// Arc-exact math, Rand, byte IO, primitive collections and the registration index. No game concepts.
plugins { id("shard.java") }

// The exactness tests compare against Arc's own classes from the vanilla jar.
shard { vanillaJarInTests() }
