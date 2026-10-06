package com.novareader.app

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.ColorStateList
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.text.Editable
import android.text.TextWatcher
import android.widget.PopupMenu
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean

/** Нативная библиотека Android: список книг, свой файловый менеджер, сканирование. */
class LibraryActivity : AppCompatActivity() {

    private val materialIcons: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.material_icons_regular) ?: Typeface.DEFAULT
    }

    private lateinit var library: LibraryManager
    private lateinit var adapter: BooksAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var titleBar: View
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionCountText: TextView
    private lateinit var searchBooksEdit: EditText
    private lateinit var formatFilters: LinearLayout
    private lateinit var sortBooksButton: TextView

    private var allBooks = listOf<BookItem>()
    private var selectedFormat = "ALL"
    private var sortMode = SortMode.LAST_OPENED

    private enum class SortMode {
        LAST_OPENED, TITLE, AUTHOR, PROGRESS
    }

    private val selectedIds = mutableSetOf<String>()
    private var selectionMode = false
    private val dialogStack = ArrayDeque<AlertDialog>()
    private var scanInProgress = false

    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            applyLibraryTheme()
            loadBooks()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)
        ThemeUtils.applyInputCursors(findViewById(android.R.id.content), this)

        library = LibraryManager(this)
        recyclerView = findViewById(R.id.booksRecyclerView)
        emptyText = findViewById(R.id.emptyLibraryText)
        titleBar = findViewById(R.id.titleBar)
        selectionBar = findViewById(R.id.selectionBar)
        selectionCountText = findViewById(R.id.selectionCountText)
        searchBooksEdit = findViewById(R.id.searchBooksEdit)
        formatFilters = findViewById(R.id.formatFilters)
        sortBooksButton = findViewById(R.id.sortBooksButton)

        setupGrid()
        setupLibraryControls()
        requestFileAccessIfNeeded()

        findViewById<View>(R.id.btnLibrarySettings).setOnClickListener {
            settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
        }
        findViewById<View>(R.id.btnLibrarySearch).setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        findViewById<View>(R.id.btnLibraryBackup).setOnClickListener {
            showBackupStorageRoots(saveMode = true)
        }
        findViewById<View>(R.id.btnLibraryRestore).setOnClickListener {
            showBackupStorageRoots(saveMode = false)
        }
        findViewById<View>(R.id.btnCancelSelection).setOnClickListener { exitSelectionMode() }
        findViewById<View>(R.id.btnDeleteSelected).setOnClickListener { confirmDeleteSelected() }

        adapter = BooksAdapter(
            isSelectionMode = { selectionMode },
            isSelected = { id -> selectedIds.contains(id) },
            onAdd = { showStorageRoots() },
            onOpen = { book -> if (selectionMode) toggleSelection(book.id) else openBook(book) },
            onLongPress = { book -> if (!selectionMode) enterSelectionMode(book.id) else toggleSelection(book.id) },
        )
        recyclerView.adapter = adapter
        applyLibraryTheme()
        updateSearchButtonVisibility()
        loadBooks()
    }

    override fun onDestroy() {
        closeAllDialogs()
        super.onDestroy()
    }

    private fun closeAllDialogs() {
        while (dialogStack.isNotEmpty()) {
            val d = dialogStack.removeLast()
            try { d.dismiss() } catch (_: Exception) {}
        }
    }

    private fun trackDialog(dialog: AlertDialog) {
        dialogStack.addLast(dialog)
        dialog.setOnDismissListener { dialogStack.remove(dialog) }
    }

    // ── Стеклянный фон для всех диалогов ──────────────────────────────
    private fun glassBackground(): GradientDrawable {
        val accent = libraryAccentColor()
        val card = libraryCardColor()
        val translucent = (card and 0x00FFFFFF) or (0xE6 shl 24)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(20).toFloat()
            setColor(translucent)
            setStroke(dp(1), (accent and 0x00FFFFFF) or (0x55 shl 24))
        }
    }

    private fun applyGlassToDialog(dialog: AlertDialog, root: View) {
        root.background = glassBackground()
        dialog.setView(root)
        dialog.setCanceledOnTouchOutside(false)
        trackDialog(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val margin = dp(16)
        val width = resources.displayMetrics.widthPixels - margin * 2
        dialog.window?.setLayout(width, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun setupLibraryControls() {
        populateFormatFilters()

        searchBooksEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyBookFilters()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        sortBooksButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this, anchor)
            popup.menu.add("Последние открытые")
            popup.menu.add("Название: А–Я")
            popup.menu.add("Автор: А–Я")
            popup.menu.add("Прогресс: от большего")
            popup.setOnMenuItemClickListener { item ->
                sortMode = when (item.title.toString()) {
                    "Название: А–Я" -> SortMode.TITLE
                    "Автор: А–Я" -> SortMode.AUTHOR
                    "Прогресс: от большего" -> SortMode.PROGRESS
                    else -> SortMode.LAST_OPENED
                }
                updateSortLabel()
                applyBookFilters()
                true
            }
            popup.show()
        }
        updateSortLabel()
    }

    private fun populateFormatFilters() {
        formatFilters.removeAllViews()
        val filters = listOf(
            "ALL" to "Все",
            "epub" to "EPUB",
            "fb2" to "FB2",
            "cbz" to "CBZ",
            "pdf" to "PDF",
            "mobi" to "MOBI",
            "azw3" to "AZW3"
        )
        filters.forEachIndexed { index, (key, label) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(dp(13), 0, dp(13), 0)
                isSingleLine = true
                isClickable = true
                setOnClickListener {
                    selectedFormat = key
                    populateFormatFilters()
                    applyBookFilters()
                }
            }
            val selected = key == selectedFormat
            chip.setTextColor(if (selected) android.graphics.Color.WHITE else android.graphics.Color.rgb(155, 157, 165))
            chip.background = filterChipBackground(selected)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(32))
            if (index > 0) lp.marginStart = dp(6)
            chip.layoutParams = lp
            formatFilters.addView(chip)
        }
    }

    private fun filterChipBackground(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        val accent = libraryAccentColor()
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(9).toFloat()
        setColor(if (selected) accent else 0x142C2C2E)
        setStroke(dp(1), if (selected) accent else 0x1FFFFFFF)
    }

    private fun updateSortLabel() {
        sortBooksButton.text = "Сортировка: ${when (sortMode) {
            SortMode.LAST_OPENED -> "последние открытые"
            SortMode.TITLE -> "название А–Я"
            SortMode.AUTHOR -> "автор А–Я"
            SortMode.PROGRESS -> "прогресс ↓"
        }}  ▾"
    }

    private fun requestFileAccessIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 4101)
        }
    }

    private fun setupGrid() {
        val orientation = resources.configuration.orientation
        val columns = if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 4 else 2
        recyclerView.layoutManager = GridLayoutManager(this, columns)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::recyclerView.isInitialized) setupGrid()
    }

    override fun onResume() {
        super.onResume()
        if (::library.isInitialized) {
            applyLibraryTheme()
            updateSearchButtonVisibility()
            loadBooks()
        }
    }

    @Deprecated("Deprecated in Android SDK; retained for the app's back behavior")
    override fun onBackPressed() {
        if (dialogStack.isNotEmpty()) {
            closeAllDialogs()
        } else if (selectionMode) {
            exitSelectionMode()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ── Резервное копирование ─────────────────────────────────────────

    private fun showBackupStorageRoots(saveMode: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestFileAccessIfNeeded()
            Toast.makeText(this, "Разрешите NovaReader доступ ко всем файлам и вернитесь в приложение", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = if (saveMode) "Сохранить резервную копию" else "Выбрать резервную копию"
            textSize = 20f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val close = TextView(this).apply {
            text = "\uE5CD"; typeface = materialIcons; textSize = 24f; setTextColor(libraryAccentColor()); gravity = android.view.Gravity.CENTER
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(title, LinearLayout.LayoutParams(0, dp(56)).apply { weight = 1f })
        head.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(head)
        root.addView(TextView(this).apply {
            text = if (saveMode) "Выберите папку, куда сохранить ZIP-архив." else "Выберите ZIP-архив резервной копии NovaReader."
            textSize = 13f; setTextColor(0xFF96969C.toInt()); setPadding(dp(8), 0, dp(8), dp(10))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        collectStorageRoots().forEach { (name, dir) ->
            list.addView(rowView(name, dir.absolutePath, isFolder = true) {
                dialog.dismiss(); showBackupBrowser(dir, saveMode)
            }, LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(8) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        applyGlassToDialog(dialog, root)
    }

    private fun showBackupBrowser(startDir: File, saveMode: Boolean) {
        if (!startDir.exists() || !startDir.isDirectory) return
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val isStorageRoot = collectStorageRoots().any { it.second.absolutePath == startDir.absolutePath }
        val canGoUp = startDir.parentFile != null && !isStorageRoot
        val back = TextView(this).apply {
            text = "\uE5C4"; typeface = materialIcons; textSize = 28f; setTextColor(libraryAccentColor()); gravity = android.view.Gravity.CENTER
            setOnClickListener { dialog.dismiss(); if (canGoUp) startDir.parentFile?.let { showBackupBrowser(it, saveMode) } else showBackupStorageRoots(saveMode) }
        }
        val title = TextView(this).apply {
            text = startDir.name.ifBlank { startDir.absolutePath }; textSize = 19f; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        head.addView(back, LinearLayout.LayoutParams(dp(48), dp(56)))
        head.addView(title, LinearLayout.LayoutParams(0, dp(56)).apply { weight = 1f })
        root.addView(head)
        root.addView(TextView(this).apply { text = startDir.absolutePath; textSize = 11f; setTextColor(0xFF77777D.toInt()); setPadding(dp(8), 0, dp(8), dp(10)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })

        if (saveMode) {
            val name = EditText(this).apply {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                setText("novareader_backup_$stamp.zip"); setSingleLine(true); textSize = 14f; setTextColor(android.graphics.Color.WHITE); hint = "Имя файла"
                setPadding(dp(12), 0, dp(12), 0); background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x66000000); setStroke(dp(1), 0x55FFFFFF) }
            }
            root.addView(name, LinearLayout.LayoutParams(-1, dp(44)).apply { bottomMargin = dp(8) })
            val save = TextView(this).apply {
                text = "Сохранить сюда"; textSize = 14f; gravity = android.view.Gravity.CENTER; setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(libraryAccentColor()) }
                setOnClickListener {
                    var filename = name.text.toString().trim()
                    if (filename.isBlank()) filename = "novareader_backup.zip"
                    if (!filename.lowercase(Locale.US).endsWith(".zip")) filename += ".zip"
                    val file = File(startDir, filename)
                    if (file.exists()) {
                        Toast.makeText(this@LibraryActivity, "Файл уже существует", Toast.LENGTH_SHORT).show(); return@setOnClickListener
                    }
                    dialog.dismiss(); startBackup(file)
                }
            }
            root.addView(save, LinearLayout.LayoutParams(-1, dp(46)))
        }

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dirs = startDir.listFiles()?.filter { it.isDirectory && !it.isHidden }?.sortedBy { it.name.lowercase(Locale.getDefault()) } ?: emptyList()
        dirs.forEach { dir -> list.addView(rowView(dir.name, dir.absolutePath, true) { dialog.dismiss(); showBackupBrowser(dir, saveMode) }, LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(8) }) }
        if (!saveMode) {
            val zips = startDir.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }?.sortedByDescending { it.lastModified() } ?: emptyList()
            zips.forEach { file -> list.addView(rowView(file.name, formatSize(file.length()), false) { dialog.dismiss(); confirmRestore(file) }, LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(8) }) }
            if (dirs.isEmpty() && zips.isEmpty()) list.addView(TextView(this).apply { text = "В этой папке нет ZIP-резервных копий."; textSize = 14f; setTextColor(0xFF77777D.toInt()); gravity = android.view.Gravity.CENTER; setPadding(dp(8), dp(40), dp(8), dp(40)) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        applyGlassToDialog(dialog, root)
    }

    private fun confirmRestore(file: File) {
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(10))
        }
        val title = TextView(this).apply {
            text = "Восстановить из резервной копии?"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val message = TextView(this).apply {
            text = "Настройки, книги, позиции чтения, закладки, выделения, заметки, TTS-замены и пользовательские шрифты будут восстановлены поверх текущих данных."
            textSize = 13f
            setTextColor(0xFFB8B8BD.toInt())
            setPadding(0, dp(10), 0, dp(8))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val cancelBtn = TextView(this).apply {
            text = "Отмена"; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTextColor(libraryAccentColor()); setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { dialog.dismiss() }
        }
        val restoreBtn = TextView(this).apply {
            text = "Восстановить"; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(libraryAccentColor()) }
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener { dialog.dismiss(); startRestore(file) }
        }
        buttons.addView(cancelBtn, LinearLayout.LayoutParams(-2, dp(48)))
        buttons.addView(restoreBtn, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(title)
        root.addView(message)
        root.addView(buttons)
        applyGlassToDialog(dialog, root)
    }

    private fun startBackup(file: File) {
        val cancel = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val title = TextView(this).apply {
            text = "Создание резервной копии…"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val accent = libraryAccentColor()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        val status = TextView(this).apply {
            text = "Подготовка…"
            textSize = 12f
            setTextColor(0xFFB8B8BD.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }
        val count = TextView(this).apply {
            text = "0 файлов"
            textSize = 12f
            setTextColor(0xFF77777D.toInt())
        }
        val cancelButton = TextView(this).apply {
            text = "Отмена"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(accent) }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { cancel.set(true) }
        }
        root.addView(title)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(14) })
        root.addView(status)
        root.addView(count)
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })

        root.background = glassBackground()
        dialog.setView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancel.set(true) }
        trackDialog(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val margin = dp(24)
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - margin * 2, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        Thread {
            try {
                val result = BackupManager(this).backup(file, cancel) { p ->
                    runOnUiThread {
                        progress.max = p.total.coerceAtLeast(1)
                        progress.progress = p.current
                        status.text = "→ ${p.name}"
                        count.text = "${p.current} / ${p.total} файлов"
                    }
                }
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (result.cancelled) {
                        file.delete()
                        Toast.makeText(this, "Создание резервной копии отменено", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Резервная копия создана", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    file.delete()
                    AlertDialog.Builder(this)
                        .setTitle("Ошибка резервного копирования")
                        .setMessage(e.message ?: "Не удалось создать резервную копию")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun startRestore(file: File) {
        val cancel = AtomicBoolean(false)
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(18))
        }
        val title = TextView(this).apply {
            text = "Восстановление…"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val accent = libraryAccentColor()
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        }
        val status = TextView(this).apply {
            text = "Проверка архива…"
            textSize = 12f
            setTextColor(0xFFB8B8BD.toInt())
            setPadding(0, dp(10), 0, dp(6))
        }
        val count = TextView(this).apply {
            text = "0 файлов"
            textSize = 12f
            setTextColor(0xFF77777D.toInt())
        }
        val cancelButton = TextView(this).apply {
            text = "Отмена"
            textSize = 14f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(accent) }
            setPadding(dp(18), dp(12), dp(18), dp(12))
            setOnClickListener { cancel.set(true) }
        }
        root.addView(title)
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(18)).apply { topMargin = dp(14) })
        root.addView(status)
        root.addView(count)
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(14) })

        root.background = glassBackground()
        dialog.setView(root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnCancelListener { cancel.set(true) }
        trackDialog(dialog)
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val margin = dp(24)
        dialog.window?.setLayout(resources.displayMetrics.widthPixels - margin * 2, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)

        Thread {
            try {
                val result = BackupManager(this).restore(file, cancel) { p ->
                    runOnUiThread {
                        progress.max = p.total.coerceAtLeast(1)
                        progress.progress = p.current
                        status.text = "← ${p.name}"
                        count.text = "${p.current} / ${p.total} файлов"
                    }
                }
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    if (result.cancelled) {
                        Toast.makeText(this, "Восстановление отменено", Toast.LENGTH_SHORT).show()
                    } else {
                        applyLibraryTheme()
                        loadBooks()
                        Toast.makeText(this, "Резервная копия восстановлена", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (dialog.isShowing) dialog.dismiss()
                    AlertDialog.Builder(this)
                        .setTitle("Ошибка восстановления")
                        .setMessage(e.message ?: "Не удалось восстановить резервную копию")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }


    private fun updateSearchButtonVisibility() {
        val enabled = SettingsManager(this).bookSearchEnabled
        findViewById<View>(R.id.btnLibrarySearch)?.visibility =
            if (enabled) View.VISIBLE else View.GONE
    }

    private fun applyLibraryTheme() {
        if (!::adapter.isInitialized) return
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        val bg = prefs.getString("library_bg", "#1C1C1E") ?: "#1C1C1E"
        val card = prefs.getString("library_card", "#2C2C2E") ?: "#2C2C2E"
        val accent = prefs.getString("library_accent", "#5A4FCF") ?: "#5A4FCF"
        findViewById<View>(R.id.libraryRoot)?.setBackgroundColor(parseColorSafe(bg, 0xFF1C1C1E.toInt()))
        findViewById<View>(R.id.selectionBar)?.setBackgroundColor(parseColorSafe(card, 0xFF2C2C2E.toInt()))
        findViewById<TextView>(R.id.btnLibrarySettings)?.setTextColor(parseColorSafe(accent, 0xFF5A4FCF.toInt()))
        findViewById<TextView>(R.id.btnLibrarySearch)?.setTextColor(parseColorSafe(accent, 0xFF5A4FCF.toInt()))
        findViewById<TextView>(R.id.btnLibraryBackup)?.setTextColor(parseColorSafe(accent, 0xFF5A4FCF.toInt()))
        findViewById<TextView>(R.id.btnLibraryRestore)?.setTextColor(parseColorSafe(accent, 0xFF5A4FCF.toInt()))
        findViewById<TextView>(R.id.searchBooksIcon)?.setTextColor(parseColorSafe(accent, 0xFF5A4FCF.toInt()))
        adapter.setTheme(card, accent)
        populateFormatFilters()
    }

    private fun parseColorSafe(value: String, fallback: Int): Int =
        try { android.graphics.Color.parseColor(value) } catch (_: Exception) { fallback }

    // ── Список хранилищ ────────────────────────────────────────────────
    private fun showStorageRoots() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestFileAccessIfNeeded()
            Toast.makeText(this, "Разрешите NovaReader доступ ко всем файлам и вернитесь в приложение", Toast.LENGTH_LONG).show()
            return
        }

        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Хранилища"
            textSize = 20f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(8), 0, dp(8), 0)
        }
        val close = TextView(this).apply {
            text = "\uE5CD"
            typeface = materialIcons
            textSize = 24f
            setTextColor(libraryAccentColor())
            gravity = android.view.Gravity.CENTER
            contentDescription = "Закрыть"
            setOnClickListener { dialog.dismiss() }
        }
        head.addView(title, LinearLayout.LayoutParams(0, dp(56)).apply { weight = 1f })
        head.addView(close, LinearLayout.LayoutParams(dp(48), dp(56)))
        root.addView(head)

        val hint = TextView(this).apply {
            text = "Выберите хранилище. Внутри любой директории лупа запустит поиск книг во всех вложенных папках."
            textSize = 13f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(dp(8), 0, dp(8), dp(12))
        }
        root.addView(hint)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val entries = collectStorageRoots()
        if (entries.isEmpty()) {
            list.addView(TextView(this).apply {
                text = "Не удалось получить список хранилищ. Проверьте разрешение «Доступ ко всем файлам»."
                textSize = 14f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        } else {
            entries.forEach { (label, dir) ->
                list.addView(
                    rowView(label, dir.absolutePath, isFolder = true) {
                        dialog.dismiss()
                        showFileBrowser(dir)
                    },
                    LinearLayout.LayoutParams(-1, dp(88)).apply { bottomMargin = dp(10) }
                )
            }
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0).apply { weight = 1f })
        applyGlassToDialog(dialog, root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            (resources.displayMetrics.heightPixels * 0.80f).toInt()
        )
    }

    private fun collectStorageRoots(): List<Pair<String, File>> {
        val result = mutableListOf<Pair<String, File>>()
        val seen = mutableSetOf<String>()

        val primary = Environment.getExternalStorageDirectory()
        if (primary != null && primary.exists()) {
            if (seen.add(primary.absolutePath)) result.add("Внутренняя память" to primary)
        }

        try {
            val appDirs = getExternalFilesDirs(null)
            appDirs.filterNotNull().forEach { appDir ->
                var cur: File? = appDir
                while (cur != null && cur.name != "Android") cur = cur.parentFile
                val root = cur?.parentFile ?: return@forEach
                val path = root.absolutePath
                if (!seen.add(path)) return@forEach
                if (result.any { it.second.absolutePath == path }) return@forEach
                result.add("SD-карта (${root.name})" to root)
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "getExternalFilesDirs: ${e.message}")
        }

        NovaLog.d("NovaReader.Library", "Storage roots: " + result.joinToString { "${it.first}=${it.second.absolutePath}" })
        return result
    }

    // ── Файловый менеджер для добавления книг ──────────────────────────
    private fun showFileBrowser(startDir: File) {
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val isStorageRoot = collectStorageRoots().any { it.second.absolutePath == startDir.absolutePath }
        val canGoUp = startDir.parentFile != null && !isStorageRoot

        val back = TextView(this).apply {
            text = "\uE5C4"
            typeface = materialIcons
            textSize = 28f
            setTextColor(libraryAccentColor())
            gravity = android.view.Gravity.CENTER
            contentDescription = if (canGoUp) "Назад" else "К списку хранилищ"
            setOnClickListener {
                dialog.dismiss()
                if (canGoUp) {
                    startDir.parentFile?.let { showFileBrowser(it) }
                } else {
                    showStorageRoots()
                }
            }
        }
        val title = TextView(this).apply {
            text = startDir.name.ifBlank { startDir.absolutePath }
            textSize = 19f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        head.addView(back, LinearLayout.LayoutParams(dp(48), dp(56)))
        head.addView(title, LinearLayout.LayoutParams(0, dp(56)).apply { weight = 1f })

        val scan = TextView(this).apply {
            text = "\uE8B6"
            typeface = materialIcons
            textSize = 24f
            setTextColor(libraryAccentColor())
            gravity = android.view.Gravity.CENTER
            contentDescription = "Сканировать директорию"
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                scanFolderFile(startDir)
            }
        }
        head.addView(scan, LinearLayout.LayoutParams(dp(52), dp(56)))
        root.addView(head)

        val pathLabel = TextView(this).apply {
            text = startDir.absolutePath
            textSize = 11f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(dp(8), 0, dp(8), dp(10))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        root.addView(pathLabel)

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val dirs = startDir.listFiles()
            ?.filter { it.isDirectory && !it.isHidden }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        val books = startDir.listFiles()
            ?.filter { it.isFile && isSupportedBookName(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

        dirs.forEach { dir ->
            list.addView(
                rowView(dir.name, dir.absolutePath, isFolder = true) {
                    dialog.dismiss()
                    showFileBrowser(dir)
                },
                LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(8) }
            )
        }
        books.forEach { file ->
            list.addView(
                rowView(file.name, formatSize(file.length()), isFolder = false) {
                    dialog.dismiss()
                    Toast.makeText(this, "Добавление: ${file.name}…", Toast.LENGTH_SHORT).show()
                    Thread {
                        val ok = importBookFile(file)
                        runOnUiThread {
                            if (ok) {
                                loadBooks()
                                Toast.makeText(this, "Добавлено: ${file.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "Не удалось добавить: ${file.name}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }.start()
                },
                LinearLayout.LayoutParams(-1, dp(84)).apply { bottomMargin = dp(8) }
            )
        }

        if (dirs.isEmpty() && books.isEmpty()) {
            val reason = when {
                !startDir.exists() -> "Папка недоступна: ${startDir.absolutePath}"
                !startDir.canRead() -> "Нет доступа к папке: ${startDir.absolutePath}"
                else -> "Папка пуста или книг здесь нет."
            }
            list.addView(TextView(this).apply {
                text = reason
                textSize = 14f
                setTextColor(android.graphics.Color.GRAY)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(8), dp(40), dp(8), dp(40))
            })
        }

        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0).apply { weight = 1f })
        applyGlassToDialog(dialog, root)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.96f).toInt(),
            (resources.displayMetrics.heightPixels * 0.86f).toInt()
        )
    }

    private fun rowView(name: String, subtitle: String, isFolder: Boolean, action: () -> Unit): View {
        val accent = libraryAccentColor()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            isClickable = true
            setOnClickListener { action() }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(0x33FFFFFF)
                setStroke(dp(1), if (isFolder) ((accent and 0x00FFFFFF) or (0x66 shl 24)) else 0x22FFFFFF)
            }
        }
        val icon = ImageView(this).apply {
            setImageResource(if (isFolder) R.drawable.ic_folder_nova else R.drawable.ic_book_nova)
            setColorFilter(if (isFolder) accent else 0xFF9A9CA5.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(4), 0)
        }
        val nameView = TextView(this).apply {
            text = name
            textSize = 16f
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitleView = TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        col.addView(nameView)
        col.addView(subtitleView)

        row.addView(icon, LinearLayout.LayoutParams(dp(56), dp(56)))
        row.addView(col, LinearLayout.LayoutParams(0, -2).apply { weight = 1f })
        return row
    }

    // ── Сканирование папки рекурсивно ──────────────────────────────────
    private fun scanFolderFile(dir: File) {
        if (scanInProgress) {
            Toast.makeText(this, "Сканирование уже идёт…", Toast.LENGTH_SHORT).show()
            return
        }
        if (!dir.exists() || !dir.isDirectory) {
            Toast.makeText(this, "Папка недоступна", Toast.LENGTH_SHORT).show()
            return
        }
        scanInProgress = true
        Toast.makeText(this, "Сканирование…", Toast.LENGTH_SHORT).show()
        Thread {
            var added = 0
            var skipped = 0
            try {
                val files = mutableListOf<File>()
                collectBookFilesFile(dir, files)
                for (file in files) {
                    if (importBookFile(file)) added++ else skipped++
                }
            } catch (e: Exception) {
                runOnUiThread {
                    scanInProgress = false
                    Toast.makeText(this, "Ошибка сканирования: ${e.message}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            runOnUiThread {
                scanInProgress = false
                loadBooks()
                Toast.makeText(
                    this,
                    "Сканирование завершено: добавлено $added, пропущено $skipped",
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }

    private fun collectBookFilesFile(dir: File, out: MutableList<File>) {
        dir.listFiles()?.forEach {
            if (it.isDirectory && !it.isHidden) collectBookFilesFile(it, out)
            else if (it.isFile && isSupportedBookName(it.name)) out.add(it)
        }
    }

    private fun isSupportedBookName(name: String): Boolean {
        return when (name.substringAfterLast('.', "").lowercase()) {
            "epub", "fb2", "cbz", "pdf", "mobi", "azw3" -> true
            // .zip может содержать одну или несколько книг внутри —
            // при импорте распакуем и добавим всё, что найдём.
            "zip" -> true
            else -> false
        }
    }

    // ── Импорт одного файла ────────────────────────────────────────────
    // Если файл .zip — распаковываем во временную папку, импортируем
    // каждую книгу внутри. Файл .zip удаляется после распаковки.
    // Иначе — прежнее поведение: определить формат, скопировать в books.
    private fun importBookFile(file: File): Boolean {
        if (!file.exists() || !file.isFile) return false
        val ext = file.extension.lowercase()

        // ── ZIP: распаковать и импортировать всё, что найдётся ─────────
        if (ext == "zip") {
            return importZipArchive(file)
        }

        val displayName = file.name
        val type = when (ext) {
            "epub" -> "epub"
            "fb2" -> "fb2"
            "cbz" -> "cbz"
            "pdf" -> "pdf"
            "mobi" -> "mobi"
            "azw3" -> "azw3"
            else -> return false
        }
        val booksDir = NovaStorage.booksDir(this)
        val cleanName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        var dest = File(booksDir, cleanName)
        if (dest.exists() && dest.length() == file.length()) {
            library.addBook(dest.name, displayName, type)
            return true
        }
        if (dest.exists()) dest = File(booksDir, "${cleanName}_${UUID.randomUUID().toString().take(8)}")
        return try {
            file.inputStream().use { input -> dest.outputStream().use { output -> input.copyTo(output) } }
            library.addBook(dest.name, displayName, type)
            true
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "Не удалось импортировать $displayName: ${e.message}")
            false
        }
    }

    /**
     * Распаковка zip-архива с книгами.
     *
     * Логика:
     *  1. Читаем zip через ZipFile.
     *  2. Для каждой записи, чьё расширение поддерживается (.fb2/.epub/
     *     .mobi/.pdf/.txt/.rtf/.azw3/.cbz), распаковываем во временную
     *     папку кэша.
     *  3. Каждую распакованную книгу импортируем обычным importBookFile.
     *  4. Временную папку удаляем.
     *
     * Не полагаемся на имя архива: если пользователь скачал fb2.zip с
     * Флибусты — внутри один .fb2; если скачал подборку "Стругацкие.zip"
     * — там может быть 20 .fb2 и 5 .epub. Всё импортируем.
     *
     * @return true, если удалось импортировать хотя бы одну книгу.
     */
    private fun importZipArchive(zipFile: File): Boolean {
        val supportedExt = setOf("fb2", "epub", "mobi", "pdf", "txt", "rtf", "azw3", "cbz")
        val tempDir = File(cacheDir, "zip_import_${System.currentTimeMillis()}")
        if (!tempDir.mkdirs()) {
            NovaLog.e("NovaReader.Library", "Не удалось создать временную папку для распаковки")
            return false
        }
        var importedAny = false
        try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().asSequence()
                    .filter { !it.isDirectory }
                    // Отсекаем macOS-мусор __MACOSX/, .DS_Store.
                    .filter { e ->
                        val n = e.name
                        !n.startsWith("__MACOSX/") && !n.endsWith("/.DS_Store") && !n.endsWith(".DS_Store")
                    }
                    .filter { e ->
                        val n = e.name.substringAfterLast('/').lowercase()
                        val ext = n.substringAfterLast('.', "")
                        ext in supportedExt
                    }
                    .toList()
                if (entries.isEmpty()) {
                    NovaLog.d("NovaReader.Library", "В zip нет поддерживаемых файлов: ${zipFile.name}")
                    return false
                }
                for (entry in entries) {
                    try {
                        // Чистое имя файла внутри zip — без префиксов путей.
                        val entryName = entry.name.substringAfterLast('/')
                        // Защита от повторяющихся имён внутри одного архива.
                        val target = File(tempDir, entryName)
                        val uniqueTarget = if (target.exists()) {
                            File(tempDir, "${System.currentTimeMillis()}_$entryName")
                        } else target

                        zip.getInputStream(entry).use { input ->
                            FileOutputStream(uniqueTarget).use { output -> input.copyTo(output) }
                        }
                        if (importBookFile(uniqueTarget)) {
                            importedAny = true
                        }
                        uniqueTarget.delete()
                    } catch (e: Exception) {
                        NovaLog.e("NovaReader.Library", "Ошибка импорта записи ${entry.name}: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            NovaLog.e("NovaReader.Library", "Не удалось распаковать ${zipFile.name}: ${e.message}")
        } finally {
            tempDir.deleteRecursively()
        }
        return importedAny
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format("%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format("%.1f MB", mb)
    }

    // ── Режим выбора для удаления ───────────────────────────────────────
    private fun enterSelectionMode(firstSelectedId: String) {
        selectionMode = true
        selectedIds.clear()
        selectedIds.add(firstSelectedId)
        titleBar.visibility = View.GONE
        selectionBar.visibility = View.VISIBLE
        updateSelectionCount()
        adapter.notifyDataSetChanged()
    }

    private fun exitSelectionMode() {
        selectionMode = false
        selectedIds.clear()
        titleBar.visibility = View.VISIBLE
        selectionBar.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    private fun toggleSelection(bookId: String) {
        if (!selectedIds.add(bookId)) selectedIds.remove(bookId)
        if (selectedIds.isEmpty()) exitSelectionMode() else {
            updateSelectionCount()
            adapter.notifyDataSetChanged()
        }
    }

    private fun updateSelectionCount() {
        selectionCountText.text = "Выбрано: ${selectedIds.size}"
    }

    private fun confirmDeleteSelected() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        val dialog = AlertDialog.Builder(this).create()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(10))
        }
        val title = TextView(this).apply {
            text = if (count == 1) "Удалить книгу?" else "Удалить книги?"
            textSize = 17f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val message = TextView(this).apply {
            text = if (count == 1) {
                "Книга и её данные чтения (позиция, закладки, выделения, заметки) будут удалены."
            } else {
                "Выбранные книги ($count) и их данные чтения (позиция, закладки, выделения, заметки) будут удалены."
            }
            textSize = 13f
            setTextColor(0xFFB8B8BD.toInt())
            setPadding(0, dp(10), 0, dp(8))
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
        }
        val cancelBtn = TextView(this).apply {
            text = "Отмена"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(libraryAccentColor())
            setPadding(dp(16), 0, dp(16), 0)
            setOnClickListener { dialog.dismiss() }
        }
        val deleteColor = 0xFFE25555.toInt()
        val deleteBtn = TextView(this).apply {
            text = "Удалить"
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(deleteColor)
            }
            setPadding(dp(20), dp(8), dp(20), dp(8))
            setOnClickListener {
                dialog.dismiss()
                val ids = selectedIds.toList()
                exitSelectionMode()
                Thread {
                    ids.forEach { library.removeBook(it) }
                    runOnUiThread { loadBooks() }
                }.start()
            }
        }
        buttons.addView(cancelBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        buttons.addView(deleteBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)))
        root.addView(title)
        root.addView(message)
        root.addView(buttons)
        applyGlassToDialog(dialog, root)
    }

    // ── Список книг ─────────────────────────────────────────────────────
    private fun loadBooks() {
        if (!::adapter.isInitialized) return
        val books = JSONArray(library.getBooks())
        val list = mutableListOf<BookItem>()
        for (i in 0 until books.length()) {
            val b = books.getJSONObject(i)
            list.add(
                BookItem(
                    id = b.optString("id"),
                    title = b.optString("title"),
                    author = b.optString("author"),
                    coverPath = b.optString("cover_path").ifBlank { null },
                    format = b.optString("format"),
                    relativePath = b.optString("relative_path"),
                    progress = b.optDouble("progress", 0.0),
                    lastRead = b.optLong("last_read", 0L)
                )
            )
        }
        allBooks = list
        applyBookFilters()
    }

    private fun applyBookFilters() {
        if (!::adapter.isInitialized) return
        val query = searchBooksEdit.text?.toString()?.trim()?.lowercase(Locale.getDefault()).orEmpty()
        var filtered = allBooks.asSequence()
            .filter { selectedFormat == "ALL" || it.format.equals(selectedFormat, ignoreCase = true) }
            .filter { query.isBlank() || it.title.lowercase(Locale.getDefault()).contains(query) }
            .toMutableList()

        filtered = when (sortMode) {
            SortMode.LAST_OPENED -> filtered.sortedWith(compareByDescending<BookItem> { it.lastRead }.thenBy { it.title.lowercase(Locale.getDefault()) }).toMutableList()
            SortMode.TITLE -> filtered.sortedWith(compareBy<BookItem> { it.title.lowercase(Locale.getDefault()) }.thenBy { it.author.lowercase(Locale.getDefault()) }).toMutableList()
            SortMode.AUTHOR -> filtered.sortedWith(compareBy<BookItem> { it.author.lowercase(Locale.getDefault()) }.thenBy { it.title.lowercase(Locale.getDefault()) }).toMutableList()
            SortMode.PROGRESS -> filtered.sortedWith(compareByDescending<BookItem> { it.progress }.thenBy { it.title.lowercase(Locale.getDefault()) }).toMutableList()
        }

        adapter.submit(filtered)
        val hasAnyBooks = allBooks.isNotEmpty()
        val hasFilteredBooks = filtered.isNotEmpty()
        emptyText.text = if (hasAnyBooks) "По этому запросу книги не найдены." else "Пока нет ни одной книги.\nНажмите «+», чтобы добавить."
        emptyText.visibility = if (!hasFilteredBooks) View.VISIBLE else View.GONE
        recyclerView.visibility = View.VISIBLE
        selectedIds.retainAll(filtered.map { it.id }.toSet())
        if (selectionMode && selectedIds.isEmpty()) exitSelectionMode()
    }

    private fun openBook(book: BookItem) {
        setResult(Activity.RESULT_OK, Intent().apply {
            putExtra(EXTRA_RELATIVE_PATH, book.relativePath)
            putExtra(EXTRA_DISPLAY_NAME, book.title)
            putExtra(EXTRA_FORMAT, book.format)
        })
        finish()
    }

    private fun libraryAccentColor(): Int {
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        return parseColorSafe(prefs.getString("library_accent", "#5A4FCF") ?: "#5A4FCF", 0xFF5A4FCF.toInt())
    }

    private fun libraryCardColor(): Int {
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        return parseColorSafe(prefs.getString("library_card", "#2C2C2E") ?: "#2C2C2E", 0xFF2C2C2E.toInt())
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_RELATIVE_PATH = "relative_path"
        const val EXTRA_DISPLAY_NAME = "display_name"
        const val EXTRA_FORMAT = "format"
    }
}

data class BookItem(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val format: String,
    val relativePath: String,
    val progress: Double,
    val lastRead: Long,
)

private class BooksAdapter(
    val isSelectionMode: () -> Boolean,
    val isSelected: (String) -> Boolean,
    val onAdd: () -> Unit,
    val onOpen: (BookItem) -> Unit,
    val onLongPress: (BookItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private val items = mutableListOf<BookItem>()
    private var cardColor = 0xFF2C2C2E.toInt()
    private var accentColor = 0xFF5A4FCF.toInt()

    fun setTheme(card: String, accent: String) {
        cardColor = try { android.graphics.Color.parseColor(card) } catch (_: Exception) { 0xFF2C2C2E.toInt() }
        accentColor = try { android.graphics.Color.parseColor(accent) } catch (_: Exception) { 0xFF5A4FCF.toInt() }
        notifyDataSetChanged()
    }

    fun submit(newItems: List<BookItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    private class AddVH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.addCardRoot)
        val plus: TextView = view.findViewById(R.id.addCardPlus)
    }

    private class BookVH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.bookCardRoot)
        val coverFrame: View = view.findViewById(R.id.coverFrame)
        val cover: ImageView = view.findViewById(R.id.coverImage)
        val placeholder: TextView = view.findViewById(R.id.coverPlaceholderText)
        val title: TextView = view.findViewById(R.id.titleText)
        val author: TextView = view.findViewById(R.id.authorText)
        val progress: ProgressBar = view.findViewById(R.id.progressBar)
        val progressBadge: TextView = view.findViewById(R.id.progressBadge)
        val status: TextView = view.findViewById(R.id.statusText)
        val format: TextView = view.findViewById(R.id.formatText)
        val lastRead: TextView = view.findViewById(R.id.lastReadText)
        val checkbox: CheckBox = view.findViewById(R.id.selectCheckbox)
    }

    override fun getItemViewType(position: Int) = if (position == items.size) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == 0)
            AddVH(LayoutInflater.from(parent.context).inflate(R.layout.item_add_book, parent, false))
        else
            BookVH(LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false))

    override fun getItemCount() = items.size + 1

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is AddVH) {
            holder.root.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            holder.plus.setTextColor(accentColor)
            holder.root.setOnClickListener { onAdd() }
            holder.root.setOnLongClickListener(null)
            return
        }
        val h = holder as BookVH
        val book = items[position]
        val percent = Math.round(book.progress * 100.0).toInt().coerceIn(0, 100)

        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * h.root.resources.displayMetrics.density
            setColor(cardColor)
            setStroke((1 * h.root.resources.displayMetrics.density).toInt().coerceAtLeast(1), 0x18FFFFFF)
        }
        if (isSelectionMode() && isSelected(book.id)) {
            cardBg.setStroke((2 * h.root.resources.displayMetrics.density).toInt().coerceAtLeast(2), accentColor)
        }
        h.root.background = cardBg
        h.root.elevation = 2f * h.root.resources.displayMetrics.density

        h.title.text = book.title
        h.author.text = book.author.ifBlank { "Неизвестен" }
        h.progress.progress = percent
        h.progress.progressTintList = ColorStateList.valueOf(accentColor)
        h.progress.backgroundTintList = ColorStateList.valueOf(0x22FFFFFF)
        h.progressBadge.text = "$percent%"
        h.status.text = when {
            percent >= 99 -> "Прочитано"
            percent > 0 -> "Читаю"
            else -> "Не начато"
        }
        h.status.setTextColor(accentColor)
        h.status.background = roundedLabel(h.root, accentColor, 0x22)
        h.format.text = book.format.uppercase(Locale.getDefault())
        h.lastRead.text = if (book.lastRead > 0L) {
            "Открыта:\n${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(book.lastRead))}"
        } else {
            "Открыта:\nещё не открывалась"
        }

        h.coverFrame.background = roundedBg(h.root, 0xFF191A1D.toInt(), 12f)
        h.cover.scaleType = ImageView.ScaleType.CENTER_CROP

        h.root.post {
            val w = h.root.width
            if (w > 0) {
                val targetH = (w * 1.5f).toInt()
                val lp = h.coverFrame.layoutParams
                if (lp.height != targetH) {
                    lp.height = targetH
                    h.coverFrame.layoutParams = lp
                    h.coverFrame.requestLayout()
                }
            }
        }

        if (book.coverPath != null) {
            val file = File(NovaStorage.rootDir(h.itemView.context), book.coverPath)
            val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
            if (bitmap != null) {
                h.cover.setImageBitmap(bitmap)
                h.cover.visibility = View.VISIBLE
                h.placeholder.visibility = View.GONE
            } else {
                showPlaceholder(h, book)
            }
        } else {
            showPlaceholder(h, book)
        }
        val select = isSelectionMode()
        h.checkbox.visibility = if (select) View.VISIBLE else View.GONE
        h.checkbox.buttonTintList = checkboxTint(accentColor)
        h.checkbox.isChecked = select && isSelected(book.id)
        h.itemView.alpha = if (select && !isSelected(book.id)) 0.55f else 1f
        h.itemView.setOnClickListener { onOpen(book) }
        h.itemView.setOnLongClickListener { onLongPress(book); true }
    }

    private fun checkboxTint(accent: Int): ColorStateList {
        return ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(accent, 0x668A8B92, 0xB3A7A9B2.toInt())
        )
    }

    private fun roundedBg(view: View, color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusDp * view.resources.displayMetrics.density
        setColor(color)
    }

    private fun roundedLabel(view: View, color: Int, alphaValue: Int): GradientDrawable {
        val c = (color and 0x00FFFFFF) or ((alphaValue.coerceIn(0, 255)) shl 24)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 8f * view.resources.displayMetrics.density
            setColor(c)
        }
    }

    private fun showPlaceholder(holder: BookVH, book: BookItem) {
        holder.cover.visibility = View.GONE
        holder.placeholder.visibility = View.VISIBLE
        holder.placeholder.text = book.title
    }
}
