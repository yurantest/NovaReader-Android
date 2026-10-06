package com.novareader.app

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

enum class TtsCorrectionScope { GLOBAL, BOOK }

/**
 * Экран коррекций произношения TTS — Android-аналог TTSCorrectionWindow.
 * Две вкладки: Глобальные / Эта книга.
 *
 * Удаление — по иконке корзины в каждой строке. Никакого режима выделения
 * строк: одно нажатие — одно удаление, EditText и CheckBox сохраняют свои
 * жесты (курсор, выделение текста, клик по чекбоксу).
 *
 * Все цвета берутся из настроек (library_bg / library_card / library_accent),
 * применяются программно — чтобы шапка, вкладки, строки и кнопки
 * нижней панели были в одной гамме. Это касается и фона EditText, и
 * тинта CheckBox — иначе они остаются системного голубоватого оттенка.
 */
class TtsCorrectionActivity : AppCompatActivity() {

    private lateinit var corrections: CorrectionsManager

    private lateinit var tabsRow: LinearLayout
    private lateinit var tabGlobal: TextView
    private lateinit var tabBook: TextView
    private lateinit var list: RecyclerView
    private lateinit var infoLabel: TextView

    private val adapter = CorrectionAdapter()
    private var currentScope: TtsCorrectionScope = TtsCorrectionScope.GLOBAL
    private var bookRelativePath: String? = null

    private var themeBg = 0xFF1C1C1E.toInt()
    private var themeCard = 0xFF2C2C2E.toInt()
    private var themeAccent = 0xFF5A4FCF.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tts_correction)
        ThemeUtils.applyInputCursors(findViewById(android.R.id.content), this)

        corrections = CorrectionsManager(this)
        bookRelativePath = currentBookRelativePath()

        tabsRow   = findViewById(R.id.ttsCorrTabs)
        tabGlobal = findViewById(R.id.ttsCorrTabGlobal)
        tabBook   = findViewById(R.id.ttsCorrTabBook)
        list      = findViewById(R.id.ttsCorrList)
        infoLabel = findViewById(R.id.ttsCorrInfo)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        tabGlobal.setOnClickListener { switchScope(TtsCorrectionScope.GLOBAL) }
        tabBook.setOnClickListener { switchScope(TtsCorrectionScope.BOOK) }

        if (bookRelativePath == null) {
            tabBook.alpha = 0.4f
            tabBook.isEnabled = false
        }

        findViewById<View>(R.id.ttsCorrAdd).setOnClickListener {
            adapter.addEmpty()
            updateInfo()
        }
        findViewById<View>(R.id.ttsCorrSave).setOnClickListener { saveAndFinish() }
        findViewById<View>(R.id.ttsCorrBack).setOnClickListener { finish() }

        switchScope(TtsCorrectionScope.GLOBAL)
        applyTheme()
    }

    private fun currentBookRelativePath(): String? {
        val prefs = getSharedPreferences("novareader_book", MODE_PRIVATE)
        val p = prefs.getString("current_book_path", null)
        return p?.takeIf { it.isNotBlank() }
    }

    private fun switchScope(scope: TtsCorrectionScope) {
        currentScope = scope
        val selectedBg = (themeAccent and 0x00FFFFFF) or (0x33 shl 24)
        if (scope == TtsCorrectionScope.GLOBAL) {
            tabGlobal.background = roundedRect(selectedBg, 12)
            tabBook.background = roundedRect(0x00000000, 12)
            adapter.submit(corrections.getGlobal())
        } else {
            tabBook.background = roundedRect(selectedBg, 12)
            tabGlobal.background = roundedRect(0x00000000, 12)
            val p = bookRelativePath ?: return
            adapter.submit(corrections.getForBook(p))
        }
        updateInfo()
    }

    private fun updateInfo() {
        val n = adapter.itemCount
        val scope = if (currentScope == TtsCorrectionScope.GLOBAL) "глобальных" else "книжных"
        infoLabel.text = "$n $scope замен"
    }

    private fun saveAndFinish() {
        val entries = adapter.currentEntries()
        when (currentScope) {
            TtsCorrectionScope.GLOBAL -> corrections.setGlobal(entries)
            TtsCorrectionScope.BOOK   -> bookRelativePath?.let { corrections.setForBook(it, entries) }
        }
        sendCorrectionsToReader()
        Toast.makeText(this, "Сохранено: ${entries.size}", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun sendCorrectionsToReader() {
        val combined = corrections.getCombined(bookRelativePath)
        val json = correctionsJson(combined)
        ReaderBridgeHolder.webView?.post {
            ReaderBridgeHolder.webView?.evaluateJavascript(
                "window.updateTTSCorrections && window.updateTTSCorrections($json);",
                null
            )
        }
    }

    private fun correctionsJson(list: List<CorrectionEntry>): String {
        val sb = StringBuilder("[")
        list.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append("{\"wrong\":\"").append(escape(e.wrong)).append("\",")
            sb.append("\"correct\":\"").append(escape(e.correct)).append("\",")
            sb.append("\"case_insensitive\":").append(e.caseInsensitive).append("}")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun applyTheme() {
        val prefs = getSharedPreferences("novareader_settings", MODE_PRIVATE)
        val bg = prefs.getString("library_bg", "#1C1C1E") ?: "#1C1C1E"
        val card = prefs.getString("library_card", "#2C2C2E") ?: "#2C2C2E"
        val accent = prefs.getString("library_accent", "#5A4FCF") ?: "#5A4FCF"
        themeBg = parseColorSafe(bg, 0xFF1C1C1E.toInt())
        themeCard = parseColorSafe(card, 0xFF2C2C2E.toInt())
        themeAccent = parseColorSafe(accent, 0xFF5A4FCF.toInt())

        findViewById<View>(R.id.ttsCorrRoot)?.setBackgroundColor(themeBg)
        // Вкладки — чуть темнее карточки, чтобы визуально отличались от строк.
        tabsRow.setBackgroundColor(blend(themeCard, themeBg, 0.35f))
        findViewById<TextView>(R.id.ttsCorrTitle)?.setTextColor(themeAccent)
        findViewById<TextView>(R.id.ttsCorrBack)?.setTextColor(themeAccent)

        // Передаём цвета в адаптер — он сам покрасит поля и чекбоксы.
        adapter.setTheme(
            rowBackground = themeCard,
            fieldBackground = blend(themeBg, themeCard, 0.4f),
            accent = themeAccent,
            textColor = android.graphics.Color.WHITE,
            hintColor = 0x777981,
            deleteIconColor = 0xFFFF6B6B.toInt(),
        )

        // Кнопки нижней панели: «+» нейтральная карточка, «Сохранить» — акцент.
        val addBtn  = findViewById<TextView>(R.id.ttsCorrAdd)
        val saveBtn = findViewById<TextView>(R.id.ttsCorrSave)

        addBtn.background = roundedRect(themeCard, 10)
        addBtn.setTextColor(themeAccent)

        saveBtn.background = roundedRect(themeAccent, 10)
        saveBtn.setTextColor(android.graphics.Color.WHITE)

        if (::tabsRow.isInitialized) switchScope(currentScope)
    }

    private fun parseColorSafe(v: String, fallback: Int): Int =
        try { android.graphics.Color.parseColor(v) } catch (_: Exception) { fallback }

    private fun roundedRect(color: Int, radiusDp: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radiusDp * resources.displayMetrics.density
            setColor(color)
        }

    private fun blend(c1: Int, c2: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val a = android.graphics.Color.alpha(c1)
        val rr = (android.graphics.Color.red(c1)   * (1 - r) + android.graphics.Color.red(c2)   * r).toInt()
        val gg = (android.graphics.Color.green(c1) * (1 - r) + android.graphics.Color.green(c2) * r).toInt()
        val bb = (android.graphics.Color.blue(c1)  * (1 - r) + android.graphics.Color.blue(c2)  * r).toInt()
        return android.graphics.Color.argb(a, rr, gg, bb)
    }
}

// ── Адаптер ─────────────────────────────────────────────────────────

private class CorrectionAdapter : RecyclerView.Adapter<CorrectionAdapter.VH>() {

    private val items = mutableListOf<Row>()

    private var rowBg = 0x00000000
    private var fieldBg = 0x00000000
    private var accentColor = 0xFF5A4FCF.toInt()
    private var textColor = 0xFFFFFFFF.toInt()
    private var hintColor = 0x777981
    private var deleteIconColor = 0xFFFF6B6B.toInt()

    data class Row(
        var wrong: String = "",
        var correct: String = "",
        var caseInsensitive: Boolean = true,
    )

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val wrong: EditText = view.findViewById(R.id.corrWrong)
        val correct: EditText = view.findViewById(R.id.corrCorrect)
        init {
            ThemeUtils.applyInputCursor(wrong, view.context)
            ThemeUtils.applyInputCursor(correct, view.context)
        }
        val case: CheckBox = view.findViewById(R.id.corrCase)
        val delete: TextView = view.findViewById(R.id.corrDelete)
    }

    fun setTheme(
        rowBackground: Int,
        fieldBackground: Int,
        accent: Int,
        textColor: Int,
        hintColor: Int,
        deleteIconColor: Int,
    ) {
        this.rowBg = rowBackground
        this.fieldBg = fieldBackground
        this.accentColor = accent
        this.textColor = textColor
        this.hintColor = hintColor
        this.deleteIconColor = deleteIconColor
        notifyDataSetChanged()
    }

    fun submit(list: List<CorrectionEntry>) {
        items.clear()
        list.forEach { items.add(Row(it.wrong, it.correct, it.caseInsensitive)) }
        notifyDataSetChanged()
    }

    fun addEmpty() {
        items.add(Row())
        notifyItemInserted(items.size - 1)
    }

    fun currentEntries(): List<CorrectionEntry> =
        items.filter { it.wrong.isNotBlank() }.map {
            CorrectionEntry(it.wrong.trim(), it.correct.trim(), it.caseInsensitive)
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_correction, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val r = items[position]

        // ── Поля ввода и чекбокс под стиль темы ──────────────────────────
        // Фон EditText — темнее карточки, цвет текста и hint тоже из темы.
        // Без этого EditText и CheckBox берут системный светлый фон/тинт
        // и выглядят инородно на фоне тёмной карточки.
        val fieldDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 8f * holder.root.resources.displayMetrics.density
            setColor(fieldBg)
            setStroke(
                (1 * holder.root.resources.displayMetrics.density).toInt().coerceAtLeast(1),
                (accentColor and 0x00FFFFFF) or (0x33 shl 24)
            )
        }
        holder.wrong.background = fieldDrawable
        holder.correct.background = fieldDrawable
        holder.wrong.setTextColor(textColor)
        holder.correct.setTextColor(textColor)
        holder.wrong.setHintTextColor(hintColor)
        holder.correct.setHintTextColor(hintColor)

        // Тинот чекбокса — акцентный.
        val states = android.content.res.ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_enabled),
                intArrayOf()
            ),
            intArrayOf(accentColor, 0x668A8B92, 0xB3A7A9B2.toInt())
        )
        holder.case.buttonTintList = states

        // Фон строки — карточка темы.
        holder.root.background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * holder.root.resources.displayMetrics.density
            setColor(rowBg)
        }

        // Иконка корзины — красный акцент, чтобы сразу читалась как
        // «удалить эту строку».
        holder.delete.setTextColor(deleteIconColor)

        holder.wrong.setText(r.wrong)
        holder.correct.setText(r.correct)
        holder.case.isChecked = r.caseInsensitive

        holder.wrong.addTextChangedListener(simple { r.wrong = it })
        holder.correct.addTextChangedListener(simple { r.correct = it })
        holder.case.setOnCheckedChangeListener { _, v -> r.caseInsensitive = v }

        // Тап по корзине — удалить ровно эту строку.
        holder.delete.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && pos in items.indices) {
                items.removeAt(pos)
                notifyItemRemoved(pos)
                notifyItemRangeChanged(pos, items.size - pos)
            }
        }
    }

    private fun simple(onChanged: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChanged(s?.toString() ?: "")
    }
}