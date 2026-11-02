package com.xpdustry.shard.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * `checkTickMath`: fails when this module's per-tick code calls one of `Math`'s transcendentals instead of
 * `StrictMath`'s ([TickMath]; docs/development.md "Determinism rules"). Part of `check` for the modules [perTick] names.
 */
@CacheableTask
abstract class TickMathCheck : DefaultTask() {
    /** The module's compiled main classes. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = TickMath.scan(classes.files.map { it.toPath() })
        report.get().asFile.writeText(violations.joinToString("") { "$it\n" })
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Per-tick code calls ${violations.size} of Math's transcendentals (docs/development.md \"Determinism rules\"):\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    companion object {
        /** The modules that hold per-tick code. */
        val perTick: Set<String> = setOf(":common", ":content", ":logic", ":sim")
    }
}
