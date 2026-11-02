package com.xpdustry.shard.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.security.MessageDigest

/**
 * Writes `META-INF/shard/build-id`, which `sim.persist.BuildInfo` reads and `shard replay` compares: a SHA-256 over
 * the code the server runs (its classes and every runtime jar, in a fixed order). Any code change gives a new id; a
 * rebuild of the same sources gives the same one, because `shard.java` makes archives reproducible. Git can't be used:
 * boat VMs get a copy of `shard/` without `.git`.
 */
@CacheableTask
abstract class BuildIdTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val code: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val digest = MessageDigest.getInstance("SHA-256")
        for (root in code.files.sortedBy { it.name }) {
            val files = if (root.isDirectory) root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
            else listOf(root)
            for (file in files) {
                val name = if (root.isDirectory) file.relativeTo(root).invariantSeparatorsPath else file.name
                digest.update(name.toByteArray())
                digest.update(0)
                digest.update(file.readBytes())
            }
        }
        val id = digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        val out = File(outputDir.get().asFile, "META-INF/shard/build-id")
        out.parentFile.mkdirs()
        out.writeText("$id\n")
    }
}
