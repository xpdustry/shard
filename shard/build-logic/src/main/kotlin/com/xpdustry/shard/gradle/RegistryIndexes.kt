package com.xpdustry.shard.gradle

/** Names the registration index `:tools:indexer` generates for each source set of a module (docs/development.md "Registration"). */
object RegistryIndexes {
    /** `com.xpdustry.shard.sim.SimRegistryIndex` for `:sim`'s `main`, `...SimOracleTestRegistryIndex` for `oracleTest`. */
    fun className(module: String, sourceSet: String): String {
        val prefix = module.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        val set = if (sourceSet == "main") "" else sourceSet.replaceFirstChar(Char::uppercase)
        return "com.xpdustry.shard.${module.replace("-", "")}.$prefix${set}RegistryIndex"
    }
}
