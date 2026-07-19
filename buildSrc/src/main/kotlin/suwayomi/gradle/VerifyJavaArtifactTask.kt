package suwayomi.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.DataInputStream
import java.io.File
import java.util.jar.Manifest
import java.util.zip.ZipFile

internal object JavaArtifactCompatibilityVerifier {
    data class Report(
        val classCount: Int,
        val targetVisibleClassCount: Int,
        val futureMultiReleaseClassCount: Int,
        val highestTargetVisibleMajor: Int,
    )

    fun verify(
        archive: File,
        targetJava: Int,
    ): Report {
        require(targetJava > 0) { "Target Java version must be positive" }
        require(archive.isFile) { "Artifact does not exist: $archive" }

        val targetMajor = targetJava + CLASS_MAJOR_OFFSET
        val violations = mutableListOf<String>()
        var classCount = 0
        var targetVisibleClassCount = 0
        var futureMultiReleaseClassCount = 0
        var highestTargetVisibleMajor = 0

        ZipFile(archive).use { zip ->
            val isMultiRelease =
                zip.getEntry(MANIFEST_PATH)?.let { manifestEntry ->
                    zip.getInputStream(manifestEntry).use(::Manifest)
                        .mainAttributes
                        .getValue(MULTI_RELEASE_ATTRIBUTE)
                        ?.equals("true", ignoreCase = true) == true
                } ?: false

            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !entry.name.endsWith(CLASS_SUFFIX)) continue

                classCount++
                if (entry.name == MODULE_INFO_PATH || entry.name.endsWith(MODULE_INFO_SUFFIX)) {
                    violations += "Module descriptor is not allowed in the embedded classpath artifact: ${entry.name}"
                    continue
                }

                val versionedMatch = VERSIONED_CLASS.matchEntire(entry.name)
                val versionedJava = versionedMatch?.groupValues?.get(1)?.toIntOrNull()
                val isUnderVersions = entry.name.startsWith(VERSIONS_PREFIX)

                if (isUnderVersions && versionedJava == null) {
                    violations += "Malformed multi-release class path: ${entry.name}"
                    continue
                }
                if (versionedJava != null && !isMultiRelease) {
                    violations += "Versioned class without Multi-Release manifest: ${entry.name}"
                    continue
                }

                val major =
                    zip.getInputStream(entry).use { input ->
                        DataInputStream(input).use { data ->
                            val magic = data.readInt()
                            if (magic != CLASS_MAGIC) {
                                violations += "Invalid class header: ${entry.name}"
                                return@use null
                            }
                            data.readUnsignedShort() // minor
                            data.readUnsignedShort()
                        }
                    } ?: continue

                val visibleAtTarget = versionedJava == null || versionedJava <= targetJava
                if (!visibleAtTarget) {
                    futureMultiReleaseClassCount++
                    continue
                }

                targetVisibleClassCount++
                highestTargetVisibleMajor = maxOf(highestTargetVisibleMajor, major)
                if (major > targetMajor) {
                    violations +=
                        "${entry.name}: class major $major exceeds Java $targetJava maximum $targetMajor"
                }
            }
        }

        if (classCount == 0) violations += "Artifact contains no class files"
        if (violations.isNotEmpty()) {
            val shown = violations.take(MAX_REPORTED_VIOLATIONS)
            val omitted = violations.size - shown.size
            val suffix = if (omitted > 0) "\n... and $omitted more violation(s)" else ""
            throw IllegalArgumentException(
                "Artifact is not Java $targetJava compatible:\n${shown.joinToString("\n")}$suffix",
            )
        }

        return Report(
            classCount = classCount,
            targetVisibleClassCount = targetVisibleClassCount,
            futureMultiReleaseClassCount = futureMultiReleaseClassCount,
            highestTargetVisibleMajor = highestTargetVisibleMajor,
        )
    }

    private const val CLASS_MAGIC = 0xCAFEBABE.toInt()
    private const val CLASS_MAJOR_OFFSET = 44
    private const val CLASS_SUFFIX = ".class"
    private const val MANIFEST_PATH = "META-INF/MANIFEST.MF"
    private const val MULTI_RELEASE_ATTRIBUTE = "Multi-Release"
    private const val MODULE_INFO_PATH = "module-info.class"
    private const val MODULE_INFO_SUFFIX = "/module-info.class"
    private const val VERSIONS_PREFIX = "META-INF/versions/"
    private const val MAX_REPORTED_VIOLATIONS = 50
    private val VERSIONED_CLASS = Regex("^META-INF/versions/(\\d+)/.+\\.class$")
}

@DisableCachingByDefault(because = "The task verifies an existing packaged artifact and produces no output")
abstract class VerifyJavaArtifactTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archiveFile: RegularFileProperty

    @get:Input
    abstract val targetJava: Property<Int>

    @TaskAction
    fun verifyArtifact() {
        val javaVersion = targetJava.get()
        val report =
            try {
                JavaArtifactCompatibilityVerifier.verify(archiveFile.get().asFile, javaVersion)
            } catch (error: IllegalArgumentException) {
                throw GradleException(error.message ?: "Java artifact compatibility verification failed", error)
            }

        logger.lifecycle(
            "Java {} artifact verification passed: {} classes, {} visible, {} future multi-release, highest visible major {}",
            javaVersion,
            report.classCount,
            report.targetVisibleClassCount,
            report.futureMultiReleaseClassCount,
            report.highestTargetVisibleMajor,
        )
    }
}
