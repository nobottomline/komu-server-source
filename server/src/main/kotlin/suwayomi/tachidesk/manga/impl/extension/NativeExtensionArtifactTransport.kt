package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import app.komu.bridge.NativeArtifactDownload
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.resumeWithException

/** iOS-only JNI transport. Merely constructing this class does not enable it. */
internal class NativeExtensionArtifactTransport(
    private val bridge: NativeArtifactBridge = JniNativeArtifactBridge,
    private val executor: Executor = NATIVE_DOWNLOAD_EXECUTOR,
    private val maxArtifactBytes: Long = StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES,
    nativeTemporaryRoot: Path = defaultNativeTemporaryRoot(),
) : ExtensionArtifactTransport {
    private val expectedNativeRoot = nativeTemporaryRoot.toAbsolutePath().normalize()

    init {
        require(maxArtifactBytes in 1..StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES) {
            "Native artifact limit must fit the signed-JAR verifier ceiling"
        }
    }

    fun isAvailable(): Boolean = NativeArtifactRuntimeProbe.probe(bridge, expectedNativeRoot).available

    override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
        ExtensionArtifactInputPolicy.requireHttpsUrl(request.url)
        ExtensionArtifactInputPolicy.requirePackageName(request.packageName)
        val stagingRoot = requireStagingRoot(request.stagingDirectory)
        check(isAvailable()) { "Native extension artifact transport ABI is unavailable" }

        val operationID = UUID.randomUUID().toString()
        val operationBytes = operationID.toByteArray(StandardCharsets.UTF_8)
        val rawResult = invokeNative(operationID, operationBytes, request.url.toByteArray(StandardCharsets.UTF_8))
        val result = decodeResult(rawResult)
        if (!result.ok) {
            throw NativeArtifactDownloadException(
                errorCode = result.errorCode ?: "native-error",
                status = result.status,
                message = result.message ?: "Native artifact download failed",
            )
        }

        return try {
            adoptNativeResult(result, operationID, stagingRoot, request)
        } catch (failure: Throwable) {
            cleanupLateNativeResult(rawResult, operationID)
            throw failure
        }
    }

    private suspend fun invokeNative(
        operationIDString: String,
        operationID: ByteArray,
        url: ByteArray,
    ): ByteArray =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { runCatching { bridge.cancel(operationID) } }
            try {
                executor.execute {
                    try {
                        if (!continuation.isActive) return@execute
                        check(bridge.prepare(operationID)) { "Native artifact operation could not be prepared" }
                        if (!continuation.isActive) bridge.cancel(operationID)
                        val result = bridge.downloadToTemporaryFile(operationID, url, maxArtifactBytes)
                        continuation.resume(result) { _, lateResult, _ ->
                            cleanupLateNativeResult(lateResult, operationIDString)
                        }
                    } catch (failure: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            } catch (failure: Throwable) {
                continuation.resumeWithException(failure)
            }
        }

    private fun decodeResult(bytes: ByteArray): NativeArtifactDownloadResult {
        val result =
            runCatching {
                RESULT_JSON.decodeFromString<NativeArtifactDownloadResult>(bytes.toString(StandardCharsets.UTF_8))
            }.getOrElse { throw SecurityException("Native artifact transport returned malformed metadata", it) }
        check(result.abiVersion == NATIVE_ARTIFACT_ABI_VERSION) { "Native artifact result ABI does not match the host" }
        return result
    }

    private fun adoptNativeResult(
        result: NativeArtifactDownloadResult,
        operationID: String,
        stagingRoot: Path,
        request: ExtensionArtifactTransport.Request,
    ): StagedArtifact {
        val finalUrl = requireNotNull(result.finalUrl) { "Native artifact result has no final URL" }
        ExtensionArtifactInputPolicy.requireHttpsUrl(finalUrl)
        check(result.status in 200..299) { "Native artifact response was not successful" }
        require(result.size in 1..maxArtifactBytes) { "Native artifact result has invalid size" }
        val nativeDigest = requireNotNull(result.sha256) { "Native artifact result has no digest" }
        require(SHA256_HEX.matches(nativeDigest)) { "Native artifact result has an invalid digest" }

        val source = requireNativeOwnedPath(result.path, operationID)
        val packageToken =
            MessageDigest
                .getInstance("SHA-256")
                .digest(request.packageName.toByteArray(StandardCharsets.UTF_8))
                .toHex()
                .take(PACKAGE_TOKEN_LENGTH)
        val destination = Files.createTempFile(stagingRoot, "extension-$packageToken-", ".staging.${request.kind.suffix}")

        try {
            require(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                "Native artifact path is not a regular file"
            }
            require(Files.size(source) == result.size) { "Native artifact size changed across the JNI boundary" }
            moveToStaging(source, destination)
            require(Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS)) {
                "Staged native artifact is not a regular file"
            }
            require(Files.size(destination) == result.size) {
                "Staged native artifact size changed"
            }
            val jvmDigest = StagedArtifact.sha256(destination, maxArtifactBytes)
            if (jvmDigest != nativeDigest) throw SecurityException("Native artifact digest changed across the JNI boundary")
            FileChannel
                .open(destination, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                .use { it.force(true) }
            return StagedArtifact(
                path = destination,
                size = result.size,
                sha256 = nativeDigest,
                finalUrl = finalUrl,
                kind = request.kind,
            )
        } catch (failure: Throwable) {
            Files.deleteIfExists(destination)
            Files.deleteIfExists(source)
            throw failure
        }
    }

    private fun moveToStaging(
        source: Path,
        destination: Path,
    ) {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun cleanupLateNativeResult(
        bytes: ByteArray,
        operationID: String,
    ) {
        val result = runCatching { decodeResult(bytes) }.getOrNull() ?: return
        if (!result.ok) return
        runCatching { Files.deleteIfExists(requireNativeOwnedPath(result.path, operationID)) }
    }

    private fun requireNativeOwnedPath(
        rawPath: String?,
        operationID: String,
    ): Path {
        val path = requireNotNull(rawPath) { "Native artifact result has no file path" }
        val unresolved = Path.of(path)
        require(unresolved.isAbsolute) { "Native artifact path must be absolute" }
        val normalized = unresolved.normalize()
        val parent = requireNotNull(normalized.parent) { "Native artifact path has no parent" }
        require(parent == expectedNativeRoot) { "Native artifact path escapes its dedicated temporary directory" }
        require(!Files.isSymbolicLink(expectedNativeRoot)) { "Native artifact temporary directory is a symlink" }
        require(Files.isDirectory(expectedNativeRoot, LinkOption.NOFOLLOW_LINKS)) {
            "Native artifact temporary directory is unavailable"
        }
        require(parent.toRealPath(LinkOption.NOFOLLOW_LINKS) == expectedNativeRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)) {
            "Native artifact temporary directory identity changed"
        }
        require(normalized.fileName.toString() == "artifact-$operationID.download") {
            "Native artifact file is not owned by this operation"
        }
        return normalized
    }

    private fun requireStagingRoot(path: Path): Path {
        val root = path.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Extension staging root must be an existing real directory"
        }
        return root
    }

    private fun ByteArray.toHex(): String = StagedArtifact.run { toHex() }

    private companion object {
        val RESULT_JSON = Json { ignoreUnknownKeys = true }
        val NATIVE_DOWNLOAD_EXECUTOR =
            Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "komu-native-artifact-download").apply { isDaemon = true }
            }
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
        const val PACKAGE_TOKEN_LENGTH = 16
    }
}

internal fun defaultNativeTemporaryRoot(): Path {
    val javaTemporaryRoot = requireNotNull(System.getProperty("java.io.tmpdir")) { "java.io.tmpdir is unavailable" }
    return Path.of(javaTemporaryRoot).resolve(NATIVE_TEMP_DIRECTORY)
}

internal interface NativeArtifactBridge {
    fun abiVersion(): Int

    fun temporaryRootMatches(expectedPath: ByteArray): Boolean

    fun prepare(operationID: ByteArray): Boolean

    fun downloadToTemporaryFile(
        operationID: ByteArray,
        url: ByteArray,
        maximumBytes: Long,
    ): ByteArray

    fun cancel(operationID: ByteArray)
}

internal object JniNativeArtifactBridge : NativeArtifactBridge {
    override fun abiVersion(): Int = NativeArtifactDownload.abiVersion()

    override fun temporaryRootMatches(expectedPath: ByteArray): Boolean = NativeArtifactDownload.temporaryRootMatches(expectedPath)

    override fun prepare(operationID: ByteArray): Boolean = NativeArtifactDownload.prepare(operationID)

    override fun downloadToTemporaryFile(
        operationID: ByteArray,
        url: ByteArray,
        maximumBytes: Long,
    ): ByteArray = NativeArtifactDownload.downloadToTemporaryFile(operationID, url, maximumBytes)

    override fun cancel(operationID: ByteArray) = NativeArtifactDownload.cancel(operationID)
}

internal const val NATIVE_ARTIFACT_ABI_VERSION = 2
internal const val NATIVE_TEMP_DIRECTORY = "komu-extension-artifacts-v1"

@Serializable
internal data class NativeArtifactDownloadResult(
    val abiVersion: Int,
    val ok: Boolean,
    val status: Int = 0,
    val finalUrl: String? = null,
    val path: String? = null,
    val size: Long = 0,
    val sha256: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

internal class NativeArtifactDownloadException(
    val errorCode: String,
    val status: Int,
    message: String,
) : Exception(message)
