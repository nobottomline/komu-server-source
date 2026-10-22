package suwayomi.tachidesk.manga.model.dataclass

/** Additive capability on the recent-chapter endpoint; ordinary numbered pages remain valid. */
class RecentChaptersPage(
    page: List<MangaChapterDataClass>,
    hasNextPage: Boolean,
) : PaginatedList<MangaChapterDataClass>(page, hasNextPage) {
    val supportsCursorPagination: Boolean = true
}
