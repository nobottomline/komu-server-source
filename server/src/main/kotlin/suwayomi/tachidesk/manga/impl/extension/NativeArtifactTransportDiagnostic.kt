package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in runtime proof. It downloads, verifies and deletes; it never installs or loads an artifact. */
internal object NativeArtifactTransportDiagnostic {
    data class Configuration(
        val url: String,
        val expectedSize: Long,
        val expectedSha256: String,
    )

    data class Evidence(
        val finalHost: String,
        val size: Long,
        val sha256: String,
        val residentBytesAtStart: Long,
        val peakResidentBytes: Long,
        val residentBytesAtEnd: Long,
        val peakResidentDeltaBytes: Long,
        val cancellationErrorCode: String,
        val stagingCleanupVerified: Boolean,
        val nativeCleanupVerified: Boolean,
    )

    fun configurationFromEnvironment(environment: Map<String, String> = System.getenv()): Configuration? {
        val mode = environment[ENABLE_KEY] ?: return null
        require(mode == ENABLE_VALUE) { "Unsupported native artifact diagnostic mode" }
        val url = environment.required(URL_KEY)
        ExtensionArtifactInputPolicy.requireHttpsUrl(url)
        val sha256 = environment.required(SHA256_KEY)
        require(SHA256.matches(sha256)) { "Native artifact diagnostic SHA-256 is invalid" }
        val size = environment.required(SIZE_KEY).toLongOrNull()
        require(size != null && size in 1..StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES) {
            "Native artifact diagnostic size is invalid"
        }
        return Configuration(url, size, sha256)
    }

    suspend fun run(configuration: Configuration): Evidence {
        ExtensionArtifactInputPolicy.requireHttpsUrl(configuration.url)
        require(configuration.expectedSize in 1..StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES)
        require(SHA256.matches(configuration.expectedSha256))

        val stagingRoot = createPrivateStagingRoot()
        val nativeRoot = defaultNativeTemporaryRoot().toAbsolutePath().normalize()
        val nativeFilesBefore = snapshotFiles(nativeRoot)

        try {
            val request = request(configuration, stagingRoot)
            val transfer =
                NativeExtensionArtifactTransport(maxArtifactBytes = configuration.expectedSize)
                    .downloadWithMetrics(request)
            val metrics = requireNotNull(transfer.metrics) { "Native artifact RSS evidence is unavailable" }
            try {
                transfer.artifact.assertIntegrity(configuration.expectedSize)
                require(transfer.artifact.size == configuration.expectedSize) {
                    "Native artifact diagnostic size does not match the pinned value"
                }
                require(transfer.artifact.sha256 == configuration.expectedSha256) {
                    "Native artifact diagnostic digest does not match the pinned value"
                }
                requirePrivateArtifactPermissions(transfer.artifact.path)
                require(metrics.peakResidentDeltaBytes <= MAXIMUM_RSS_DELTA_BYTES) {
                    "Native artifact diagnostic exceeded its RSS delta budget"
                }
            } finally {
                transfer.artifact.close()
            }

            val cancellationResult = exerciseCancellation(configuration, request)
            val stagingCleanupVerified = snapshotFiles(stagingRoot).isEmpty()
            require(stagingCleanupVerified) { "Native artifact diagnostic staging cleanup failed" }
            val nativeCleanupVerified = awaitNoAdditionalFiles(nativeRoot, nativeFilesBefore)
            require(nativeCleanupVerified) { "Native artifact diagnostic native cleanup failed" }

            return Evidence(
                finalHost = requireNotNull(URI(transfer.artifact.finalUrl).host),
                size = transfer.artifact.size,
                sha256 = transfer.artifact.sha256,
                residentBytesAtStart = metrics.residentBytesAtStart,
                peakResidentBytes = metrics.peakResidentBytes,
                residentBytesAtEnd = metrics.residentBytesAtEnd,
                peakResidentDeltaBytes = metrics.peakResidentDeltaBytes,
                cancellationErrorCode = cancellationResult.errorCode ?: "missing",
                stagingCleanupVerified = stagingCleanupVerified,
                nativeCleanupVerified = nativeCleanupVerified,
            )
        } finally {
            cleanupOwnedStagingRoot(stagingRoot)
        }
    }

    private suspend fun exerciseCancellation(
        configuration: Configuration,
        request: ExtensionArtifactTransport.Request,
    ): NativeArtifactDownloadResult =
        coroutineScope {
            val bridge = CancellationBarrierBridge(JniNativeArtifactBridge)
            val transport =
                NativeExtensionArtifactTransport(
                    bridge = bridge,
                    maxArtifactBytes = configuration.expectedSize,
                )
            val job = launch(start = CoroutineStart.UNDISPATCHED) { transport.download(request) }
            check(bridge.prepared.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Native artifact cancellation did not reach the prepared state"
            }
            job.cancel(CancellationException("native artifact diagnostic cancellation"))
            bridge.release.countDown()
            job.join()
            check(job.isCancelled) { "Native artifact diagnostic coroutine was not cancelled" }
            check(bridge.completed.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Native artifact cancellation did not complete in native transport"
            }
            val resultBytes = requireNotNull(bridge.result.get()) { "Native cancellation produced no result envelope" }
            val result = RESULT_JSON.decodeFromString<NativeArtifactDownloadResult>(resultBytes.toString(StandardCharsets.UTF_8))
            check(!result.ok && result.errorCode == "cancelled") {
                "Native artifact diagnostic did not observe native cancellation"
            }
            result
        }

    private fun request(
        configuration: Configuration,
        stagingRoot: Path,
    ) = ExtensionArtifactTransport.Request(
        url = configuration.url,
        stagingDirectory = stagingRoot,
        packageName = DIAGNOSTIC_PACKAGE_NAME,
        kind = ArtifactKind.JAR,
    )

    private fun createPrivateStagingRoot(): Path {
        val root = Files.createTempDirectory("komu-native-artifact-diagnostic-").toAbsolutePath().normalize()
        Files.setPosixFilePermissions(root, PRIVATE_DIRECTORY_PERMISSIONS)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root))
        return root
    }

    private fun requirePrivateArtifactPermissions(path: Path) {
        require(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS) == PRIVATE_FILE_PERMISSIONS) {
            "Native artifact diagnostic file permissions are not private"
        }
    }

    private fun snapshotFiles(root: Path): Set<String> {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return emptySet()
        return Files.list(root).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() }
    }

    private suspend fun awaitNoAdditionalFiles(
        root: Path,
        baseline: Set<String>,
    ): Boolean {
        repeat(CLEANUP_POLL_ATTEMPTS) {
            if ((snapshotFiles(root) - baseline).isEmpty()) return true
            delay(CLEANUP_POLL_MILLISECONDS)
        }
        return (snapshotFiles(root) - baseline).isEmpty()
    }

    private fun cleanupOwnedStagingRoot(root: Path) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return
        Files.list(root).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
        Files.deleteIfExists(root)
    }

    private fun Map<String, String>.required(key: String): String =
        requireNotNull(this[key]?.takeIf { it.isNotBlank() }) { "Missing native artifact diagnostic input: $key" }

    private class CancellationBarrierBridge(
        private val delegate: NativeArtifactBridge,
    ) : NativeArtifactBridge by delegate {
        val prepared = CountDownLatch(1)
        val release = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val result = AtomicReference<ByteArray?>()

        override fun prepare(operationID: ByteArray): Boolean {
            val accepted = delegate.prepare(operationID)
            if (accepted) {
                prepared.countDown()
                check(release.await(CANCELLATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Native artifact cancellation barrier timed out"
                }
            }
            return accepted
        }

        override fun downloadToTemporaryFile(
            operationID: ByteArray,
            url: ByteArray,
            maximumBytes: Long,
        ): ByteArray =
            try {
                delegate.downloadToTemporaryFile(operationID, url, maximumBytes).also(result::set)
            } finally {
                completed.countDown()
            }
    }

    private val RESULT_JSON = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val PRIVATE_DIRECTORY_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    private val PRIVATE_FILE_PERMISSIONS =
        setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )

    private const val ENABLE_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC"
    private const val ENABLE_VALUE = "download-cancel-v1"
    private const val URL_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_URL"
    private const val SHA256_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_SHA256"
    private const val SIZE_KEY = "KOMU_NATIVE_ARTIFACT_DIAGNOSTIC_SIZE"
    private const val DIAGNOSTIC_PACKAGE_NAME = "app.komu.diagnostic.nativeartifact"
    private const val MAXIMUM_RSS_DELTA_BYTES = 32L * 1024L * 1024L
    private const val CANCELLATION_TIMEOUT_SECONDS = 15L
    private const val CLEANUP_POLL_ATTEMPTS = 200
    private const val CLEANUP_POLL_MILLISECONDS = 25L
}
