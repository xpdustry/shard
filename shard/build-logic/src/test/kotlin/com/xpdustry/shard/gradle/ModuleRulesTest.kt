package com.xpdustry.shard.gradle

import com.xpdustry.shard.gradle.ModuleRules.Dependency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ModuleRulesTest {
    private fun violations(module: String, vararg deps: String) =
        ModuleRules.violations(module, deps.map(Dependency::parse))

    @Test
    fun `the architecture graph passes`() {
        for ((module, deps) in ModuleRules.allowed) {
            assertEquals(listOf<String>(), ModuleRules.violations(module, deps.map { Dependency("implementation", it) }))
        }
    }

    @Test
    fun `sim may not reach wire, compat, net or server`() {
        for (target in listOf(":wire", ":compat", ":net", ":server")) {
            val found = violations(":sim", "implementation $target")
            assertEquals(1, found.size, "$target: $found")
            assertTrue(found.single().contains("lets :sim depend on :common, :content, :logic"), found.single())
        }
    }

    @Test
    fun `a module may use what its dependencies use`() {
        assertEquals(listOf<String>(), violations(":compat", "api :logic", "implementation :common"))
        assertEquals(listOf<String>(), violations(":testkit", "api :wire"))
    }

    @Test
    fun `tools are never on a runtime classpath`() {
        for (configuration in ModuleRules.mainConfigurations) {
            val found = violations(":server", "$configuration :tools:oracle")
            assertTrue(found.single().contains("tools are never on a runtime classpath"), found.single())
        }
        assertEquals(listOf<String>(), violations(":sim", "testImplementation :tools:oracle"))
        assertEquals(listOf<String>(), violations(":sim", "annotationProcessor :tools:indexer"))
        assertEquals(listOf<String>(), violations(":tools:bots", "implementation :net"))
    }

    @Test
    fun `only the named packaging configuration may carry a tool`() {
        assertEquals(listOf<String>(), violations(":server", "aotTrainBots :tools:bots"))
        // another tool there is no packaging exception (the oracle is test support, which any test configuration may use)
        assertTrue(violations(":server", "aotTrainBots :tools:replay").single().contains("tests of :server"))
        assertTrue(violations(":sim", "aotTrainBots :tools:bots").single().contains("tests of :sim"))
    }

    @Test
    fun `tests may not reach around the graph`() {
        assertTrue(violations(":sim", "testImplementation :compat").single().contains("tests of :sim"))
        assertEquals(listOf<String>(), violations(":sim", "testImplementation :testkit", "oracleTestImplementation :sim"))
    }

    @Test
    fun `unknown modules are reported`() {
        assertTrue(violations(":plugins").single().contains("not in ModuleRules"))
        assertTrue(violations(":server", "implementation :plugins").single().contains(":plugins is not in ModuleRules"))
    }

    @Test
    fun `the graph is acyclic`() {
        for (module in ModuleRules.allowed.keys) {
            assertTrue(module !in ModuleRules.closure(module), "$module reaches itself")
        }
    }

    @Test
    fun `gated tags`() {
        assertEquals(listOf("slow", "client"), ShardTests.excludedTags(null as String?))
        assertEquals(listOf<String>(), ShardTests.excludedTags("all"))
        assertEquals(listOf("client"), ShardTests.excludedTags("slow"))
    }
}
