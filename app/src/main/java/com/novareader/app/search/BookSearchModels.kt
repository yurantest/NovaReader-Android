package com.novareader.app.search

data class DownloadLink(
    val url: String,
    val format: String,
    /** SearchFloor fb2 часто отдаётся как zip-архив. */
    val isZip: Boolean = false,
)

data class BookSearchResult(
    val title: String,
    val authors: List<String> = emptyList(),
    val url: String,
    val source: SearchSource,
    val coverUrl: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val series: String? = null,
    val bookId: String? = null,
    val downloadLinks: List<DownloadLink> = emptyList(),
    /** Author.Today — только чтение онлайн, без скачивания (как на ПК). */
    val canDownload: Boolean = downloadLinks.isNotEmpty(),
)

enum class SearchSource(val displayName: String, val id: String) {
    AUTHOR_TODAY("Author.Today", "author"),
    SEARCHFLOOR("Цокольный этаж", "searchfloor"),
    MOREKNIG("MoreKnig", "moreknig"),
    FLIBUSTA("Флибуста", "flibusta");
}
