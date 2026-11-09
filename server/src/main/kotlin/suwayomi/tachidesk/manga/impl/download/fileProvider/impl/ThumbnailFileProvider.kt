package suwayomi.tachidesk.manga.impl.download.fileProvider.impl

import app.komu.covers.CoverCacheWriter
import app.komu.covers.CoverPublicationGate
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
    // Komu (ADR-0084 Review 3): the fetch, replaceable in tests to interleave a refresh.
    private val fetchThumbnail: suspend (Int, CoverPublicationGate.Generation) -> Pair<InputStream, String> = { id, generation ->
        Manga.fetchMangaThumbnail(id, generation)
    },
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

    /**
     * Komu (ADR-0084 Reviews 3–4): the library cover for one request,
     * downloaded when absent. The cached copy is found and opened under the
     * publication gate (`CoverCacheWriter.openCached`), as is the temporary
     * cache the download reads first; a file that vanished anyway is
     * fetched. The answer is the bytes this call fetched whether or not
     * they were cached: a refresh that overtook the fetch keeps them out of
     * the cache, so looking the file up again could find nothing.
     */
    suspend fun getOrDownloadImage(): Pair<InputStream, String> =
        CoverCacheWriter.openCached(FullSizeCovers.publication, mangaId, applicationDirs.thumbnailDownloadsRoot)
            ?: downloadAndSave().served

    private suspend fun downloadImpl(): Boolean {
        val isExistingFile = getFilePath() != null
        if (isExistingFile) {
            return true
        }

        return downloadAndSave().run {
            stream.close()
            cached
        }
    }

    // Komu (ADR-0084): the library copy is written under the publication
    // gate, at the generation captured before the cover was fetched.
    private suspend fun downloadAndSave(): CoverCacheWriter.Saved {
        val generation = FullSizeCovers.publication.generation(mangaId)
        val (inputStream, mime) = fetchThumbnail(mangaId, generation)
        makeSureDownloadDirExists()
        return CoverCacheWriter.save(
            FullSizeCovers.publication,
            mangaId,
            generation,
            File(applicationDirs.thumbnailDownloadsRoot),
            inputStream,
            mime,
        )
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
