package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import app.komu.covers.FullSizeCovers
import suwayomi.tachidesk.manga.impl.Manga
import suwayomi.tachidesk.manga.impl.download.fileProvider.DownloadedFilesProvider
import suwayomi.tachidesk.manga.impl.download.fileProvider.FileDownload0Args
import suwayomi.tachidesk.manga.impl.download.fileProvider.RetrieveFile0Args
import suwayomi.tachidesk.manga.impl.util.getThumbnailDownloadPath
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse
import suwayomi.tachidesk.manga.impl.util.storage.ImageResponse.getCachedImageResponse
import suwayomi.tachidesk.server.ApplicationDirs
import uy.kohesive.injekt.injectLazy
import java.io.File
import java.io.InputStream

class MissingThumbnailException : Exception("No thumbnail found")

private val applicationDirs: ApplicationDirs by injectLazy()

class ThumbnailFileProvider(
    val mangaId: Int,
) : DownloadedFilesProvider {
    private fun getFilePath(): String? {
        val thumbnailDir = applicationDirs.thumbnailDownloadsRoot
        val fileName = mangaId.toString()
        return ImageResponse.findFileNameStartingWith(thumbnailDir, fileName)
    }

    fun getImageImpl(): Pair<InputStream, String> {
        val filePathWithoutExt = getThumbnailDownloadPath(mangaId)
        val filePath = getFilePath()

        if (filePath.isNullOrEmpty()) {
            throw MissingThumbnailException()
        }

        return getCachedImageResponse(filePath, filePathWithoutExt)
    }

    override suspend fun getImage(): RetrieveFile0Args = RetrieveFile0Args(::getImageImpl)

    private suspend fun downloadImpl(): Boolean {
        val isExistingFile = getFilePath() != null
        if (isExistingFile) {
            return true
        }

        val (inputStream, mime) = Manga.fetchMangaThumbnail(mangaId)
        inputStream.use { image ->
            makeSureDownloadDirExists()
            val filePath = getThumbnailDownloadPath(mangaId)
            ImageResponse.saveImage(filePath, image, mime)
        }

        return true
    }

    override fun download(): FileDownload0Args = FileDownload0Args(::downloadImpl)

    override suspend fun delete(): Boolean {
        val filePath = getFilePath()
        if (filePath.isNullOrEmpty()) {
            return true
        }

        // Komu (ADR-0084): serialised with full-size cover publication.
        return FullSizeCovers.invalidate(mangaId) { File(filePath).delete() }
    }

    private fun makeSureDownloadDirExists() {
        val downloadDirPath = applicationDirs.thumbnailDownloadsRoot
        val downloadDir = File(downloadDirPath)

        if (!downloadDir.exists()) {
            downloadDir.mkdir()
        }
    }
}
