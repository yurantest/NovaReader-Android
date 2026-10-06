package com.novareader.app.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Точный порт BookParser из book_search_window.py (Python → Kotlin).
 *
 * Каждая функция — построчный эквивалент Python-оригинала:
 *   - Author.Today:  /search?category=works&q=...&page=N,
 *                    каждая книга — отдельный GET /work/{id}
 *                    (parsится параллельно через coroutineScope/async).
 *   - SearchFloor:   /search?q=...&page=N (НЕ "/?q=..."),
 *                    пагинация ТОЛЬКО через soup.find(id='btn-next-page')
 *                    или id='div-next-page' — нет кнопки → break.
 *                    Обложка: {BASE}/cover/{id} (детерминированно).
 *                    Аннотация: {BASE}/api/annotation/{id} (JSON).
 *   - MoreKnig:      POST index.php?do=search,
 *                    search_start = page, result_from = (page-1)*10+1,
 *                    строго #dle-content и a.sres-wrap, без fallback.
 *   - Flibusta:      /booksearch?ask=...&page=N-1.
 *                    Три независимых блока h3: "Найденные серии"
 *                    (/sequence/N или /s/N), "Найденные писатели"
 *                    (/a/N с фильтром по query_words), "Найденные книги"
 *                    (/b/N, только если нет серий и авторов).
 *                    Обход в порядке: авторы → серии → книги.
 *                    Страницы автора/серии — строго внутри #main
 *                    (иначе подмешивается сайдбар "Впечатления о книгах").
 *
 * Любое расхождение с Python-оригиналом — баг.
 */
object SiteParsers {

    private const val AUTHOR_BASE = "https://author.today"
    private const val MOREKNIG_BASE = "https://moreknig.org"
    private const val DEFAULT_SEARCHFLOOR = "https://searchfloor.org"
    private const val DEFAULT_FLIBUSTA = "https://flibusta.is"

    // Python: MIN_DELAY = 0.5, MAX_DELAY = 1.5
    private const val MIN_DELAY_MS = 500L
    private const val MAX_DELAY_MS = 1500L

    fun searchfloorBase(override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/') ?: DEFAULT_SEARCHFLOOR

    fun flibustaBase(override: String?): String =
        override?.trim()?.takeIf { it.isNotEmpty() }?.trimEnd('/') ?: DEFAULT_FLIBUSTA

    private fun enc(q: String): String = URLEncoder.encode(q.trim(), "UTF-8")

    private fun abs(base: String, href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> base.trimEnd('/') + href
        else -> base.trimEnd('/') + "/" + href
    }

    private fun randomDelay() {
        val delay = MIN_DELAY_MS + (Math.random() * (MAX_DELAY_MS - MIN_DELAY_MS)).toLong()
        try { Thread.sleep(delay) } catch (_: InterruptedException) { }
    }

    // ═════════════════════════════════════════════════════════════════════
    // AUTHOR.TODAY
    // ═════════════════════════════════════════════════════════════════════
    suspend fun searchAuthorToday(
        query: String,
        limit: Int,
        maxPages: Int,
        cancelled: () -> Boolean,
        onBook: (BookSearchResult) -> Unit,
        searchId: Long,
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val seenUrls = LinkedHashSet<String>()
        val allBookUrls = mutableListOf<String>()
        var cleanQuery = query.trim()
        if (cleanQuery.endsWith(".")) cleanQuery = cleanQuery.dropLast(1)
        val encoded = enc(cleanQuery)

        for (page in 1..maxPages) {
            if (cancelled()) break
            if (count >= limit) break
            try {
                randomDelay()
                val searchUrl = "$AUTHOR_BASE/search?category=works&q=$encoded&page=$page"
                val html = SearchHttp.get(searchUrl, AUTHOR_BASE, searchId) ?: break
                val doc = Jsoup.parse(html, AUTHOR_BASE)

                val noResults = doc.text().contains(
                    Regex("Ничего не найдено|Нет результатов|No results", RegexOption.IGNORE_CASE)
                )
                if (noResults) break

                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    if ("/work/" !in href) continue
                    if ("/reviews" in href || "search" in href || "page" in href) continue
                    Regex("/work/(\\d+)").find(href) ?: continue
                    val bookUrl = abs(AUTHOR_BASE, href).substringBefore('?')
                    if (seenUrls.add(bookUrl)) allBookUrls += bookUrl
                }

                val pagination = doc.selectFirst("ul.pagination")
                var hasNext = false
                if (pagination != null) {
                    val nextLink = pagination.select("a").any { a ->
                        val t = a.text()
                        t.contains("Вперед", ignoreCase = true) ||
                                t.contains("Next", ignoreCase = true) ||
                                t.contains("→")
                    }
                    if (nextLink) {
                        hasNext = true
                    } else {
                        for (a in pagination.select("a[href]")) {
                            val pageText = a.text().trim()
                            if (pageText.toIntOrNull()?.let { it > page } == true) {
                                hasNext = true
                                break
                            }
                        }
                    }
                }
                if (!hasNext) break
            } catch (_: Exception) {
                break
            }
        }

        if (allBookUrls.isEmpty() || cancelled()) {
            return@withContext count
        }

        val toParse = allBookUrls.take(limit)
        val parsed = coroutineScope {
            toParse.map { url ->
                async(Dispatchers.IO) {
                    if (cancelled()) return@async null
                    try { parseAuthorTodayBook(url, searchId) } catch (_: Exception) { null }
                }
            }.awaitAll()
        }

        val groups = LinkedHashMap<String, MutableList<BookSearchResult>>()
        for (b in parsed) {
            if (b == null) continue
            if (b.title == "Без названия" || b.title.length <= 2) continue
            val key = b.title.lowercase().trim()
            groups.getOrPut(key) { mutableListOf() }.add(b)
        }

        for ((_, group) in groups) {
            if (cancelled() || count >= limit) break
            group.sortBy { it.bookId?.toLongOrNull() ?: Long.MAX_VALUE }
            onBook(group.first())
            count++
        }
        count
    }

    private fun parseAuthorTodayBook(url: String, searchId: Long): BookSearchResult? {
        val cleanUrl = url.replace(Regex("/reviews$"), "").trimEnd('/')
        randomDelay()
        val html = SearchHttp.get(cleanUrl, AUTHOR_BASE, searchId) ?: return null
        val doc = Jsoup.parse(html, cleanUrl)

        var title = "Без названия"
        val h1 = doc.selectFirst("h1")
        if (h1 != null && h1.text().trim().isNotEmpty()) title = h1.text().trim()
        if (title == "Без названия") {
            for (sel in listOf(".work-title", ".book-title", ".title")) {
                val tag = doc.selectFirst(sel)
                if (tag != null && tag.text().trim().isNotEmpty()) {
                    title = tag.text().trim(); break
                }
            }
        }

        var cover: String? = null
        for (sel in listOf(".book-cover img", ".work-cover img", ".cover img")) {
            val img = doc.selectFirst(sel)
            val src = img?.attr("src")?.takeIf { it.isNotBlank() }
            if (src != null) { cover = abs(AUTHOR_BASE, src); break }
        }
        if (cover == null) {
            val og = doc.selectFirst("meta[property=og:image]")
            val c = og?.attr("content")?.takeIf { it.isNotBlank() }
            if (c != null) cover = abs(AUTHOR_BASE, c)
        }

        var description = ""
        for (sel in listOf(".description", ".book-description", ".work-description", ".annotation")) {
            val tag = doc.selectFirst(sel)
            val text = tag?.text()?.trim()
            if (text != null && text.length > 20) {
                description = if (text.length > 300) text.take(300) + "..." else text
                break
            }
        }
        if (description.isEmpty()) {
            val m = doc.selectFirst("meta[name=description]")
            val content = m?.attr("content")?.trim()
            if (content != null && content.length > 20) {
                description = if (content.length > 300) content.take(300) + "..." else content
            }
        }

        val authors = mutableListOf<String>()
        for (sel in listOf(".author", ".book-author", ".work-author")) {
            val tags = doc.select(sel)
            if (tags.isNotEmpty()) {
                for (t in tags) {
                    val s = t.text().trim()
                    if (s.isNotEmpty()) authors += s
                }
                if (authors.isNotEmpty()) break
            }
        }

        return BookSearchResult(
            title = title,
            authors = authors,
            url = cleanUrl,
            source = SearchSource.AUTHOR_TODAY,
            coverUrl = cover,
            description = description.ifBlank { null },
            bookId = Regex("/work/(\\d+)").find(cleanUrl)?.groupValues?.get(1),
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // SEARCHFLOOR
    // ═════════════════════════════════════════════════════════════════════
    suspend fun searchSearchFloor(
        query: String,
        limit: Int,
        maxPages: Int,
        baseOverride: String?,
        cancelled: () -> Boolean,
        onBook: (BookSearchResult) -> Unit,
        searchId: Long,
    ): Int = withContext(Dispatchers.IO) {
        val base = searchfloorBase(baseOverride)
        var count = 0
        val seenUrls = LinkedHashSet<String>()
        val allBookUrls = mutableListOf<String>()
        val encoded = enc(query)

        for (page in 1..maxPages) {
            if (cancelled()) break
            if (count >= limit) break
            try {
                randomDelay()
                // Python: f"{SEARCHFLOOR_BASE}/search?q={encoded_query}&page={page}"
                val searchUrl = "$base/search?q=$encoded&page=$page"
                val html = SearchHttp.get(searchUrl, base, searchId) ?: break
                val doc = Jsoup.parse(html, base)

                val pageUrls = mutableListOf<String>()
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    if ("/b/" !in href) continue
                    Regex("/b/(\\d+)").find(href) ?: continue
                    val full = abs(base, href.substringBefore('?'))
                    if (seenUrls.add(full)) pageUrls += full
                }

                allBookUrls += pageUrls

                // Python: next_btn = soup.find(id='btn-next-page') or
                //         soup.find(id='div-next-page'); if not next_btn: break
                val nextBtn = doc.getElementById("btn-next-page")
                    ?: doc.getElementById("div-next-page")
                if (nextBtn == null) break
            } catch (_: Exception) {
                break
            }
        }

        if (allBookUrls.isEmpty() || cancelled()) {
            return@withContext count
        }

        val toParse = allBookUrls.take(limit)
        val parsed = coroutineScope {
            toParse.map { url ->
                async(Dispatchers.IO) {
                    if (cancelled()) return@async null
                    try { parseSearchFloorBook(base, url, searchId) } catch (_: Exception) { null }
                }
            }.awaitAll()
        }

        for (b in parsed) {
            if (cancelled() || count >= limit) break
            if (b == null) continue
            if (b.title.isBlank() || b.title == "Без названия") continue
            onBook(b)
            count++
        }
        count
    }

    private fun searchfloorCover(base: String, bookId: String?): String? {
        if (bookId.isNullOrBlank()) return null
        return "$base/cover/$bookId"
    }

    private fun searchfloorAnnotation(base: String, bookId: String?, searchId: Long): String {
        if (bookId.isNullOrBlank()) return ""
        return try {
            randomDelay()
            val url = "$base/api/annotation/$bookId"
            val raw = SearchHttp.get(url, base, searchId) ?: return ""
            try {
                val obj = JSONObject(raw)
                for (key in listOf("text", "annotation", "content", "body", "description")) {
                    if (obj.has(key)) {
                        val v = obj.opt(key)
                        when (v) {
                            is String -> if (v.trim().isNotEmpty()) return v.trim()
                            is JSONObject -> {
                                val nested = v.optString("text", "")
                                if (nested.isNotBlank()) return nested.trim()
                            }
                        }
                    }
                }
                ""
            } catch (_: Exception) {
                Jsoup.parse(raw).body()?.text()?.trim().orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseSearchFloorBook(base: String, url: String, searchId: Long): BookSearchResult? {
        randomDelay()
        val html = SearchHttp.get(url, base, searchId) ?: return null
        val doc = Jsoup.parse(html, url)

        val bookId = Regex("/b/(\\d+)").find(url)?.groupValues?.get(1)

        val titleTag = doc.selectFirst("title")
        var title = "Без названия"
        var author: String? = null
        if (titleTag != null) {
            val titleText = titleTag.text().trim()
            for (sep in listOf(" / ", " — ", " – ", " - ")) {
                if (sep in titleText) {
                    val parts = titleText.split(sep)
                    title = parts.dropLast(1).joinToString(sep).trim()
                    author = parts.last().trim()
                    break
                }
            }
            if (author == null) title = titleText
        }

        val description = if (bookId != null) searchfloorAnnotation(base, bookId, searchId) else ""
        val cover = searchfloorCover(base, bookId)

        return BookSearchResult(
            title = title,
            authors = if (author != null) listOf(author) else emptyList(),
            url = url,
            source = SearchSource.SEARCHFLOOR,
            coverUrl = cover,
            description = description.ifBlank { null },
            bookId = bookId,
            downloadLinks = if (bookId != null) listOf(
                DownloadLink(url = "$base/book/$bookId", format = "fb2", isZip = true)
            ) else emptyList(),
        )
    }

    fun fetchSearchFloorAnnotation(baseOverride: String?, bookId: String): String {
        val base = searchfloorBase(baseOverride)
        return searchfloorAnnotation(base, bookId, 0L)
    }

    // ═════════════════════════════════════════════════════════════════════
    // MOREKNIG
    // ═════════════════════════════════════════════════════════════════════
    suspend fun searchMoreKnig(
        query: String,
        limit: Int,
        maxPages: Int,
        cancelled: () -> Boolean,
        onBook: (BookSearchResult) -> Unit,
        searchId: Long,
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        val seenUrls = LinkedHashSet<String>()

        try {
            randomDelay()
            SearchHttp.post(
                "$MOREKNIG_BASE/index.php?do=search",
                mapOf(
                    "do" to "search", "subaction" to "search", "story" to query,
                    "search_start" to "0", "result_from" to "1", "full_search" to "0",
                ),
                MOREKNIG_BASE,
                searchId,
            )
        } catch (_: Exception) { }

        for (page in 1..maxPages) {
            if (cancelled()) break
            if (count >= limit) break

            val searchStart = if (page == 1) "0" else page.toString()
            val resultFrom = if (page == 1) "1" else ((page - 1) * 10 + 1).toString()

            try {
                randomDelay()
                val html = SearchHttp.post(
                    "$MOREKNIG_BASE/index.php?do=search",
                    mapOf(
                        "do" to "search", "subaction" to "search", "story" to query,
                        "search_start" to searchStart, "result_from" to resultFrom,
                        "full_search" to "0",
                    ),
                    MOREKNIG_BASE,
                    searchId,
                ) ?: break

                val doc = Jsoup.parse(html, MOREKNIG_BASE)

                // Python: content_div = soup.find('div', id='dle-content')
                //         if content_div is None: break
                val contentDiv = doc.getElementById("dle-content") ?: break

                val pageUrls = mutableListOf<String>()
                // Python: for a in content_div.find_all('a', class_='sres-wrap')
                for (a in contentDiv.select("a.sres-wrap[href]")) {
                    val full = abs(MOREKNIG_BASE, a.attr("href")).substringBefore('?')
                    if (seenUrls.add(full)) pageUrls += full
                }

                if (pageUrls.isEmpty()) break

                for (bookUrl in pageUrls) {
                    if (cancelled()) break
                    if (count >= limit) break
                    try {
                        randomDelay()
                        val book = parseMoreKnigBook(bookUrl, searchId) ?: continue
                        if (book.title.isNotBlank()) {
                            onBook(book)
                            count++
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) {
                break
            }
        }
        count
    }

    private fun parseMoreKnigBook(url: String, searchId: Long): BookSearchResult? {
        randomDelay()
        val html = SearchHttp.get(url, MOREKNIG_BASE, searchId) ?: return null
        val doc = Jsoup.parse(html, url)

        var title = "Без названия"
        val h1 = doc.selectFirst("h1")
        if (h1 != null && h1.text().trim().isNotEmpty()) title = h1.text().trim()

        var cover: String? = null
        val fpos = doc.selectFirst("div.fpos")
        if (fpos != null) {
            val img = fpos.selectFirst("img")
            val src = img?.attr("src")?.takeIf { it.isNotBlank() }
            if (src != null && "uploads" in src) cover = abs(MOREKNIG_BASE, src)
        }
        if (cover == null) {
            val og = doc.selectFirst("meta[property=og:image]")
            val c = og?.attr("content")?.takeIf { it.isNotBlank() && "uploads" in it }
            if (c != null) cover = abs(MOREKNIG_BASE, c)
        }

        val authors = mutableListOf<String>()
        for (a in doc.select("a[href*=/knigi-filtr/autor/]")) {
            val name = a.text().trim()
            if (name.length in 2..50 && !name[0].isDigit()) {
                authors += name
                break
            }
        }
        if (authors.isEmpty()) {
            val meta = doc.selectFirst("meta[name=author]")
            val m = meta?.attr("content")?.trim()
            if (!m.isNullOrEmpty()) authors += m
        }

        var description = ""
        val fullText = doc.selectFirst("div.full-text")
        if (fullText != null) {
            val text = fullText.text().trim()
            if (text.length > 20) {
                description = if (text.length > 500) text.take(500) + "..." else text
            }
        }

        val downloads = mutableListOf<DownloadLink>()
        for (a in doc.select("a[href*=download.php]").take(5)) {
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: continue
            val full = abs(MOREKNIG_BASE, href)
            val text = a.text().lowercase()
            val fmt = when {
                "pdf" in text -> "pdf"
                "epub" in text -> "epub"
                "txt" in text -> "txt"
                "rtf" in text -> "rtf"
                "mobi" in text -> "mobi"
                else -> "fb2"
            }
            downloads += DownloadLink(url = full, format = fmt)
        }

        return BookSearchResult(
            title = title,
            authors = authors,
            url = url,
            source = SearchSource.MOREKNIG,
            coverUrl = cover,
            description = description.ifBlank { null },
            downloadLinks = downloads.ifEmpty { listOf(DownloadLink(url = url, format = "fb2")) },
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    // FLIBUSTA
    // ═════════════════════════════════════════════════════════════════════
    suspend fun searchFlibusta(
        query: String,
        limit: Int,
        maxPages: Int,
        baseOverride: String?,
        cancelled: () -> Boolean,
        onBook: (BookSearchResult) -> Unit,
        searchId: Long,
    ): Int = withContext(Dispatchers.IO) {
        val base = flibustaBase(baseOverride)
        var count = 0
        val seenUrls = LinkedHashSet<String>()

        val encoded = enc(query)
        val allBookUrls = mutableListOf<String>()
        val allSeriesUrls = mutableListOf<String>()
        val allAuthorUrls = mutableListOf<String>()
        val queryWords = query.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }

        for (page in 1..maxPages) {
            if (cancelled()) break
            try {
                randomDelay()
                var searchUrl = "$base/booksearch?ask=$encoded"
                if (page > 1) searchUrl += "&page=${page - 1}"
                val html = SearchHttp.get(searchUrl, base, searchId) ?: break
                val doc = Jsoup.parse(html, base)

                val seriesHeading = doc.select("h3").firstOrNull {
                    it.text().contains("Найденные серии")
                }
                if (seriesHeading != null) {
                    val seriesUl = nextSiblingOfTag(seriesHeading, "ul")
                    if (seriesUl != null) {
                        for (a in seriesUl.select("a[href]")) {
                            val href = a.attr("href")
                            if (Regex("^/(sequence|s)/\\d+$").matches(href)) {
                                val full = abs(base, href)
                                if (full !in allSeriesUrls) allSeriesUrls += full
                            }
                        }
                    }
                }

                val authorsHeading = doc.select("h3").firstOrNull {
                    it.text().contains("Найденные писатели")
                }
                if (authorsHeading != null) {
                    val authorsUl = nextSiblingOfTag(authorsHeading, "ul")
                    if (authorsUl != null) {
                        for (a in authorsUl.select("a[href]")) {
                            val href = a.attr("href")
                            if (!Regex("^/a/\\d+$").matches(href)) continue
                            val authorName = a.text().trim().lowercase()
                            if (queryWords.isNotEmpty() && queryWords.none { it in authorName }) continue
                            val full = abs(base, href)
                            if (full !in allAuthorUrls) allAuthorUrls += full
                        }
                    }
                }

                if (seriesHeading == null && authorsHeading == null) {
                    val booksHeading = doc.select("h3").firstOrNull {
                        it.text().contains("Найденные книги")
                    }
                    if (booksHeading != null) {
                        val booksUl = nextSiblingOfTag(booksHeading, "ul")
                        if (booksUl != null) {
                            for (a in booksUl.select("a[href]")) {
                                val href = a.attr("href")
                                if (!Regex("^/b/\\d+$").matches(href)) continue
                                val full = abs(base, href)
                                if (full !in seenUrls && full !in allBookUrls) {
                                    allBookUrls += full
                                }
                            }
                        }
                    }
                }

                val hasNext = doc.select("a").any { a ->
                    val t = a.text()
                    t.contains("Следующая", ignoreCase = true) ||
                            t.contains("Next", ignoreCase = true) ||
                            t.contains("→")
                }
                if (!hasNext) break
            } catch (_: Exception) {
                break
            }
        }

        // Python порядок: авторы → серии → книги.
        if (!cancelled()) {
            for (authorUrl in allAuthorUrls) {
                if (cancelled() || count >= limit) break
                count += parseFlibustaListPage(base, authorUrl, limit, cancelled, searchId, seenUrls, onBook)
            }
        }
        if (count < limit && !cancelled()) {
            for (seriesUrl in allSeriesUrls) {
                if (cancelled() || count >= limit) break
                count += parseFlibustaListPage(base, seriesUrl, limit, cancelled, searchId, seenUrls, onBook)
            }
        }
        if (count < limit && !cancelled() && allBookUrls.isNotEmpty()) {
            for (url in allBookUrls) {
                if (cancelled() || count >= limit) break
                if (!seenUrls.add(url)) continue
                try {
                    randomDelay()
                    val book = parseFlibustaBook(base, url, searchId) ?: continue
                    if (book.title.isNotBlank() && book.title != "Без названия") {
                        onBook(book)
                        count++
                    }
                } catch (_: Exception) { }
            }
        }

        count
    }

    private fun nextSiblingOfTag(el: Element, tag: String): Element? {
        var sib = el.nextElementSibling()
        while (sib != null) {
            if (sib.tagName() == tag) return sib
            sib = sib.nextElementSibling()
        }
        return null
    }

    private fun parseFlibustaListPage(
        base: String,
        url: String,
        limit: Int,
        cancelled: () -> Boolean,
        searchId: Long,
        seenUrls: MutableSet<String>,
        onBook: (BookSearchResult) -> Unit,
    ): Int {
        var added = 0
        try {
            randomDelay()
            val html = SearchHttp.get(url, base, searchId) ?: return 0
            val doc = Jsoup.parse(html, url)
            val mainDiv = doc.getElementById("main") ?: return 0

            val bookLinks = mutableListOf<String>()
            for (a in mainDiv.select("a[href]")) {
                val href = a.attr("href")
                if (!Regex("^/b/\\d+$").matches(href)) continue
                val full = abs(base, href)
                if (full !in bookLinks) bookLinks += full
            }

            for (bookUrl in bookLinks) {
                if (cancelled() || added >= limit) break
                if (!seenUrls.add(bookUrl)) continue
                try {
                    randomDelay()
                    val book = parseFlibustaBook(base, bookUrl, searchId) ?: continue
                    if (book.title.isNotBlank() && book.title != "Без названия") {
                        onBook(book)
                        added++
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }
        return added
    }

    private fun parseFlibustaBook(base: String, url: String, searchId: Long): BookSearchResult? {
        randomDelay()
        val html = SearchHttp.get(url, base, searchId) ?: return null
        val doc = Jsoup.parse(html, url)

        val bookId = Regex("/b/(\\d+)").find(url)?.groupValues?.get(1)

        var title = "Без названия"
        var authorsFromTitle: List<String> = emptyList()
        val titleTag = doc.selectFirst("title")
        if (titleTag != null) {
            var fullTitle = titleTag.text().trim()
                .replace(Regex("\\s*\\((?:fb2|epub|mobi)\\)\\s*", RegexOption.IGNORE_CASE), " ")
            if ("Флибуста" in fullTitle) fullTitle = fullTitle.replace("Флибуста", "").trim()
            fullTitle = fullTitle.replace(Regex("\\s*\\|\\s*$"), "").trim()
            fullTitle = fullTitle.replace(Regex("\\s{2,}"), " ").trim()
            if (" - " in fullTitle) {
                val idx = fullTitle.lastIndexOf(" - ")
                title = fullTitle.substring(0, idx).trim()
                authorsFromTitle = listOf(fullTitle.substring(idx + 3).trim())
            } else {
                title = fullTitle
            }
        }

        val authors = mutableListOf<String>()
        val h1 = doc.selectFirst("h1.title")
        if (h1 != null) {
            var sib: Element? = h1.nextElementSibling()
            while (sib != null) {
                if (sib.tagName() == "div") break
                if (sib.tagName() == "a" && Regex("^/a/\\d+$").matches(sib.attr("href"))) {
                    val name = sib.text().trim()
                    if (name.isNotEmpty() && name !in authors) authors += name
                }
                sib = sib.nextElementSibling()
            }
        }
        if (authors.isEmpty()) authors.addAll(authorsFromTitle)

        var cover: String? = null
        for (img in doc.select("img[src]")) {
            val src = img.attr("src").trim()
            if (src.startsWith("/i/") || src.startsWith("/ib/")) {
                cover = abs(base, src); break
            }
            if (bookId != null && ("/$bookId/" in src || "/$bookId." in src)) {
                cover = abs(base, src); break
            }
        }

        var description = ""
        val annTag = doc.select("div, h1, h2, h3, h4, b, strong, p").firstOrNull {
            it.text().trim() == "Аннотация"
        }
        if (annTag != null) {
            val parts = mutableListOf<String>()
            var sib: Element? = annTag.nextElementSibling()
            while (sib != null) {
                val text = sib.text().trim()
                if (text.isEmpty()) { sib = sib.nextElementSibling(); continue }
                if (sib.tagName() in listOf("h1", "h2", "h3", "form", "table", "hr")) break
                if (text.startsWith("Рекомендации")) break
                if (text == "Читать онлайн" || text == "Похожие книги") break
                parts += text
                if (parts.sumOf { it.length } > 500) break
                sib = sib.nextElementSibling()
            }
            val joined = parts.joinToString(" ").trim()
            if (joined.length > 20) {
                description = if (joined.length > 500) joined.take(500) + "..." else joined
            }
        }

        val downloads = if (bookId != null) listOf(
            DownloadLink(url = "$base/b/$bookId/fb2", format = "fb2", isZip = true),
            DownloadLink(url = "$base/b/$bookId/epub", format = "epub", isZip = false),
            DownloadLink(url = "$base/b/$bookId/mobi", format = "mobi", isZip = false),
        ) else emptyList()

        return BookSearchResult(
            title = title.ifBlank { "Без названия" },
            authors = authors,
            url = url,
            source = SearchSource.FLIBUSTA,
            coverUrl = cover,
            description = description.ifBlank { null },
            bookId = bookId,
            downloadLinks = downloads,
        )
    }
}