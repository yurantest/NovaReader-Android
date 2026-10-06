package com.novareader.app.search

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call

object SearchHttp {
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"


    // Отдельные search-сессии позволяют кнопке «Стоп» реально отменять
    // уже выполняющиеся OkHttp-запросы, а не только coroutine Job.
    // Обычные обложки/скачивания в эти сессии не попадают.
    private val nextSearchId = AtomicLong(1)
    private val searchCalls = ConcurrentHashMap<Long, MutableSet<Call>>()

    fun beginSearch(): Long {
        val id = nextSearchId.getAndIncrement()
        searchCalls[id] = ConcurrentHashMap.newKeySet()
        return id
    }

    fun cancelSearch(id: Long) {
        searchCalls.remove(id)?.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
    }

    fun cancelAllSearches() {
        searchCalls.keys.toList().forEach { cancelSearch(it) }
    }

    fun endSearch(id: Long) {
        searchCalls.remove(id)?.forEach { call ->
            try { call.cancel() } catch (_: Exception) {}
        }
    }

    private inline fun <T> withSearchCall(searchId: Long?, call: Call, block: () -> T): T {
        if (searchId != null) {
            val calls = searchCalls[searchId]
            if (calls == null) {
                // Сессия уже остановлена между созданием Call и его регистрацией.
                call.cancel()
            } else {
                calls.add(call)
                // Стоп мог прийти ровно между get() и add(). Повторная проверка
                // гарантирует, что такой запрос тоже не уйдёт в сеть.
                if (searchCalls[searchId] !== calls) {
                    try { call.cancel() } catch (_: Exception) {}
                }
            }
        }
        try {
            return block()
        } finally {
            if (searchId != null) searchCalls[searchId]?.remove(call)
        }
    }
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun defaultHeaders(referer: String? = null): Request.Builder {
        val b = Request.Builder()
            .header("User-Agent", UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            )
            .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
        if (!referer.isNullOrBlank()) b.header("Referer", referer)
        return b
    }

    fun get(url: String, referer: String? = null, searchId: Long? = null): String? {
        return try {
            val call = client.newCall(defaultHeaders(referer).url(url).get().build())
            withSearchCall(searchId, call) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getBytes(url: String, referer: String? = null): ByteArray? {
        return try {
            client.newCall(defaultHeaders(referer).url(url).get().build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.bytes()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun post(url: String, form: Map<String, String>, referer: String? = null, searchId: Long? = null): String? {
        val bodyBuilder = FormBody.Builder()
        form.forEach { (k, v) -> bodyBuilder.add(k, v) }
        return try {
            val call = client.newCall(
                defaultHeaders(referer).url(url).post(bodyBuilder.build()).build()
            )
            withSearchCall(searchId, call) {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) null else resp.body?.string()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    // ── Скачивание ───────────────────────────────────────────────────────

    data class RawDownload(
        val code: Int,
        val finalUrl: String,
        val contentType: String?,
        val bytes: ByteArray?,
        val error: String? = null,
    )

    /**
     * Полный GET с сохранением тела в память. Для скачивания книг —
     * достаточно: файлы книг обычно до нескольких десятков МБ.
     * Прогресс отдаётся через onProgress (0..100), если сервер сообщает
     * Content-Length; иначе onProgress не вызывается.
     */
    fun downloadRaw(
        url: String,
        referer: String? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): RawDownload {
        return try {
            val req = defaultHeaders(referer)
                .header("Accept", "*/*")
                .url(url)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body
                if (!resp.isSuccessful || body == null) {
                    return RawDownload(
                        code = resp.code,
                        finalUrl = resp.request.url.toString(),
                        contentType = resp.header("Content-Type"),
                        bytes = null,
                        error = "HTTP ${resp.code}",
                    )
                }
                val total = body.contentLength()
                val out = ByteArrayOutputStream()
                body.byteStream().use { input ->
                    val buf = ByteArray(32 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && onProgress != null) {
                            onProgress(((read * 100) / total).toInt().coerceIn(0, 100))
                        }
                    }
                }
                RawDownload(
                    code = resp.code,
                    finalUrl = resp.request.url.toString(),
                    contentType = resp.header("Content-Type"),
                    bytes = out.toByteArray(),
                )
            }
        } catch (e: Exception) {
            RawDownload(0, url, null, null, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Совместимость со старым именем — то же, что downloadRaw. */
    fun download(url: String, referer: String? = null, onProgress: ((Int) -> Unit)? = null): RawDownload =
        downloadRaw(url, referer, onProgress)
}