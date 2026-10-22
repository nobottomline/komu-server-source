package eu.kanade.tachiyomi.source.local.io

import suwayomi.tachidesk.server.ApplicationDirs
import java.io.File

class LocalSourceFileSystem(
    private val applicationDirs: ApplicationDirs,
) {
    fun getBaseDirectories(): Sequence<File> = sequenceOf(File(applicationDirs.localMangaRoot))

    fun getFilesInBaseDirectories(): Sequence<File> =
        getBaseDirectories()
            // Get all the files inside all baseDir
            .flatMap { it.listFiles().orEmpty().toList() }

    fun getMangaDirectory(name: String): File? = mangaDirectories(name).firstOrNull()

    fun getFilesInMangaDirectory(name: String): Sequence<File> =
        mangaDirectories(name)
            .flatMap { it.listFiles().orEmpty().toList() }

    private fun mangaDirectories(name: String): Sequence<File> {
        // A manga URL is one leaf name. Retain the old enumeration's containment
        // without listing every sibling directory for each lookup.
        if (name.isEmpty() || name == "." || name == ".." || File(name).isAbsolute || File(name).name != name) {
            return emptySequence()
        }
        return getBaseDirectories().map { File(it, name) }.filter(File::isDirectory)
    }
}
