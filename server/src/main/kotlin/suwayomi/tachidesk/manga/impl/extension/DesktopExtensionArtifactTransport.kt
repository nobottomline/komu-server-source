package suwayomi.tachidesk.manga.impl.extension

/*
 * Copyright (C) Contributors to the Suwayomi project
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Dedicated cookie-free streaming transport for non-embedded JVM runtimes. */
internal class DesktopExtensionArtifactTransport(
    private val client: OkHttpClient = dedicatedClient(),
    private val maxArtifactBytes: Long = StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES,
) : ExtensionArtifactTransport {
    init {
        require(maxArtifactBytes in 1..StagedArtifact.DEFAULT_MAX_ARTIFACT_BYTES) {
            "Artifact size limit must fit the signed-JAR verifier ceiling"
        }
        require(!client.followRedirects && !client.followSslRedirects) {
            "Artifact HTTP client must expose every redirect to the transport policy"
        }
        require(client.cache == null) { "Artifact HTTP client must not use a response cache" }
        require(client.cookieJar === CookieJar.NO_COOKIES) { "Artifact HTTP client must not send shared cookies" }
    }

    override suspend fun download(request: ExtensionArtifactTransport.Request): StagedArtifact {
        ExtensionArtifactInputPolicy.requireHttpsUrl(request.url)
        ExtensionArtifactInputPolicy.requirePackageName(request.packageName)
        val stagingRoot = requireStagingRoot(request.stagingDirectory)
        val packageToken =
            MessageDigest
                .getInstance("SHA-256")
                .digest(request.packageName.toByteArray())
                .toHex()
                .take(PACKAGE_TOKEN_LENGTH)
        val stagingPath = Files.createTempFile(stagingRoot, "extension-$packageToken-", ".staging.${request.kind.suffix}")

        try {
            var currentUrl = request.url.toHttpUrl()
            val visitedUrls = linkedSetOf(currentUrl.toString())
            repeat(MAX_REDIRECTS + 1) { redirectCount ->
                when (val outcome = execute(currentUrl, stagingPath)) {
                    is DownloadOutcome.Redirect -> {
                        check(redirectCount < MAX_REDIRECTS) { "Extension artifact exceeded the redirect limit" }
                        ExtensionArtifactInputPolicy.requireHttpsUrl(outcome.url.toString())
                        check(visitedUrls.add(outcome.url.toString())) { "Extension artifact redirect loop detected" }
                        currentUrl = outcome.url
                    }

                    is DownloadOutcome.Complete -> {
                        val artifact =
                            StagedArtifact(
                                path = stagingPath,
                                size = outcome.size,
                                sha256 = outcome.sha256,
                                finalUrl = currentUrl.toString(),
                                kind = request.kind,
                            )
                        artifact.assertIntegrity(maxArtifactBytes)
                        return artifact
                    }
                }
            }
            error("Extension artifact redirect state is unreachable")
        } catch (failure: Throwable) {
            Files.deleteIfExists(stagingPath)
            throw failure
        }
    }

    private suspend fun execute(
        url: HttpUrl,
        stagingPath: Path,
    ): DownloadOutcome =
        suspendCancellableCoroutine { continuation ->
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .header("Cache-Control", "no-store")
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (!continuation.isActive) {
                            response.close()
                            Files.deleteIfExists(stagingPath)
                            return
                        }
                        try {
                            val outcome = response.use { processResponse(it, url, stagingPath) }
                            continuation.resume(outcome) { _, _, _ -> Files.deleteIfExists(stagingPath) }
                        } catch (failure: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(failure)
                            } else {
                                Files.deleteIfExists(stagingPath)
                            }
                        }
                    }
                },
            )
        }

    private fun processResponse(
        response: Response,
        requestedUrl: HttpUrl,
        stagingPath: Path,
    ): DownloadOutcome {
        check(response.request.url == requestedUrl) { "Artifact HTTP client changed the requested URL" }
        if (response.code in REDIRECT_STATUS_CODES) {
            val locations = response.headers("Location")
            require(locations.size == 1) { "Extension artifact redirect must have exactly one Location" }
            val redirectUrl = requireNotNull(requestedUrl.resolve(locations.single())) { "Extension artifact redirect is invalid" }
            ExtensionArtifactInputPolicy.requireHttpsUrl(redirectUrl.toString())
            return DownloadOutcome.Redirect(redirectUrl)
        }
        check(response.isSuccessful) { "Extension artifact HTTP status is ${response.code}" }
        val contentEncoding = response.header("Content-Encoding")
        require(contentEncoding == null || contentEncoding.equals("identity", ignoreCase = true)) {
            "Extension artifact response uses unsupported content encoding"
        }
        val declaredLength = response.body.contentLength()
        require(declaredLength == -1L || declaredLength in 1..maxArtifactBytes) {
            "Extension artifact declared size is invalid"
        }

        var downloadedBytes = 0L
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel
            .open(
                stagingPath,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        downloadedBytes += count
                        require(downloadedBytes <= maxArtifactBytes) { "Extension artifact exceeds the streaming size limit" }
                        digest.update(buffer, 0, count)
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        while (bytes.hasRemaining()) output.write(bytes)
                    }
                }
                output.force(true)
            }
        require(downloadedBytes > 0) { "Extension artifact is empty" }
        return DownloadOutcome.Complete(downloadedBytes, digest.digest().toHex())
    }

    private fun requireStagingRoot(path: Path): Path {
        val root = path.toAbsolutePath().normalize()
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "Extension staging root must be an existing real directory"
        }
        return root
    }

    private sealed interface DownloadOutcome {
        data class Redirect(
            val url: HttpUrl,
        ) : DownloadOutcome

        data class Complete(
            val size: Long,
            val sha256: String,
        ) : DownloadOutcome
    }

    private fun ByteArray.toHex(): String = StagedArtifact.run { toHex() }

    companion object {
        private val REDIRECT_STATUS_CODES = setOf(300, 301, 302, 303, 307, 308)
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val PACKAGE_TOKEN_LENGTH = 16
        private const val MAX_REDIRECTS = 5
        private const val USER_AGENT = "Komu-ExtensionArtifactTransport/1"

        private fun dedicatedClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .cookieJar(CookieJar.NO_COOKIES)
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(5, TimeUnit.MINUTES)
                .build()
    }
}
