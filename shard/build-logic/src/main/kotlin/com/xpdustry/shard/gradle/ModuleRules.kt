package com.xpdustry.shard.gradle

/**
 * The module graph of ARCHITECTURE.md §2, as data. `checkModuleDependencies` (wired into `check`) fails the build when
 * a project dependency breaks it.
 *
 * - A **main** dependency (`api`, `implementation`, `compileOnly`, `compileOnlyApi`, `runtimeOnly`) must point at a
 *   module listed for the depending module, or at something those modules depend on (the transitive closure: `:compat`
 *   may use `:logic` because `:sim` does).
 * - Tools are never on a runtime classpath: no runtime module may depend on a tool in a main configuration.
 *   `:testkit` is test support and may (`:tools:oracle`); runtime modules may use it only from tests.
 * - Every other configuration (tests, `oracleTest`, annotation processors, benchmarks) is not a runtime classpath. It
 *   may also use `:testkit`, `:tools:oracle` and `:tools:indexer`.
 * - A project missing from [allowed] is a violation: add it here when you add it to `settings.gradle.kts`.
 * - A [packagingOnly] configuration is not checked: it puts another module's jars in a distribution, on no classpath.
 */
object ModuleRules {
    /** Direct main dependencies each module may declare (ARCHITECTURE.md §2, "Depends on"). */
    val allowed: Map<String, Set<String>> = mapOf(
        ":common" to setOf(),
        ":content" to setOf(":common"),
        ":wire" to setOf(":common"),
        ":logic" to setOf(":common", ":content"),
        ":sim" to setOf(":common", ":content", ":logic"),
        ":compat" to setOf(":common", ":content", ":wire", ":sim"),
        ":net" to setOf(":common"),
        ":server" to setOf(":common", ":content", ":wire", ":logic", ":sim", ":compat", ":net"),
        ":testkit" to setOf(":sim", ":compat", ":server", ":tools:oracle"),
        ":tools:oracle" to setOf(),
        ":tools:indexer" to setOf(),
        ":tools:wirelift" to setOf(":tools:oracle"),
        ":tools:content-extract" to setOf(":tools:oracle"),
        ":tools:bots" to setOf(":net", ":wire"),
        ":tools:replay" to setOf(":common", ":content", ":sim", ":compat", ":tools:oracle"),
        ":tools:fuzz" to setOf(":common", ":content", ":wire", ":sim", ":compat", ":net", ":server"),
    )

    /**
     * Configurations that only copy another module's jars into the depending module's distribution, beside its own and on
     * none of its classpaths, by module, then configuration, then the one module it may name. `:server`'s install
     * carries the load generator for `bin/shard --aot-train` (ARCHITECTURE.md §2).
     */
    val packagingOnly: Map<String, Map<String, String>> = mapOf(
        ":server" to mapOf("aotTrainBots" to ":tools:bots"),
    )

    /** Configurations that end up on a module's runtime or compile classpath for its main code. */
    val mainConfigurations = setOf("api", "implementation", "compileOnly", "compileOnlyApi", "runtimeOnly")

    /** Test support, never a runtime classpath itself: it may depend on the oracle. */
    private const val TESTKIT = ":testkit"

    /** What any non-main configuration may use on top of the module's own closure. */
    private val testSupport = setOf(TESTKIT, ":tools:oracle", ":tools:indexer")

    fun isTool(path: String) = path.startsWith(":tools:")

    /** Everything [module] may reach through main dependencies. */
    fun closure(module: String): Set<String> {
        val seen = linkedSetOf<String>()
        val stack = ArrayDeque(allowed[module].orEmpty())
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (seen.add(next)) stack.addAll(allowed[next].orEmpty())
        }
        return seen
    }

    /** One violation message per broken rule, empty when [dependencies] respect the graph. */
    fun violations(module: String, dependencies: List<Dependency>): List<String> {
        if (module !in allowed) {
            return listOf("$module is not in ModuleRules: add it to ARCHITECTURE.md §2 and build-logic's ModuleRules.kt")
        }
        val reach = closure(module)
        val out = mutableListOf<String>()
        for ((configuration, target) in dependencies) {
            if (target == module || packagingOnly[module]?.get(configuration) == target) continue
            val main = configuration in mainConfigurations
            when {
                target !in allowed ->
                    out += "$module -> $target ($configuration): $target is not in ModuleRules"
                main && isTool(target) && !isTool(module) && module != TESTKIT ->
                    out += "$module -> $target ($configuration): tools are never on a runtime classpath " +
                        "(use a test configuration)"
                main && target !in reach ->
                    out += "$module -> $target ($configuration): ARCHITECTURE.md §2 lets $module depend on " +
                        allowed.getValue(module).ifEmpty { setOf("nothing") }.joinToString(", ")
                !main && target !in reach && target !in testSupport && !isTool(module) && module != TESTKIT ->
                    out += "$module -> $target ($configuration): tests of $module may use its own dependencies, " +
                        testSupport.joinToString(", ")
            }
        }
        return out
    }

    /** A declared project dependency: `configuration` of the depending project names project `target`. */
    data class Dependency(val configuration: String, val target: String) {
        override fun toString() = "$configuration $target"

        companion object {
            fun parse(line: String): Dependency {
                val (configuration, target) = line.split(' ', limit = 2)
                return Dependency(configuration, target)
            }
        }
    }
}
