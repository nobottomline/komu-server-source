package suwayomi.tachidesk.manga.controller

import io.javalin.http.HttpStatus
import suwayomi.tachidesk.manga.impl.extension.AmbiguousExtensionStoreOwnershipException
import suwayomi.tachidesk.manga.impl.extension.CustomPinnedExtensionUpdateException
import suwayomi.tachidesk.manga.impl.extension.ExtensionNotInstalledException
import suwayomi.tachidesk.manga.impl.extension.ExtensionStoreSnapshotUnavailableException
import suwayomi.tachidesk.manga.impl.extension.ExtensionUpdateStoreUnavailableException
import suwayomi.tachidesk.manga.impl.extension.NativeArtifactDownloadException
import suwayomi.tachidesk.manga.impl.extension.StaleExtensionStoreSnapshotException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Update-route errors contain fixed user text, never exception details or artifact URLs. */
internal data class ExtensionUpdateFailure(
    val status: HttpStatus,
    val body: Body,
) {
    data class Body(
        val code: String,
        val message: String,
        val retryable: Boolean,
    )

    companion object {
        fun from(failure: Exception): ExtensionUpdateFailure =
            when (failure) {
                is ExtensionStoreSnapshotUnavailableException -> {
                    response(
                        HttpStatus.CONFLICT,
                        "EXTENSION_STORE_UNAVAILABLE",
                        "Refresh the extension repository before updating this extension.",
                        true,
                    )
                }

                is NoSuchElementException -> {
                    response(HttpStatus.NOT_FOUND, "EXTENSION_NOT_FOUND", "This extension could not be found.", false)
                }

                is ExtensionNotInstalledException -> {
                    response(HttpStatus.CONFLICT, "EXTENSION_NOT_INSTALLED", "Install this extension before updating it.", false)
                }

                is CustomPinnedExtensionUpdateException -> {
                    response(
                        HttpStatus.CONFLICT,
                        "EXTENSION_CUSTOM_PINNED",
                        "Switch this custom extension to its repository before updating it.",
                        false,
                    )
                }

                is ExtensionUpdateStoreUnavailableException -> {
                    response(
                        HttpStatus.CONFLICT,
                        "EXTENSION_STORE_UNAVAILABLE",
                        "Refresh the extension repository before updating this extension.",
                        true,
                    )
                }

                is AmbiguousExtensionStoreOwnershipException -> {
                    response(
                        HttpStatus.CONFLICT,
                        "EXTENSION_STORE_AMBIGUOUS",
                        "This extension is provided by multiple repositories. Resolve its repository ownership before updating it.",
                        false,
                    )
                }

                is StaleExtensionStoreSnapshotException -> {
                    response(
                        HttpStatus.CONFLICT,
                        "EXTENSION_STORE_CHANGED",
                        "The repository changed during this update. Refresh it and try again.",
                        true,
                    )
                }

                is NativeArtifactDownloadException -> {
                    if (failure.errorCode in setOf("network-error", "http-status")) downloadFailed() else unexpected()
                }

                is ConnectException, is UnknownHostException, is SocketTimeoutException -> {
                    downloadFailed()
                }

                else -> {
                    unexpected()
                }
            }

        private fun downloadFailed() =
            response(
                HttpStatus.BAD_GATEWAY,
                "EXTENSION_DOWNLOAD_FAILED",
                "The extension download failed. Check your connection and try again.",
                true,
            )

        private fun unexpected() =
            response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "EXTENSION_UPDATE_FAILED",
                "The extension could not be updated. Please try again after refreshing the repository.",
                false,
            )

        private fun response(
            status: HttpStatus,
            code: String,
            message: String,
            retryable: Boolean,
        ) = ExtensionUpdateFailure(status, Body(code, message, retryable))
    }
}
