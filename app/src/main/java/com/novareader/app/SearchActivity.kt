package com.novareader.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.novareader.app.search.BookDownloader
import com.novareader.app.search.BookSearchResult
import com.novareader.app.search.DownloadLink
import com.novareader.app.search.SearchHttp
import com.novareader.app.search.SearchRepository
import com.novareader.app.search.SearchSource
import com.novareader.app.search.SiteParsers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class SearchActivity : AppCompatActivity() {

    private lateinit var queryEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var btnSearch: TextView
    private lateinit var btnStop: TextView
    private lateinit var resultsList: RecyclerView
    private lateinit var chkAuthor: CheckBox
    private lateinit var chkSearchFloor: CheckBox
    private lateinit var chkMoreKnig: CheckBox
    private lateinit var chkFlibusta: CheckBox

    private var themeBg = 0xFF1C1C1E.toInt()
    private var themeCard = 0xFF2C2C2E.toInt()
    private var themeAccent = 0xFF5A4FCF.toInt()
    private var themeText = 0xFFF0F0F2.toInt()
    private var themeSub = 0xFFA8A8AE.toInt()

    private val coverExecutor = Executors.newFixedThreadPool(4)

    private val adapter by lazy {
        SearchResultsAdapter(
            accent = { themeAccent },
            cardColor = { themeCard },
            textColor = { themeText },
            subColor = { themeSub },
            onClick = { book -> showBookActions(book) },
            onRead = { book -> openInBrowser(book.url) },
            onDownload = { book -> startDownloadFlow(book) },
            onNeedCover = { book, position -> enrichCover(book, position) },
            loadCoverBytes = { url, referer -> SearchHttp.getBytes(url, referer) },
            executor = coverExecutor,
        )
    }

    private var searchJob: Job? = null
    private val cancelled = AtomicBoolean(false)
    // У каждого запуска поиска свой generation. Это не даёт отменённому
    // старому Job показать ошибку уже поверх нового/остановленного поиска.
    private var searchGeneration = 0L
    private val seenUrls = mutableSetOf<String>()
    private val searchAuxJobs = mutableSetOf<Job>()

    private var pendingDownload: Pair<BookSearchResult, DownloadLink>? = null

    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val pending = pendingDownload
        pendingDownload = null
        if (uri == null || pending == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        SettingsManager(this).setDownloadFolderUri(uri.toString())
        startDownload(pending.first, pending.second, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        queryEdit = findViewById(R.id.searchQueryEdit)
        statusText = findViewById(R.id.searchStatus)
        btnSearch = findViewById(R.id.btnDoSearch)
        btnStop = findViewById(R.id.btnStopSearch)
        resultsList = findViewById(R.id.searchResultsList)
        chkAuthor = findViewById(R.id.chkAuthorToday)
        chkSearchFloor = findViewById(R.id.chkSearchFloor)
        chkMoreKnig = findViewById(R.id.chkMoreKnig)
        chkFlibusta = findViewById(R.id.chkFlibusta)

        resultsList.layoutManager = LinearLayoutManager(this)
        resultsList.adapter = adapter

        findViewById<View>(R.id.btnSearchBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSearchHelp).setOnClickListener { showHelp() }
        btnSearch.setOnClickListener { startSearch() }
        btnStop.setOnClickListener { stopSearch() }

        queryEdit.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                // Поиск с экранной клавиатуры: сначала гарантированно закрываем IME,
                // затем снимаем фокус и запускаем поиск.
                hideSearchKeyboard(view)
                startSearch()
                true
            } else false
        }

        applyThemeColors()
        ThemeUtils.applyInputCursors(findViewById(android.R.id.content), this)
    }

    private fun parseColorSafe(value: String?, fallback: Int): Int =
        try {
            if (value.isNullOrBlank()) fallback else Color.parseColor(value)
        } catch (_: Exception) {
            fallback
        }

    private fun applyThemeColors() {
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        themeBg = parseColorSafe(prefs.getString("library_bg", "#1C1C1E"), 0xFF1C1C1E.toInt())
        themeCard = parseColorSafe(prefs.getString("library_card", "#2C2C2E"), 0xFF2C2C2E.toInt())
        themeAccent = parseColorSafe(prefs.getString("library_accent", "#5A4FCF"), 0xFF5A4FCF.toInt())
        themeText = 0xFFF0F0F2.toInt()
        themeSub = 0xFFA8A8AE.toInt()

        findViewById<View>(R.id.searchRoot)?.setBackgroundColor(themeBg)
        findViewById<View>(R.id.searchHeaderBar)?.setBackgroundColor(themeCard)
        statusText.setBackgroundColor(themeCard)
        statusText.setTextColor(themeSub)
        btnSearch.setTextColor(themeAccent)
        btnStop.setTextColor(0xFFE74C3C.toInt())
        findViewById<TextView>(R.id.btnSearchBack)?.setTextColor(themeAccent)
        findViewById<TextView>(R.id.btnSearchHelp)?.setTextColor(themeAccent)
        findViewById<TextView>(R.id.searchTitleLabel)?.setTextColor(themeText)

        val editBg = GradientDrawable().apply {
            setColor(themeCard)
            cornerRadius = 22f * resources.displayMetrics.density
        }
        queryEdit.background = editBg
        queryEdit.setTextColor(themeText)
        queryEdit.setHintTextColor(themeSub)

        listOf(chkAuthor, chkSearchFloor, chkMoreKnig, chkFlibusta).forEach { cb ->
            cb.setTextColor(themeText)
            try {
                cb.buttonTintList = android.content.res.ColorStateList.valueOf(themeAccent)
            } catch (_: Exception) {}
        }
    }

    private fun selectedSources(): Set<SearchSource> {
        val set = mutableSetOf<SearchSource>()
        if (chkAuthor.isChecked) set += SearchSource.AUTHOR_TODAY
        if (chkSearchFloor.isChecked) set += SearchSource.SEARCHFLOOR
        if (chkMoreKnig.isChecked) set += SearchSource.MOREKNIG
        if (chkFlibusta.isChecked) set += SearchSource.FLIBUSTA
        return set
    }

    private fun hideSearchKeyboard(view: View = queryEdit) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
        view.clearFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
    }

    private fun startSearch() {
        // Дополнительная страховка: поиск, запущенный кнопкой приложения,
        // также не должен оставлять экранную клавиатуру открытой.
        hideSearchKeyboard()
        val query = queryEdit.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            statusText.text = "Введите запрос"
            return
        }
        val sources = selectedSources()
        if (sources.isEmpty()) {
            statusText.text = "Выберите хотя бы один источник"
            return
        }

        stopSearch(silent = true)
        // stopSearch() уже инвалидировал предыдущий запуск. Этот generation
        // принадлежит только текущему поиску.
        val generation = searchGeneration
        cancelled.set(false)
        seenUrls.clear()
        adapter.clear()
        setSearching(true)
        statusText.text = "Поиск: $query…"

        val settings = SettingsManager(this)
        val repo = SearchRepository(
            searchFloorDomain = settings.getBookSearchSearchFloorDomain(),
            flibustaDomain = settings.getBookSearchFlibustaDomain(),
        )
        val limit = settings.bookSearchLimit
        val maxPages = settings.bookSearchMaxPages
        val sfDomain = settings.getBookSearchSearchFloorDomain()

        searchJob = lifecycleScope.launch {
            try {
                repo.search(
                    query = query,
                    sources = sources,
                    limit = limit,
                    maxPages = maxPages,
                    cancelled = { cancelled.get() || generation != searchGeneration },
                    onBook = { book ->
                        if (cancelled.get() || generation != searchGeneration) return@search
                        synchronized(seenUrls) {
                            if (!seenUrls.add(book.url)) return@search
                        }
                        if (book.source == SearchSource.SEARCHFLOOR &&
                            book.description.isNullOrBlank() &&
                            !book.bookId.isNullOrBlank()
                        ) {
                            val bookId = book.bookId!!
                            val bookUrl = book.url
                            val auxJob = lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val ann = SiteParsers.fetchSearchFloorAnnotation(sfDomain, bookId)
                                    if (ann.isNotBlank() && !cancelled.get()) {
                                        runOnUiThread { adapter.updateDescription(bookUrl, ann) }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Аннотация — не критичная подгрузка, просто оставляем карточку без описания
                                }
                            }
                            synchronized(searchAuxJobs) { searchAuxJobs.add(auxJob) }
                        }
                        runOnUiThread {
                            if (!cancelled.get() && generation == searchGeneration) {
                                adapter.add(book)
                                statusText.text = "Найдено: ${adapter.itemCount}…"
                            }
                        }
                    },
                )
                if (!cancelled.get() && generation == searchGeneration) {
                    statusText.text = if (adapter.itemCount == 0) {
                        "Книги не найдены. Попробуйте изменить запрос."
                    } else {
                        "✅ Найдено ${adapter.itemCount} книг"
                    }
                }
            } catch (e: CancellationException) {
                // Отмена coroutine — штатное состояние. НИКОГДА не выводим
                // текст самой CancellationException (например,
                // "StandaloneCoroutine was cancelled") в статус поиска.
                if (generation == searchGeneration && !cancelled.get()) {
                    statusText.text = "⏹ Поиск остановлен. Найдено ${adapter.itemCount}"
                }
            } catch (e: Exception) {
                // Некоторые библиотеки могут завернуть отмену в другое
                // исключение. Для UI это всё равно не ошибка поиска.
                val cancellationLike =
                    e is CancellationException ||
                    e.cause is CancellationException ||
                    e.message?.contains("cancelled", ignoreCase = true) == true ||
                    e.message?.contains("canceled", ignoreCase = true) == true
                if (!cancellationLike && !cancelled.get() && generation == searchGeneration) {
                    statusText.text = "Ошибка поиска: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                // Старый Job не имеет права переключать UI нового поиска
                // обратно в состояние "не ищем".
                if (generation == searchGeneration) {
                    setSearching(false)
                }
            }
        }
    }

    private fun stopSearch(silent: Boolean = false) {
        // Сначала меняем generation, чтобы старый coroutine больше не мог
        // обновить статус после нажатия «Стоп» или старта нового поиска.
        searchGeneration++
        cancelled.set(true)
        // Отменяем не только coroutine Job, но и все активные OkHttp Call,
        // иначе блокирующий execute() может продолжать работать в фоне.
        SearchHttp.cancelAllSearches()
        searchJob?.cancel()
        searchJob = null
        synchronized(searchAuxJobs) {
            searchAuxJobs.forEach { it.cancel() }
            searchAuxJobs.clear()
        }
        setSearching(false)
        if (!silent) {
            statusText.text = "⏹ Поиск остановлен. Найдено ${adapter.itemCount}"
        }
    }

    private fun setSearching(active: Boolean) {
        btnSearch.isEnabled = !active
        btnSearch.alpha = if (active) 0.4f else 1f
        btnStop.isEnabled = active
        btnStop.alpha = if (active) 1f else 0.4f
        chkAuthor.isEnabled = !active
        chkSearchFloor.isEnabled = !active
        chkMoreKnig.isEnabled = !active
        chkFlibusta.isEnabled = !active
    }

    private fun showBookActions(book: BookSearchResult) {
        val items = mutableListOf<String>()
        items += "Открыть на сайте"
        if (book.source != SearchSource.AUTHOR_TODAY) {
            items += "Скачать"
            items += "Сменить папку загрузки"
        }
        items += "Скопировать ссылку"

        val msg = buildString {
            if (book.authors.isNotEmpty()) append(book.authors.joinToString(", ")).append('\n')
            append(book.source.displayName)
            if (book.downloadLinks.isNotEmpty()) {
                append("\nФорматы: ")
                append(book.downloadLinks.joinToString(", ") { it.format.uppercase() })
            }
            if (!book.description.isNullOrBlank()) {
                append("\n\n")
                append(book.description)
            }
            val folder = SettingsManager(this@SearchActivity).getDownloadFolderUri()
            if (folder.isNotBlank() && book.source != SearchSource.AUTHOR_TODAY) {
                append("\n\nПапка загрузки: ")
                append(folderNameFromUri(folder))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(book.title)
            .setMessage(msg)
            .setItems(items.toTypedArray()) { _, which ->
                when (items[which]) {
                    "Открыть на сайте" -> openInBrowser(book.url)
                    "Скачать" -> startDownloadFlow(book)
                    "Сменить папку загрузки" -> changeDownloadFolder()
                    "Скопировать ссылку" -> {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("book", book.url))
                        Toast.makeText(this, "Ссылка скопирована", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun changeDownloadFolder() {
        SettingsManager(this).clearDownloadFolderUri()
        Toast.makeText(
            this,
            "Папка сброшена. При следующем скачивании выберите новую.",
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * "Скачать" — как на десктопе _download_from_*:
     *  - Author.Today: только чтение, скачивания нет
     *  - SearchFloor: одна ссылка fb2 (zip внутри), качаем сразу
     *  - Flibusta: диалог выбора формата fb2/epub/mobi
     *  - MoreKnig: сперва резолвим ссылки download.php со страницы,
     *    потом либо сразу качаем одну, либо диалог выбора формата
     */
    private fun startDownloadFlow(book: BookSearchResult) {
        when (book.source) {
            SearchSource.AUTHOR_TODAY ->
                Toast.makeText(this, "Author.Today — только чтение онлайн", Toast.LENGTH_SHORT).show()
            SearchSource.SEARCHFLOOR -> {
                val link = book.downloadLinks.firstOrNull()
                    ?: DownloadLink(url = book.url, format = "fb2", isZip = true)
                ensureFolderAndDownload(book, link)
            }
            SearchSource.FLIBUSTA -> {
                val base = Regex("^(https?://[^/]+)").find(book.url)?.groupValues?.get(1)
                    ?: "https://flibusta.is"
                val id = book.bookId ?: Regex("/b/(\\d+)").find(book.url)?.groupValues?.get(1)
                val links = book.downloadLinks.ifEmpty {
                    if (id == null) emptyList()
                    else listOf(
                        DownloadLink("$base/b/$id/fb2", "fb2", isZip = true),
                        DownloadLink("$base/b/$id/epub", "epub"),
                        DownloadLink("$base/b/$id/mobi", "mobi"),
                    )
                }
                if (links.isEmpty()) {
                    Toast.makeText(this, "Нет ссылок для скачивания", Toast.LENGTH_SHORT).show()
                    return
                }
                // Всегда диалог выбора формата, как на десктопе.
                AlertDialog.Builder(this)
                    .setTitle("Формат")
                    .setItems(links.map { "Скачать ${it.format.uppercase()}" }.toTypedArray()) { _, idx ->
                        ensureFolderAndDownload(book, links[idx])
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            SearchSource.MOREKNIG -> {
                statusText.text = "Поиск ссылок для скачивания…"
                lifecycleScope.launch {
                    val links = withContext(Dispatchers.IO) {
                        BookDownloader.resolveMoreKnigLinks(book.url).ifEmpty { book.downloadLinks }
                    }
                    if (links.isEmpty()) {
                        statusText.text = "Нет доступных форматов"
                        Toast.makeText(this@SearchActivity, "Нет ссылок для скачивания", Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    if (links.size == 1) {
                        ensureFolderAndDownload(book, links[0])
                    } else {
                        AlertDialog.Builder(this@SearchActivity)
                            .setTitle("Формат")
                            .setItems(links.map { "Скачать ${it.format.uppercase()}" }.toTypedArray()) { _, idx ->
                                ensureFolderAndDownload(book, links[idx])
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                    }
                }
            }
        }
    }

    private fun ensureFolderAndDownload(book: BookSearchResult, link: DownloadLink) {
        val settings = SettingsManager(this)
        val savedUri = settings.getDownloadFolderUri()
        if (savedUri.isNotBlank()) {
            val uri = Uri.parse(savedUri)
            if (canWriteToFolder(uri)) {
                startDownload(book, link, uri)
                return
            }
            settings.clearDownloadFolderUri()
        }
        pendingDownload = book to link
        try {
            pickFolderLauncher.launch(null)
        } catch (e: Exception) {
            pendingDownload = null
            Toast.makeText(
                this,
                "Не удалось открыть выбор папки: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun canWriteToFolder(uri: Uri): Boolean {
        return try {
            val doc = DocumentFile.fromTreeUri(this, uri)
            doc != null && doc.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    private fun startDownload(book: BookSearchResult, link: DownloadLink, folderUri: Uri) {
        val progress = ProgressBar(this).apply { isIndeterminate = true }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Скачивание")
            .setMessage("⬇ ${book.title}\n${link.format.uppercase()}…")
            .setView(progress)
            .setCancelable(false)
            .create()
        dialog.show()
        statusText.text = "⬇ Скачивание ${link.format.uppercase()}…"

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                BookDownloader.downloadToFolder(this@SearchActivity, book, link, folderUri) { percent ->
                    runOnUiThread {
                        if (progress.isIndeterminate && percent > 0) {
                            progress.isIndeterminate = false
                            progress.max = 100
                        }
                        if (!progress.isIndeterminate) progress.progress = percent
                        statusText.text = "⬇ Скачивание… $percent%"
                    }
                }
            }
            dialog.dismiss()
            if (result.success) {
                statusText.text = "✅ ${result.message}"
                Toast.makeText(this@SearchActivity, result.message, Toast.LENGTH_LONG).show()
            } else {
                statusText.text = "Ошибка: ${result.message}"
                Toast.makeText(this@SearchActivity, result.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun folderNameFromUri(uriString: String): String {
        return try {
            val uri = Uri.parse(uriString)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val parts = docId.split(":", limit = 2)
            parts.getOrNull(1)?.substringAfterLast('/') ?: parts.getOrNull(1) ?: uriString
        } catch (_: Exception) {
            uriString
        }
    }

    private fun enrichCover(book: BookSearchResult, position: Int) {
        if (!book.coverUrl.isNullOrBlank()) return
        lifecycleScope.launch {
            val cover = withContext(Dispatchers.IO) {
                BookDownloader.fetchCoverFromBookPage(book)
            } ?: return@launch
            adapter.updateCover(position, cover)
        }
    }

    private fun openInBrowser(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("Поиск книг")
            .setMessage(
                "• Author.Today — только чтение на сайте\n" +
                        "• Цокольный этаж — обложки /cover/{id}; скачивание может требовать вход на сайт\n" +
                        "• MoreKnig / Флибуста — скачивание в библиотеку (fb2/epub/mobi)\n\n" +
                        "Скачанные файлы сохраняются в папку, которую вы выбрали при первом скачивании. " +
                        "Чтобы сменить папку — нажмите на карточку книги → «Сменить папку загрузки».\n\n" +
                        "Соблюдайте авторские права. NovaReader не распространяет книги."
            )
            .setPositiveButton("Понятно", null)
            .show()
    }

    override fun onDestroy() {
        stopSearch(silent = true)
        coverExecutor.shutdownNow()
        super.onDestroy()
    }

    private class SearchResultsAdapter(
        private val accent: () -> Int,
        private val cardColor: () -> Int,
        private val textColor: () -> Int,
        private val subColor: () -> Int,
        private val onClick: (BookSearchResult) -> Unit,
        private val onRead: (BookSearchResult) -> Unit,
        private val onDownload: (BookSearchResult) -> Unit,
        private val onNeedCover: (BookSearchResult, Int) -> Unit,
        private val loadCoverBytes: (String, String?) -> ByteArray?,
        private val executor: java.util.concurrent.ExecutorService,
    ) : RecyclerView.Adapter<SearchResultsAdapter.VH>() {

        private val items = mutableListOf<BookSearchResult>()

        fun clear() { items.clear(); notifyDataSetChanged() }

        fun add(book: BookSearchResult) {
            items.add(book)
            notifyItemInserted(items.size - 1)
        }

        fun updateCover(position: Int, coverUrl: String) {
            if (position !in items.indices) return
            items[position] = items[position].copy(coverUrl = coverUrl)
            notifyItemChanged(position)
        }

        fun updateDescription(url: String, description: String) {
            val idx = items.indexOfFirst { it.url == url }
            if (idx < 0) return
            val old = items[idx]
            if (!old.description.isNullOrBlank()) return
            items[idx] = old.copy(description = description)
            notifyItemChanged(idx)
        }

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_search_book, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val book = items[position]
            val bg = GradientDrawable().apply {
                setColor(cardColor())
                cornerRadius = 12f * holder.itemView.resources.displayMetrics.density
            }
            holder.itemView.background = bg
            holder.title.setTextColor(textColor())
            holder.author.setTextColor(subColor())
            holder.source.setTextColor(accent())

            holder.title.text = book.title
            holder.author.text = book.authors.joinToString(", ").ifBlank { "—" }
            val fmt = book.downloadLinks.joinToString("/") { it.format.uppercase() }
            holder.source.text = buildString {
                append(book.source.displayName)
                when {
                    fmt.isNotBlank() -> append(" · ").append(fmt)
                    book.source == SearchSource.AUTHOR_TODAY -> append(" · читать")
                    else -> append(" · скачать")
                }
            }

            // ── Кнопки "Читать" / "Скачать" — как в desktop-версии ──
            val accentColor = accent()
            holder.readButton.text = "Читать"
            holder.readButton.background = rounded(accentColor, 8f, holder.itemView)
            holder.readButton.setOnClickListener { onRead(book) }

            // Author.Today — только чтение, кнопки скачивания нет.
            if (book.source == SearchSource.AUTHOR_TODAY) {
                holder.downloadButton.visibility = View.GONE
            } else {
                holder.downloadButton.visibility = View.VISIBLE
                holder.downloadButton.text = "Скачать"
                holder.downloadButton.background = rounded(0xFFE67E22.toInt(), 8f, holder.itemView)
                holder.downloadButton.setOnClickListener { onDownload(book) }
            }

            holder.cover.setImageResource(R.drawable.cover_placeholder_bg)
            val coverUrl = book.coverUrl
            holder.cover.tag = coverUrl ?: ""
            if (!coverUrl.isNullOrBlank()) {
                val referer = Regex("^(https?://[^/]+)").find(book.url)?.groupValues?.get(1)
                executor.execute {
                    try {
                        val bytes = loadCoverBytes(coverUrl, referer)
                        if (bytes != null && holder.cover.tag == coverUrl) {
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            holder.cover.post {
                                if (holder.cover.tag == coverUrl && bmp != null) {
                                    holder.cover.setImageBitmap(bmp)
                                }
                            }
                        }
                    } catch (_: Exception) { }
                }
            } else {
                onNeedCover(book, position)
            }
            holder.itemView.setOnClickListener { onClick(book) }
        }

        private fun rounded(color: Int, radiusDp: Float, view: View): GradientDrawable =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radiusDp * view.resources.displayMetrics.density
                setColor(color)
            }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.searchBookTitle)
            val author: TextView = view.findViewById(R.id.searchBookAuthor)
            val source: TextView = view.findViewById(R.id.searchBookSource)
            val cover: ImageView = view.findViewById(R.id.searchBookCover)
            val readButton: TextView = view.findViewById(R.id.searchBookReadButton)
            val downloadButton: TextView = view.findViewById(R.id.searchBookDownloadButton)
        }
    }
}