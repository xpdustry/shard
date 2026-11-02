package com.xpdustry.shard.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/** Fails when this project's declared project dependencies break [ModuleRules]. */
@CacheableTask
abstract class ModuleDependencyCheck : DefaultTask() {
    @get:Input
    abstract val projectPath: Property<String>

    /** `"<configuration> <project path>"` for each declared project dependency, collected at configuration time. */
    @get:Input
    abstract val declared: ListProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val module = projectPath.get()
        val dependencies = declared.get().map(ModuleRules.Dependency::parse)
        val violations = ModuleRules.violations(module, dependencies)
        report.get().asFile.writeText(
            buildString {
                appendLine("module $module")
                dependencies.forEach { appendLine("  $it") }
                violations.forEach { appendLine("violation: $it") }
            },
        )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module dependency rules broken (ARCHITECTURE.md §2):\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
    }
}
