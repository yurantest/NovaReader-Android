package com.novareader.app.search

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.novareader.app.NovaLog
import org.jsoup.Jsoup
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * Скачивание книги в папку, выбранную пользователем через SAF —
 * точная копия логики DownloadWorker / FlibustaDownloadWorker из
 * book_search_window.py.
 *
 * На ПК было:
 *   - QFileDialog.getSaveFileName (первый раз) → пользователь выбрал путь
 *   - save_path = _resolve_save_path(...) — генерировал имя "<title>.<ext>",
 *     если файл существует, добавлял "(N)"
 *   - DownloadWorker.get(url, stream=True).iter_content(8192) — просто
 *     писал байты в открытый файл
 *   - Никакого импорта в библиотеку, распаковки, addBook.
 *
 * На Android:
 *   - Первый раз пользователь выбирает ПАПКУ через ACTION_OPEN_DOCUMENT_TREE,
 *     tree Uri сохраняется в SettingsManager.downloadFolderUri.
 *   - Все последующие скачивания идут в ту же папку молча.
 *   - Имя файла — suggestedFileName(book, link), как "_resolve_save_path".
 *   - Если файл с таким именем уже есть — добавляем " (N)" — как на ПК.
 *   - Пишем байты в поток выбранного DocumentFile.
 *   - Никакого LibraryManager.addBook.
 */
object BookDownloader {
    private const val TAG = "NovaReader.BookDL"

    data class Result(
        val success: Boolean,
        val message: String,
        val savedName: String? = null,
    )

    /**
     * Скачивает книгу в папку tree Uri. Имя файла — suggestedFileName.
     * Если файл с таким именем уже есть, добавляет " (N)" до свободного.
     *
     * @param folderUri tree Uri папки (из SettingsManager.getDownloadFolderUri)
     */
    fun downloadToFolder(
        context: Context,
        book: BookSearchResult,
        link: DownloadLink,
        folderUri: Uri,
        onProgress: ((Int) -> Unit)? = null,
    ): Result {
        return try {
            val url = resolveDownloadUrl(book, link)
                ?: return Result(false, "Нет URL для скачивания")
            val referer = refererFor(book)
            val suggested = suggestedFileName(book, link)

            val folder = DocumentFile.fromTreeUri(context, folderUri)
                ?: return Result(false, "Не удалось открыть папку сохранения")

            val finalName = uniqueFileName(folder, suggested)
            val newFile = folder.createFile(mimeFor(finalName), finalName)
                ?: return Result(false, "Не удалось создать файл в выбранной папке")

            val out: OutputStream = context.contentResolver.openOutputStream(newFile.uri)
                ?: return Result(false, "Не удалось открыть файл для записи")

            val dl = SearchHttp.downloadRaw(url, referer, onProgress)
            if (dl.bytes == null || dl.bytes.isEmpty()) {
                out.use { }
                try { newFile.delete() } catch (_: Exception) {}
                return Result(false, dl.error ?: "Пустой ответ HTTP ${dl.code}")
            }

            out.use { it.write(dl.bytes); it.flush() }
            NovaLog.d(TAG, "Сохранено: ${newFile.name} (${dl.bytes.size} байт)")
            Result(true, "Сохранено: ${newFile.name}", newFile.name)
        } catch (e: FileNotFoundException) {
            NovaLog.e(TAG, "downloadToFolder FileNotFound: ${e.message}")
            Result(false, "Папка недоступна. Возможно, права потеряны — выберите папку заново.")
        } catch (e: IOException) {
            NovaLog.e(TAG, "downloadToFolder IOException: ${e.message}")
            Result(false, "Ошибка ввода-вывода: ${e.message}")
        } catch (e: Exception) {
            NovaLog.e(TAG, "downloadToFolder failed: ${e.message}")
            Result(false, e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Имя файла по умолчанию — как Python "_clean_filename_title(title) + .ext".
     *  - Флибуста fb2 → <title>.fb2.zip (сайт отдаёт zip)
     *  - Флибуста epub/mobi → <title>.epub / <title>.mobi
     *  - SearchFloor   → <title>.fb2.zip
     *  - MoreKnig      → <title>.fb2 (или .epub/.mobi — по link.format)
     *  - Author.Today  → не скачивается
     */
    fun suggestedFileName(book: BookSearchResult, link: DownloadLink): String {
        val safeTitle = book.title
            .replace(Regex("[<>:\"/\\\\|?*]"), "")
            .trim()
            .ifBlank { "book" }
            .take(120)
        val ext = when (book.source) {
            SearchSource.FLIBUSTA -> {
                val fmt = link.format.ifBlank { "fb2" }.lowercase()
                if (fmt == "fb2") "fb2.zip" else fmt
            }
            SearchSource.SEARCHFLOOR -> "fb2.zip"
            SearchSource.MOREKNIG -> link.format.ifBlank { "fb2" }.lowercase()
            SearchSource.AUTHOR_TODAY -> "txt"
        }
        return "$safeTitle.$ext"
    }

    /** Резолв прямого URL скачивания — как в Python _start_*_download. */
    fun resolveDownloadUrl(book: BookSearchResult, link: DownloadLink): String? {
        return when (book.source) {
            SearchSource.AUTHOR_TODAY -> null
            SearchSource.SEARCHFLOOR -> {
                val id = book.bookId
                    ?: Regex("/b/(\\d+)").find(book.url)?.groupValues?.get(1)
                    ?: return null
                val base = siteBase(book.url).ifBlank { SiteParsers.searchfloorBase(null) }
                "$base/book/$id"
            }
            SearchSource.FLIBUSTA -> {
                if (link.url.isNotBlank()) link.url
                else {
                    val id = book.bookId
                        ?: Regex("/b/(\\d+)").find(book.url)?.groupValues?.get(1)
                        ?: return null
                    val base = siteBase(book.url).ifBlank { SiteParsers.flibustaBase(null) }
                    "$base/b/$id/${link.format.ifBlank { "fb2" }}"
                }
            }
            SearchSource.MOREKNIG -> link.url
        }
    }

    /** Referer — как в Python HEADERS['Referer'] / FlibustaDownloadWorker. */
    fun refererFor(book: BookSearchResult): String? = when (book.source) {
        SearchSource.SEARCHFLOOR -> siteBase(book.url).ifBlank { SiteParsers.searchfloorBase(null) } + "/"
        SearchSource.FLIBUSTA -> siteBase(book.url).ifBlank { SiteParsers.flibustaBase(null) }
        SearchSource.MOREKNIG -> book.url
        SearchSource.AUTHOR_TODAY -> "https://author.today"
    }

    /** Резолв ссылок download.php со страницы MoreKnig. */
    fun resolveMoreKnigLinks(bookUrl: String): List<DownloadLink> {
        val html = SearchHttp.get(bookUrl, "https://moreknig.org") ?: return emptyList()
        val doc = Jsoup.parse(html, bookUrl)
        val links = mutableListOf<DownloadLink>()
        for (a in doc.select("a[href*=download.php]").take(8)) {
            val href = a.absUrl("href").ifBlank { a.attr("href") }
            if (href.isBlank()) continue
            val full = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "https://moreknig.org$href"
                else -> "https://moreknig.org/$href"
            }
            val text = a.text().lowercase()
            val fmt = when {
                "pdf" in text -> "pdf"
                "epub" in text -> "epub"
                "txt" in text -> "txt"
                "rtf" in text -> "rtf"
                "mobi" in text -> "mobi"
                else -> "fb2"
            }
            links += DownloadLink(url = full, format = fmt)
        }
        return links.distinctBy { it.format }
    }

    /** Ленивое получение обложки — как на ПК. */
    fun fetchCoverFromBookPage(book: BookSearchResult): String? {
        return try {
            when (book.source) {
                SearchSource.SEARCHFLOOR -> {
                    val id = book.bookId ?: Regex("/b/(\\d+)").find(book.url)?.groupValues?.get(1)
                    if (id != null) {
                        "${siteBase(book.url).ifBlank { SiteParsers.searchfloorBase(null) }}/cover/$id"
                    } else null
                }
                SearchSource.FLIBUSTA -> {
                    val html = SearchHttp.get(book.url, siteBase(book.url)) ?: return null
                    val doc = Jsoup.parse(html, book.url)
                    for (img in doc.select("img[src]")) {
                        val src = img.attr("src").trim()
                        if (src.startsWith("/i/") || src.startsWith("/ib/")) {
                            return absUrl(book.url, src)
                        }
                    }
                    val id = book.bookId
                    if (id != null) "https://static.flibusta.is/i/$id.jpg" else null
                }
                SearchSource.MOREKNIG -> {
                    val html = SearchHttp.get(book.url, book.url) ?: return null
                    val doc = Jsoup.parse(html, book.url)
                    doc.selectFirst(".full-story img, .full-text img, article img, .poster img, img[src*=/uploads/]")
                        ?.absUrl("src")
                        ?.takeIf { it.isNotBlank() && !it.endsWith(".svg") }
                }
                SearchSource.AUTHOR_TODAY -> {
                    val html = SearchHttp.get(book.url, "https://author.today") ?: return null
                    val doc = Jsoup.parse(html, book.url)
                    doc.selectFirst("meta[property=og:image]")?.attr("content")
                        ?: doc.selectFirst(".book-cover img, .work-cover img, .cover img")?.absUrl("src")
                }
            }
        } catch (_: Exception) { null }
    }

    // ── Вспомогательные ─────────────────────────────────────────────────

    private fun siteBase(url: String): String =
        Regex("^(https?://[^/]+)").find(url)?.groupValues?.get(1).orEmpty()

    private fun absUrl(pageUrl: String, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> siteBase(pageUrl) + href
        else -> siteBase(pageUrl) + "/" + href
    }

    /** MIME по расширению — используется только для DocumentFile.createFile. */
    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "zip" -> "application/zip"
        "fb2" -> "application/x-fictionbook+xml"
        "epub" -> "application/epub+zip"
        "mobi" -> "application/x-mobipocket-ebook"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Уникальное имя файла в папке — аналог _resolve_save_path:
     * если "Title.fb2.zip" уже есть, вернёт "Title (2).fb2.zip",
     * потом "Title (3).fb2.zip" и т.д.
     */
    private fun uniqueFileName(folder: DocumentFile, wanted: String): String {
        if (folder.findFile(wanted) == null) return wanted
        val dot = wanted.lastIndexOf('.')
        val stem = if (dot > 0) wanted.substring(0, dot) else wanted
        val ext = if (dot > 0) wanted.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (folder.findFile(candidate) == null) return candidate
            n++
        }
    }
}